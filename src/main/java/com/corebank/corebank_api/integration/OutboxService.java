package com.corebank.corebank_api.integration;

import com.corebank.corebank_api.common.CoreBankException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class OutboxService {

	private final JdbcTemplate jdbcTemplate;

	public OutboxService(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public void appendMessage(String aggregateType, String aggregateId, String eventType, String payloadJson) {
		jdbcTemplate.update(
				"""
				INSERT INTO outbox_messages (
				    aggregate_type,
				    aggregate_id,
				    event_type,
				    payload_json,
				    status
				) VALUES (?, ?, ?, ?::jsonb, 'PENDING')
				""",
				aggregateType,
				aggregateId,
				eventType,
				payloadJson);
	}
}