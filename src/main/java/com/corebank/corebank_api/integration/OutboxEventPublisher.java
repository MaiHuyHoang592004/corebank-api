package com.corebank.corebank_api.integration;

import lombok.extern.slf4j.Slf4j;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.Locale;

/**
 * Outbox event publisher service.
 * Processes pending outbox events and publishes them to Kafka.
 * Ensures reliable event delivery with retry logic and monitoring.
 */
@Service
@Slf4j
public class OutboxEventPublisher {
    
    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    
    // Configuration
    private static final int BATCH_SIZE = 10;
    private static final int MAX_RETRIES = 3;
    private static final int RETRY_BACKOFF_SECONDS = 30;
    private static final int RECLAIM_TIMEOUT_SECONDS = 300;
    private static final long PROCESSING_INTERVAL_MS = 5000; // 5 seconds
    private static final long PUBLISH_TIMEOUT_SECONDS = 10;
    private static final ObjectMapper EVENT_DATA_MAPPER = new ObjectMapper().findAndRegisterModules();
    
    @Autowired
    public OutboxEventPublisher(OutboxEventRepository outboxEventRepository, 
                               KafkaTemplate<String, Object> kafkaTemplate) {
        this.outboxEventRepository = outboxEventRepository;
        this.kafkaTemplate = kafkaTemplate;
    }
    
    /**
     * Scheduled task to process pending outbox events
     */
    @Scheduled(fixedDelay = PROCESSING_INTERVAL_MS)
    @Transactional
    public void processPendingEvents() {
        try {
            List<OutboxEvent> pendingEvents = outboxEventRepository.getPendingEvents(
                BATCH_SIZE,
                MAX_RETRIES,
                RETRY_BACKOFF_SECONDS,
                RECLAIM_TIMEOUT_SECONDS
            );
            
            if (pendingEvents.isEmpty()) {
                return;
            }
            
            log.info("Processing {} pending outbox events", pendingEvents.size());
            
            for (OutboxEvent event : pendingEvents) {
                processEvent(event);
            }
            
        } catch (Exception e) {
            log.error("Error processing outbox events", e);
        }
    }
    
    /**
     * Process a single outbox event
     */
    private void processEvent(OutboxEvent event) {
        try {
            // Determine topic based on event type
            String topic = determineTopic(event.getAggregateType(), event.getEventType());
            
            // Publish to Kafka
            CompletableFuture<SendResult<String, Object>> future = kafkaTemplate.send(
                topic, 
                event.getAggregateId(), 
                parseEventData(event.getEventData())
            );

            SendResult<String, Object> result = future.get(PUBLISH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            handlePublishSuccess(event, result);
            
        } catch (Exception e) {
            handlePublishFailure(event, e);
        }
    }
    
    /**
     * Handle successful event publication
     */
    private void handlePublishSuccess(OutboxEvent event, SendResult<String, Object> result) {
        try {
            boolean marked = outboxEventRepository.markAsProcessed(event.getId(), OutboxEvent.STATUS_PROCESSED);
            if (marked) {
                log.debug("Successfully published event {} to topic {}", 
                         event.getId(), result.getProducerRecord().topic());
            } else {
                log.warn("Failed to mark event {} as processed", event.getId());
            }
        } catch (Exception e) {
            log.error("Error marking event {} as processed", event.getId(), e);
        }
    }
    
    /**
     * Handle failed event publication
     */
    private void handlePublishFailure(OutboxEvent event, Throwable throwable) {
        try {
            String errorMessage = "Publish failed: " + throwable.getMessage();
            boolean marked = outboxEventRepository.markAsFailed(event.getId(), errorMessage);
            
            if (marked) {
                int attempt = (event.getRetryCount() == null ? 0 : event.getRetryCount()) + 1;
                log.warn("Failed to publish event {} (retry {}): {}", 
                        event.getId(), attempt, errorMessage);
            } else {
                log.warn("Failed to mark event {} as failed", event.getId());
            }
        } catch (Exception e) {
            log.error("Error handling publish failure for event {}", event.getId(), e);
        }
    }
    
    /**
     * Determine Kafka topic based on event type
     */
    String determineTopic(String aggregateType, String eventType) {
        String aggregate = aggregateType == null ? "" : aggregateType.trim().toUpperCase(Locale.ROOT);
        String event = eventType == null ? "" : eventType.trim().toUpperCase(Locale.ROOT);

        if (aggregate.contains("ACCOUNT") || event.contains("ACCOUNT")) {
            return KafkaConfig.TOPIC_ACCOUNT_EVENTS;
        } else if (aggregate.contains("TRANSFER") || event.contains("TRANSFER")) {
            return KafkaConfig.TOPIC_TRANSFER_EVENTS;
        } else if (aggregate.contains("PAYMENT") || aggregate.contains("HOLD") || event.contains("PAYMENT") || event.contains("HOLD")) {
            return KafkaConfig.TOPIC_PAYMENT_EVENTS;
        } else if (aggregate.contains("DEPOSIT") || event.contains("DEPOSIT")) {
            return KafkaConfig.TOPIC_DEPOSIT_EVENTS;
        } else if (aggregate.contains("LOAN") || event.contains("LOAN")) {
            return KafkaConfig.TOPIC_LOAN_EVENTS;
        } else if (aggregate.contains("LEDGER") || event.contains("LEDGER")) {
            return KafkaConfig.TOPIC_LEDGER_EVENTS;
        } else {
            // Default to ledger-events instead of dropping to unknown topic.
            return KafkaConfig.TOPIC_LEDGER_EVENTS;
        }
    }
    
    /**
     * Parse event data from JSON string to Object
     */
    private Object parseEventData(String eventData) {
        if (eventData == null) {
            return null;
        }

        try {
            JsonNode jsonNode = EVENT_DATA_MAPPER.readTree(eventData);
            return jsonNode;
        } catch (Exception ex) {
            log.debug("Falling back to raw event_data string for non-JSON payload");
            return eventData;
        }
    }
    
    /**
     * Get outbox statistics for monitoring
     */
    public OutboxEventRepository.OutboxStats getStats() {
        return outboxEventRepository.getStats();
    }
    
    /**
     * Manual cleanup of old processed events
     */
    public int cleanupProcessedEvents(int daysToKeep) {
        return outboxEventRepository.cleanupProcessedEvents(daysToKeep);
    }
    
    /**
     * Health check for outbox processing
     */
    public boolean isHealthy() {
        try {
            OutboxEventRepository.OutboxStats stats = getStats();
            // Consider healthy if we have reasonable processing rates
            return stats.getPending() < 1000; // Less than 1000 pending events
        } catch (Exception e) {
            log.error("Error checking outbox health", e);
            return false;
        }
    }
}
