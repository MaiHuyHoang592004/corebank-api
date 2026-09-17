package com.corebank.corebank_api.ops.audit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.corebank.corebank_api.TestcontainersConfiguration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@link AuditService} had no test despite being the module 14-source-of-truth-map.md
 * names authoritative for "who did what". Covers the fields it claims to capture
 * and, specifically, that the SHA-256 hash chain actually links row to row —
 * that property is invisible to a test that only checks each row in isolation.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class AuditServiceTest {

	@Autowired
	private AuditService auditService;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void appendEvent_persistsEveryDocumentedField() {
		UUID correlationId = UUID.randomUUID();
		UUID requestId = UUID.randomUUID();
		UUID sessionId = UUID.randomUUID();
		String resourceId = UUID.randomUUID().toString();

		auditService.appendEvent(new AuditService.AuditCommand(
				"audit-test-actor",
				"AUDIT_TEST_ACTION",
				"AUDIT_TEST_RESOURCE",
				resourceId,
				correlationId,
				requestId,
				sessionId,
				"trace-audit-test",
				"{\"balance\":\"100.00\"}",
				"{\"balance\":\"200.00\"}"));

		Map<String, Object> row = jdbcTemplate.queryForMap(
				"""
				SELECT actor, action, resource_type, resource_id, correlation_id, request_id,
				       session_id, trace_id, before_state_json::text AS before_json,
				       after_state_json::text AS after_json, row_hash
				FROM audit_events
				WHERE resource_type = 'AUDIT_TEST_RESOURCE' AND resource_id = ?
				ORDER BY audit_id DESC
				LIMIT 1
				""",
				resourceId);

		assertEquals("audit-test-actor", row.get("actor"));
		assertEquals("AUDIT_TEST_ACTION", row.get("action"));
		assertEquals("AUDIT_TEST_RESOURCE", row.get("resource_type"));
		assertEquals(resourceId, row.get("resource_id"));
		assertEquals(correlationId, row.get("correlation_id"));
		assertEquals(requestId, row.get("request_id"));
		assertEquals(sessionId, row.get("session_id"));
		assertEquals("trace-audit-test", row.get("trace_id"));
		assertTrue(((String) row.get("before_json")).contains("100.00"));
		assertTrue(((String) row.get("after_json")).contains("200.00"));
		assertNotNull(row.get("row_hash"));
	}

	@Test
	void consecutiveEvents_chainByRowHash() {
		String resourceIdA = UUID.randomUUID().toString();
		auditService.appendEvent(new AuditService.AuditCommand(
				"chain-actor", "CHAIN_EVENT_A", "CHAIN_TEST", resourceIdA,
				UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "trace-a", null, null));

		byte[] firstRowHash = jdbcTemplate.queryForObject(
				"SELECT row_hash FROM audit_events ORDER BY created_at DESC, audit_id DESC LIMIT 1",
				byte[].class);

		String resourceIdB = UUID.randomUUID().toString();
		auditService.appendEvent(new AuditService.AuditCommand(
				"chain-actor", "CHAIN_EVENT_B", "CHAIN_TEST", resourceIdB,
				UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "trace-b", null, null));

		Map<String, Object> second = jdbcTemplate.queryForMap(
				"SELECT prev_row_hash, row_hash FROM audit_events ORDER BY created_at DESC, audit_id DESC LIMIT 1");

		assertArrayEquals(firstRowHash, (byte[]) second.get("prev_row_hash"),
				"the second event's prev_row_hash must equal the first event's row_hash");
		assertFalse(
				java.util.Arrays.equals(firstRowHash, (byte[]) second.get("row_hash")),
				"consecutive rows must not collide to the same hash");
	}
}
