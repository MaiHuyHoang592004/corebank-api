package com.corebank.corebank_api.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class OutboxEventPublisherTopicRoutingTest {

	@Test
	void determineTopic_routesLoanEventsToLoanTopic() {
		OutboxEventPublisher publisher = new OutboxEventPublisher(null, null);

		assertEquals(
				KafkaConfig.TOPIC_LOAN_EVENTS,
				publisher.determineTopic("LOAN_CONTRACT", "LOAN_DISBURSED"));
	}
}
