package com.corebank.corebank_api.integration;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka configuration for outbox pattern integration.
 * Configures topics and producer settings for financial event publishing.
 */
@Configuration
public class KafkaConfig {
    
    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;
    
    @Value("${spring.kafka.producer.acks:all}")
    private String acks;
    
    @Value("${spring.kafka.producer.retries:3}")
    private Integer retries;
    
    @Value("${spring.kafka.producer.batch-size:16384}")
    private Integer batchSize;
    
    @Value("${spring.kafka.producer.linger-ms:5}")
    private Integer lingerMs;
    
    @Value("${spring.kafka.producer.buffer-memory:33554432}")
    private Integer bufferMemory;
    
    // Topic names
    public static final String TOPIC_ACCOUNT_EVENTS = "account-events";
    public static final String TOPIC_TRANSFER_EVENTS = "transfer-events";
    public static final String TOPIC_PAYMENT_EVENTS = "payment-events";
    public static final String TOPIC_DEPOSIT_EVENTS = "deposit-events";
    public static final String TOPIC_LEDGER_EVENTS = "ledger-events";
    
    /**
     * Configure producer factory for JSON serialization
     */
    @Bean
    public ProducerFactory<String, Object> producerFactory() {
        Map<String, Object> configProps = new HashMap<>();
        configProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        configProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        configProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        configProps.put(ProducerConfig.ACKS_CONFIG, acks);
        configProps.put(ProducerConfig.RETRIES_CONFIG, retries);
        configProps.put(ProducerConfig.BATCH_SIZE_CONFIG, batchSize);
        configProps.put(ProducerConfig.LINGER_MS_CONFIG, lingerMs);
        configProps.put(ProducerConfig.BUFFER_MEMORY_CONFIG, bufferMemory);
        
        // Financial system specific settings
        configProps.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        configProps.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 1);
        configProps.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");
        
        return new DefaultKafkaProducerFactory<>(configProps);
    }
    
    /**
     * Configure Kafka template for sending events
     */
    @Bean
    public KafkaTemplate<String, Object> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }
    
    /**
     * Create account events topic
     */
    @Bean
    public NewTopic accountEventsTopic() {
        return new NewTopic(TOPIC_ACCOUNT_EVENTS, 3, (short) 1);
    }
    
    /**
     * Create transfer events topic
     */
    @Bean
    public NewTopic transferEventsTopic() {
        return new NewTopic(TOPIC_TRANSFER_EVENTS, 3, (short) 1);
    }
    
    /**
     * Create payment events topic
     */
    @Bean
    public NewTopic paymentEventsTopic() {
        return new NewTopic(TOPIC_PAYMENT_EVENTS, 3, (short) 1);
    }
    
    /**
     * Create deposit events topic
     */
    @Bean
    public NewTopic depositEventsTopic() {
        return new NewTopic(TOPIC_DEPOSIT_EVENTS, 3, (short) 1);
    }
    
    /**
     * Create ledger events topic
     */
    @Bean
    public NewTopic ledgerEventsTopic() {
        return new NewTopic(TOPIC_LEDGER_EVENTS, 3, (short) 1);
    }
}