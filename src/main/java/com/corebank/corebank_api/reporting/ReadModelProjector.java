package com.corebank.corebank_api.reporting;

import com.corebank.corebank_api.integration.KafkaConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Minimal non-authoritative read-model projector fed by event envelopes.
 */
@Service
@Slf4j
public class ReadModelProjector {

	private static final String SUPPORTED_SCHEMA_VERSION = "v1";
	private static final String REPLAY_SOURCE_TOPIC = "outbox-replay";

	private final JdbcTemplate jdbcTemplate;
	private final ObjectMapper objectMapper;

	@Autowired
	public ReadModelProjector(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
		this.jdbcTemplate = jdbcTemplate;
		this.objectMapper = objectMapper.copy().findAndRegisterModules();
	}

	@KafkaListener(
			topics = {
					KafkaConfig.TOPIC_ACCOUNT_EVENTS,
					KafkaConfig.TOPIC_TRANSFER_EVENTS,
					KafkaConfig.TOPIC_PAYMENT_EVENTS,
					KafkaConfig.TOPIC_DEPOSIT_EVENTS,
					KafkaConfig.TOPIC_LOAN_EVENTS,
					KafkaConfig.TOPIC_LEDGER_EVENTS },
			groupId = "read-model-projector")
	public void project(
			@Payload String eventData,
			@Header(KafkaHeaders.RECEIVED_TOPIC) String sourceTopic,
			Acknowledgment acknowledgment) {
		projectEvent(sourceTopic, eventData);
		if (acknowledgment != null) {
			acknowledgment.acknowledge();
		}
	}

	@Transactional
	public int projectEvent(String sourceTopic, String eventData) {
		ProjectedEnvelope envelope = parseEnvelope(sourceTopic, eventData);
		if (envelope == null) {
			return 0;
		}

		int inserted = jdbcTemplate.update(
				"""
				INSERT INTO read_model_event_feed (
				    event_id,
				    source_topic,
				    aggregate_type,
				    aggregate_id,
				    event_type,
				    occurred_at,
				    schema_version,
				    correlation_id,
				    request_id,
				    actor,
				    payload
				) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
				ON CONFLICT (event_id) DO NOTHING
				""",
				envelope.eventId(),
				sourceTopic,
				envelope.aggregateType(),
				envelope.aggregateId(),
				envelope.eventType(),
				java.sql.Timestamp.from(envelope.occurredAt()),
				envelope.schemaVersion(),
				envelope.correlationId(),
				envelope.requestId(),
				envelope.actor(),
				envelope.payloadJson());

		if (inserted == 1) {
			updateAggregateActivitySummary(envelope);
		}

		return inserted;
	}

	@Transactional
	public int projectReplayedEvent(String eventData) {
		return projectEvent(REPLAY_SOURCE_TOPIC, eventData);
	}

	private void updateAggregateActivitySummary(ProjectedEnvelope envelope) {
		jdbcTemplate.update(
				"""
				INSERT INTO read_model_aggregate_activity (
				    aggregate_type,
				    aggregate_id,
				    latest_event_id,
				    latest_event_type,
				    latest_occurred_at,
				    last_actor,
				    last_correlation_id,
				    event_count,
				    updated_at
				) VALUES (?, ?, ?, ?, ?, ?, ?, 1, CURRENT_TIMESTAMP)
				ON CONFLICT (aggregate_type, aggregate_id) DO UPDATE
				SET latest_event_id = CASE
				        WHEN EXCLUDED.latest_occurred_at >= read_model_aggregate_activity.latest_occurred_at
				            THEN EXCLUDED.latest_event_id
				        ELSE read_model_aggregate_activity.latest_event_id
				    END,
				    latest_event_type = CASE
				        WHEN EXCLUDED.latest_occurred_at >= read_model_aggregate_activity.latest_occurred_at
				            THEN EXCLUDED.latest_event_type
				        ELSE read_model_aggregate_activity.latest_event_type
				    END,
				    latest_occurred_at = GREATEST(
				        read_model_aggregate_activity.latest_occurred_at,
				        EXCLUDED.latest_occurred_at),
				    last_actor = CASE
				        WHEN EXCLUDED.latest_occurred_at >= read_model_aggregate_activity.latest_occurred_at
				            THEN EXCLUDED.last_actor
				        ELSE read_model_aggregate_activity.last_actor
				    END,
				    last_correlation_id = CASE
				        WHEN EXCLUDED.latest_occurred_at >= read_model_aggregate_activity.latest_occurred_at
				            THEN EXCLUDED.last_correlation_id
				        ELSE read_model_aggregate_activity.last_correlation_id
				    END,
				    event_count = read_model_aggregate_activity.event_count + 1,
				    updated_at = CURRENT_TIMESTAMP
				""",
				envelope.aggregateType(),
				envelope.aggregateId(),
				envelope.eventId(),
				envelope.eventType(),
				java.sql.Timestamp.from(envelope.occurredAt()),
				envelope.actor(),
				envelope.correlationId());
	}

	private ProjectedEnvelope parseEnvelope(String sourceTopic, String eventData) {
		try {
			JsonNode envelope = objectMapper.readTree(eventData);
			if (!envelope.hasNonNull("eventId")
					|| !envelope.hasNonNull("aggregateType")
					|| !envelope.hasNonNull("aggregateId")
					|| !envelope.hasNonNull("eventType")
					|| !envelope.hasNonNull("occurredAt")
					|| !envelope.hasNonNull("schemaVersion")
					|| !envelope.has("payload")) {
				log.debug("Skipping non-envelope event for topic {}", sourceTopic);
				return null;
			}

			String schemaVersion = envelope.get("schemaVersion").asText();
			if (!SUPPORTED_SCHEMA_VERSION.equals(schemaVersion)) {
				log.warn("Skipping unsupported envelope schema {} from topic {}", schemaVersion, sourceTopic);
				return null;
			}

			return new ProjectedEnvelope(
					java.util.UUID.fromString(envelope.get("eventId").asText()),
					envelope.get("aggregateType").asText(),
					envelope.get("aggregateId").asText(),
					envelope.get("eventType").asText(),
					java.time.Instant.parse(envelope.get("occurredAt").asText()),
					schemaVersion,
					envelope.path("correlationId").isNull() ? null : envelope.path("correlationId").asText(null),
					envelope.path("requestId").isNull() ? null : envelope.path("requestId").asText(null),
					envelope.path("actor").isNull() ? null : envelope.path("actor").asText(null),
					objectMapper.writeValueAsString(envelope.get("payload")));
		} catch (Exception ex) {
			log.warn("Skipping unreadable projector payload from topic {}", sourceTopic, ex);
			return null;
		}
	}

	private record ProjectedEnvelope(
			java.util.UUID eventId,
			String aggregateType,
			String aggregateId,
			String eventType,
			java.time.Instant occurredAt,
			String schemaVersion,
			String correlationId,
			String requestId,
			String actor,
			String payloadJson) {
	}
}
