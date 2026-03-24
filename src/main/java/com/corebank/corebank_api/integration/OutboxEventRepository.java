package com.corebank.corebank_api.integration;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Repository for outbox events using JDBC for maximum reliability.
 * Provides atomic operations for outbox pattern implementation.
 */
@Repository
public class OutboxEventRepository {
    
    private static final int DEFAULT_MAX_RETRIES = 3;
    private static final int DEFAULT_RETRY_BACKOFF_SECONDS = 30;
    private static final int DEFAULT_RECLAIM_TIMEOUT_SECONDS = 300;

    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;
    
    @Autowired
    public OutboxEventRepository(JdbcTemplate jdbcTemplate, NamedParameterJdbcTemplate namedParameterJdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.namedParameterJdbcTemplate = namedParameterJdbcTemplate;
    }
    
    private final RowMapper<OutboxEvent> rowMapper = new OutboxEventRowMapper();
    
    /**
     * Insert a new outbox event
     */
    public Long insert(OutboxEvent event) {
        String sql = """
            SELECT insert_outbox_event(
                :aggregateType,
                :aggregateId,
                :eventType,
                :eventData::jsonb,
                :correlationId,
                :causationId
            )
            """;
        
        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("aggregateType", event.getAggregateType())
            .addValue("aggregateId", event.getAggregateId())
            .addValue("eventType", event.getEventType())
            .addValue("eventData", event.getEventData())
            .addValue("correlationId", event.getCorrelationId())
            .addValue("causationId", event.getCausationId());
        
        return namedParameterJdbcTemplate.queryForObject(sql, params, Long.class);
    }
    
    /**
     * Get pending events for processing
     */
    public List<OutboxEvent> getPendingEvents(int limit) {
        return getPendingEvents(
            limit,
            DEFAULT_MAX_RETRIES,
            DEFAULT_RETRY_BACKOFF_SECONDS,
            DEFAULT_RECLAIM_TIMEOUT_SECONDS
        );
    }

    /**
     * Get claimable events for processing with retry/reclaim controls.
     */
    public List<OutboxEvent> getPendingEvents(
            int limit,
            int maxRetries,
            int retryBackoffSeconds,
            int reclaimTimeoutSeconds) {
        String sql = """
            SELECT id, aggregate_type, aggregate_id, event_type, event_data, 
                   created_at, processed_at, status, retry_count, last_error,
                   correlation_id, causation_id
            FROM get_pending_outbox_events(
                :limit,
                :maxRetries,
                :retryBackoffSeconds,
                :reclaimTimeoutSeconds
            )
            """;
        
        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("limit", limit)
            .addValue("maxRetries", maxRetries)
            .addValue("retryBackoffSeconds", retryBackoffSeconds)
            .addValue("reclaimTimeoutSeconds", reclaimTimeoutSeconds);
        
        return namedParameterJdbcTemplate.query(sql, params, rowMapper);
    }
    
    /**
     * Mark event as processed
     */
    public boolean markAsProcessed(Long eventId, String status) {
        String sql = """
            UPDATE outbox_events
            SET processed_at = CURRENT_TIMESTAMP,
                processing_started_at = NULL,
                status = :status
            WHERE id = :eventId
              AND status IN ('PENDING', 'PROCESSING')
            """;

        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("eventId", eventId)
            .addValue("status", status);

        return namedParameterJdbcTemplate.update(sql, params) == 1;
    }
    
    /**
     * Mark event as failed
     */
    public boolean markAsFailed(Long eventId, String error) {
        String sql = """
            UPDATE outbox_events
            SET processed_at = CURRENT_TIMESTAMP,
                processing_started_at = NULL,
                status = 'FAILED',
                retry_count = retry_count + 1,
                last_error = :error
            WHERE id = :eventId
              AND status IN ('PENDING', 'PROCESSING', 'FAILED')
            """;

        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("eventId", eventId)
            .addValue("error", error);

        return namedParameterJdbcTemplate.update(sql, params) == 1;
    }
    
    /**
     * Get event by ID
     */
    public Optional<OutboxEvent> findById(Long id) {
        String sql = """
            SELECT id, aggregate_type, aggregate_id, event_type, event_data, 
                   created_at, processed_at, status, retry_count, last_error,
                   correlation_id, causation_id
            FROM outbox_events WHERE id = :id
            """;
        
        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("id", id);
        
        List<OutboxEvent> events = namedParameterJdbcTemplate.query(sql, params, rowMapper);
        return events.stream().findFirst();
    }
    
    /**
     * Get statistics for monitoring
     */
    public OutboxStats getStats() {
        String sql = """
            SELECT 
                COUNT(*) as total,
                COUNT(*) FILTER (WHERE status = 'PENDING') as pending,
                COUNT(*) FILTER (WHERE status = 'PROCESSING') as processing,
                COUNT(*) FILTER (WHERE status = 'PROCESSED') as processed,
                COUNT(*) FILTER (WHERE status = 'FAILED') as failed,
                COUNT(*) FILTER (WHERE created_at > NOW() - INTERVAL '1 hour') as created_last_hour,
                COUNT(*) FILTER (WHERE processed_at > NOW() - INTERVAL '1 hour') as processed_last_hour
            FROM outbox_events
            """;
        
        return jdbcTemplate.queryForObject(sql, (rs, rowNum) -> OutboxStats.builder()
            .total(rs.getLong("total"))
            .pending(rs.getLong("pending"))
            .processing(rs.getLong("processing"))
            .processed(rs.getLong("processed"))
            .failed(rs.getLong("failed"))
            .createdLastHour(rs.getLong("created_last_hour"))
            .processedLastHour(rs.getLong("processed_last_hour"))
            .build());
    }
    
    /**
     * Clean up old processed events (for maintenance)
     */
    public int cleanupProcessedEvents(int daysToKeep) {
        String sql = """
            DELETE FROM outbox_events 
            WHERE status = 'PROCESSED' 
            AND processed_at < NOW() - INTERVAL ':days days'
            """;
        
        return jdbcTemplate.update(sql.replace(":days", String.valueOf(daysToKeep)));
    }
    
    /**
     * Row mapper for OutboxEvent
     */
    private static class OutboxEventRowMapper implements RowMapper<OutboxEvent> {
        @Override
        public OutboxEvent mapRow(ResultSet rs, int rowNum) throws SQLException {
            OffsetDateTime createdAt = rs.getObject("created_at", OffsetDateTime.class);
            OffsetDateTime processedAt = rs.getObject("processed_at", OffsetDateTime.class);

            return OutboxEvent.builder()
                .id(rs.getLong("id"))
                .aggregateType(rs.getString("aggregate_type"))
                .aggregateId(rs.getString("aggregate_id"))
                .eventType(rs.getString("event_type"))
                .eventData(rs.getString("event_data"))
                .createdAt(createdAt == null ? null : createdAt.toZonedDateTime())
                .processedAt(processedAt == null ? null : processedAt.toZonedDateTime())
                .status(rs.getString("status"))
                .retryCount(rs.getInt("retry_count"))
                .lastError(rs.getString("last_error"))
                .correlationId(rs.getString("correlation_id"))
                .causationId(rs.getString("causation_id"))
                .build();
        }
    }
    
    /**
     * Statistics for monitoring outbox events
     */
    @lombok.Data
    @lombok.Builder
    public static class OutboxStats {
        private long total;
        private long pending;
        private long processing;
        private long processed;
        private long failed;
        private long createdLastHour;
        private long processedLastHour;
    }
}
