package com.corebank.corebank_api.integration;

import lombok.extern.slf4j.Slf4j;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import org.springframework.beans.factory.annotation.Autowired;
import org.apache.kafka.common.errors.RetriableException;
import org.apache.kafka.common.errors.SerializationException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
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
            PublishFailureType failureType = classifyFailure(throwable);
            String errorMessage = "Publish failed: " + describeFailure(throwable);

            if (failureType == PublishFailureType.NON_TRANSIENT) {
                String terminalErrorMessage = "Publish failed (non-transient): " + describeFailure(throwable);
                boolean terminalMarked = outboxEventRepository.markAsTerminalFailed(
                        event.getId(),
                        terminalErrorMessage,
                        MAX_RETRIES);
                if (!terminalMarked) {
                    log.warn("Failed to mark event {} as terminal failed", event.getId());
                    return;
                }

                log.error("Non-transient publish failure for event {}. Moving directly to dead-letter: {}",
                        event.getId(),
                        terminalErrorMessage);
                boolean deadLettered = outboxEventRepository.addToDeadLetter(event.getId());
                if (deadLettered) {
                    log.error("Outbox event {} moved to dead-letter immediately for non-transient failure", event.getId());
                }
                return;
            }

            boolean marked = outboxEventRepository.markAsFailed(event.getId(), errorMessage);
            if (!marked) {
                log.warn("Failed to mark event {} as failed", event.getId());
                return;
            }

            int attempt = currentRetryCount(event.getId());
            log.warn("Failed to publish event {} (retry {}): {}",
                    event.getId(),
                    attempt,
                    errorMessage);

            if (attempt >= MAX_RETRIES) {
                boolean deadLettered = outboxEventRepository.addToDeadLetter(event.getId());
                if (deadLettered) {
                    log.error("Outbox event {} moved to dead-letter after {} retries", event.getId(), attempt);
                }
            }
        } catch (Exception e) {
            log.error("Error handling publish failure for event {}", event.getId(), e);
        }
    }

    private int currentRetryCount(Long eventId) {
        Optional<OutboxEvent> latest = outboxEventRepository.findById(eventId);
        if (latest.isPresent() && latest.get().getRetryCount() != null) {
            return latest.get().getRetryCount();
        }
        return 0;
    }

    private PublishFailureType classifyFailure(Throwable throwable) {
        Set<Throwable> visited = new HashSet<>();
        Throwable current = throwable;

        while (current != null && visited.add(current)) {
            if (isNonTransient(current)) {
                return PublishFailureType.NON_TRANSIENT;
            }
            if (isTransient(current)) {
                return PublishFailureType.TRANSIENT;
            }
            current = current.getCause();
        }

        return PublishFailureType.NON_TRANSIENT;
    }

    private boolean isTransient(Throwable throwable) {
        return throwable instanceof RetriableException
                || throwable instanceof TimeoutException
                || throwable instanceof SocketTimeoutException
                || throwable instanceof ConnectException
                || throwable instanceof IOException;
    }

    private boolean isNonTransient(Throwable throwable) {
        return throwable instanceof SerializationException
                || throwable instanceof IllegalArgumentException
                || throwable instanceof ClassCastException;
    }

    private String describeFailure(Throwable throwable) {
        if (throwable == null) {
            return "unknown error";
        }
        String type = throwable.getClass().getSimpleName();
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            return type;
        }
        return type + ": " + message;
    }

    private enum PublishFailureType {
        TRANSIENT,
        NON_TRANSIENT
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
