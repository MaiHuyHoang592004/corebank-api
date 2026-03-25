package com.corebank.corebank_api.integration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Outbox service for reliable event publishing.
 * Integrates with the outbox pattern to ensure events are published
 * atomically with database transactions.
 */
@Service
@Slf4j
public class OutboxService {

    private static final String OUTBOX_SCHEMA_VERSION = "v1";

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    @Autowired
    public OutboxService(OutboxEventRepository outboxEventRepository, ObjectMapper objectMapper) {
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Append a message to the outbox for asynchronous processing
     */
    @Transactional
    public void appendMessage(String aggregateType, String aggregateId, String eventType, Object payload) {
        appendMessage(aggregateType, aggregateId, eventType, payload, OutboxMetadata.empty());
    }

    /**
     * Append a message to the outbox with metadata for traceability.
     */
    @Transactional
    public void appendMessage(String aggregateType, String aggregateId, String eventType, Object payload, OutboxMetadata metadata) {
        try {
            OutboxMetadata effectiveMetadata = metadata == null ? OutboxMetadata.empty() : metadata;
            String eventData = objectMapper.writeValueAsString(buildEnvelope(
                    aggregateType,
                    aggregateId,
                    eventType,
                    payload,
                    effectiveMetadata));
            
            OutboxEvent event = OutboxEvent.builder()
                .aggregateType(aggregateType)
                .aggregateId(aggregateId)
                .eventType(eventType)
                .eventData(eventData)
                .correlationId(effectiveMetadata.correlationId())
                .causationId(effectiveMetadata.causationId())
                .status(OutboxEvent.STATUS_PENDING)
                .retryCount(0)
                .build();
            
            Long eventId = outboxEventRepository.insert(event);
            log.debug("Created outbox event {} for {} {} {}", 
                     eventId, aggregateType, aggregateId, eventType);
                     
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize payload for outbox event", e);
            throw new RuntimeException("Failed to serialize event payload", e);
        }
    }

    /**
     * Append a message with correlation and causation IDs
     */
    @Transactional
    public void appendMessage(String aggregateType, String aggregateId, String eventType, 
                            Object payload, String correlationId, String causationId) {
        appendMessage(
                aggregateType,
                aggregateId,
                eventType,
                payload,
                OutboxMetadata.legacy(correlationId, causationId));
    }

    private OutboxEnvelope buildEnvelope(
            String aggregateType,
            String aggregateId,
            String eventType,
            Object payload,
            OutboxMetadata metadata) {
        return new OutboxEnvelope(
                UUID.randomUUID(),
                aggregateType,
                aggregateId,
                eventType,
                Instant.now(),
                OUTBOX_SCHEMA_VERSION,
                metadata.correlationId(),
                metadata.requestId(),
                metadata.actor(),
                payload);
    }

    /**
     * Get outbox statistics for monitoring
     */
    public OutboxEventRepository.OutboxStats getStats() {
        return outboxEventRepository.getStats();
    }

    /**
     * Health check for outbox service
     */
    public boolean isHealthy() {
        try {
            OutboxEventRepository.OutboxStats stats = getStats();
            return stats.getPending() < 1000; // Less than 1000 pending events
        } catch (Exception e) {
            log.error("Error checking outbox health", e);
            return false;
        }
    }
}
