package com.corebank.corebank_api.integration;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.ZonedDateTime;

/**
 * Outbox event entity for reliable event publishing.
 * Follows the outbox pattern to ensure events are published atomically
 * with database transactions.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OutboxEvent {
    
    private Long id;
    
    @JsonProperty("aggregate_type")
    private String aggregateType;
    
    @JsonProperty("aggregate_id")
    private String aggregateId;
    
    @JsonProperty("event_type")
    private String eventType;
    
    @JsonProperty("event_data")
    private String eventData;
    
    @JsonProperty("created_at")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
    private ZonedDateTime createdAt;
    
    @JsonProperty("processed_at")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
    private ZonedDateTime processedAt;
    
    private String status;
    
    @JsonProperty("retry_count")
    private Integer retryCount;
    
    @JsonProperty("last_error")
    private String lastError;
    
    @JsonProperty("correlation_id")
    private String correlationId;
    
    @JsonProperty("causation_id")
    private String causationId;
    
    // Status constants
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_PROCESSING = "PROCESSING";
    public static final String STATUS_PROCESSED = "PROCESSED";
    public static final String STATUS_FAILED = "FAILED";
    
    // Business methods
    public boolean isPending() {
        return STATUS_PENDING.equals(status);
    }
    
    public boolean isProcessing() {
        return STATUS_PROCESSING.equals(status);
    }
    
    public boolean isProcessed() {
        return STATUS_PROCESSED.equals(status);
    }
    
    public boolean isFailed() {
        return STATUS_FAILED.equals(status);
    }
    
    public boolean canBeProcessed() {
        return isPending() || (isFailed() && retryCount < 3);
    }
    
    public void markAsProcessing() {
        this.status = STATUS_PROCESSING;
    }
    
    public void markAsProcessed() {
        this.status = STATUS_PROCESSED;
        this.processedAt = ZonedDateTime.now();
    }
    
    public void markAsFailed(String error) {
        this.status = STATUS_FAILED;
        this.processedAt = ZonedDateTime.now();
        this.retryCount = (this.retryCount == null) ? 1 : this.retryCount + 1;
        this.lastError = error;
    }
}