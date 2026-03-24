package com.corebank.corebank_api.reporting;

import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;

/**
 * Minimal read-model projector placeholder.
 *
 * <p>This keeps Kafka consumption wiring compilable while reporting projector
 * persistence is being aligned with current repository/domain contracts.</p>
 */
@Service
@Slf4j
public class ReadModelProjector {

	@KafkaListener(
			topics = { "account-events", "transfer-events", "payment-events", "deposit-events", "ledger-events" },
			groupId = "read-model-projector")
	public void project(@Payload String eventData, Acknowledgment acknowledgment) {
		log.debug("Read-model projector received event payload (length={} chars)", eventData == null ? 0 : eventData.length());
		if (acknowledgment != null) {
			acknowledgment.acknowledge();
		}
	}
}