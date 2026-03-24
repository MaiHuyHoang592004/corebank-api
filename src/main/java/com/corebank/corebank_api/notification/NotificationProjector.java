package com.corebank.corebank_api.notification;

import com.corebank.corebank_api.integration.KafkaConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Minimal non-authoritative notification inbox projector fed by event envelopes.
 */
@Service
@Slf4j
public class NotificationProjector {

	private static final String SUPPORTED_SCHEMA_VERSION = "v1";

	private final JdbcTemplate jdbcTemplate;
	private final ObjectMapper objectMapper;

	public NotificationProjector(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
		this.jdbcTemplate = jdbcTemplate;
		this.objectMapper = objectMapper.copy().findAndRegisterModules();
	}

	@KafkaListener(
			topics = {
					KafkaConfig.TOPIC_ACCOUNT_EVENTS,
					KafkaConfig.TOPIC_TRANSFER_EVENTS,
					KafkaConfig.TOPIC_PAYMENT_EVENTS,
					KafkaConfig.TOPIC_DEPOSIT_EVENTS,
					KafkaConfig.TOPIC_LOAN_EVENTS },
			groupId = "notification-projector")
	public void project(
			@Payload String eventData,
			@Header(KafkaHeaders.RECEIVED_TOPIC) String sourceTopic) {
		projectEvent(sourceTopic, eventData);
	}

	@Transactional
	public int projectEvent(String sourceTopic, String eventData) {
		NotificationEnvelope envelope = parseEnvelope(sourceTopic, eventData);
		if (envelope == null) {
			return 0;
		}

		return jdbcTemplate.update(
				"""
				INSERT INTO read_model_notification_inbox (
				    event_id,
				    source_topic,
				    aggregate_type,
				    aggregate_id,
				    event_type,
				    occurred_at,
				    correlation_id,
				    request_id,
				    actor,
				    payload
				) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
				ON CONFLICT (event_id) DO NOTHING
				""",
				envelope.eventId(),
				sourceTopic,
				envelope.aggregateType(),
				envelope.aggregateId(),
				envelope.eventType(),
				java.sql.Timestamp.from(envelope.occurredAt()),
				envelope.correlationId(),
				envelope.requestId(),
				envelope.actor(),
				envelope.payloadJson());
	}

	private NotificationEnvelope parseEnvelope(String sourceTopic, String eventData) {
		try {
			JsonNode envelope = objectMapper.readTree(eventData);
			if (!envelope.hasNonNull("eventId")
					|| !envelope.hasNonNull("aggregateType")
					|| !envelope.hasNonNull("aggregateId")
					|| !envelope.hasNonNull("eventType")
					|| !envelope.hasNonNull("occurredAt")
					|| !envelope.hasNonNull("schemaVersion")
					|| !envelope.has("payload")) {
				log.debug("Skipping non-envelope notification payload for topic {}", sourceTopic);
				return null;
			}

			String schemaVersion = envelope.get("schemaVersion").asText();
			if (!SUPPORTED_SCHEMA_VERSION.equals(schemaVersion)) {
				log.warn("Skipping unsupported notification schema {} from topic {}", schemaVersion, sourceTopic);
				return null;
			}

			return new NotificationEnvelope(
					UUID.fromString(envelope.get("eventId").asText()),
					envelope.get("aggregateType").asText(),
					envelope.get("aggregateId").asText(),
					envelope.get("eventType").asText(),
					Instant.parse(envelope.get("occurredAt").asText()),
					envelope.path("correlationId").isNull() ? null : envelope.path("correlationId").asText(null),
					envelope.path("requestId").isNull() ? null : envelope.path("requestId").asText(null),
					envelope.path("actor").isNull() ? null : envelope.path("actor").asText(null),
					objectMapper.writeValueAsString(envelope.get("payload")));
		} catch (Exception ex) {
			log.warn("Skipping unreadable notification payload from topic {}", sourceTopic, ex);
			return null;
		}
	}

	private record NotificationEnvelope(
			UUID eventId,
			String aggregateType,
			String aggregateId,
			String eventType,
			Instant occurredAt,
			String correlationId,
			String requestId,
			String actor,
			String payloadJson) {
	}
}
