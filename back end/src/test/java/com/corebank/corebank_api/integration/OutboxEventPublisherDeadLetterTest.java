package com.corebank.corebank_api.integration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

@ExtendWith(MockitoExtension.class)
class OutboxEventPublisherDeadLetterTest {

	@Mock
	private OutboxEventRepository outboxEventRepository;

	@Mock
	private KafkaTemplate<String, Object> kafkaTemplate;

	@InjectMocks
	private OutboxEventPublisher outboxEventPublisher;

	@Test
	void processPendingEvents_movesExhaustedFailureToDeadLetter() {
		OutboxEvent claimed = OutboxEvent.builder()
				.id(10L)
				.aggregateType("LOAN_CONTRACT")
				.aggregateId("loan-001")
				.eventType("LOAN_DISBURSEMENT")
				.eventData("{\"amountMinor\":100000}")
				.retryCount(2)
				.status(OutboxEvent.STATUS_PROCESSING)
				.build();
		when(outboxEventRepository.getPendingEvents(anyInt(), anyInt(), anyInt(), anyInt()))
				.thenReturn(List.of(claimed));
		when(outboxEventRepository.markAsFailed(eq(10L), contains("broker unavailable")))
				.thenReturn(true);
		when(outboxEventRepository.findById(10L))
				.thenReturn(Optional.of(OutboxEvent.builder()
						.id(10L)
						.retryCount(3)
						.status(OutboxEvent.STATUS_FAILED)
						.build()));
		when(outboxEventRepository.addToDeadLetter(10L)).thenReturn(true);

		CompletableFuture<SendResult<String, Object>> failedPublish = new CompletableFuture<>();
		failedPublish.completeExceptionally(new TimeoutException("broker unavailable"));
		when(kafkaTemplate.send(anyString(), anyString(), any())).thenReturn(failedPublish);

		outboxEventPublisher.processPendingEvents();

		verify(outboxEventRepository).addToDeadLetter(10L);
	}

	@Test
	void processPendingEvents_doesNotDeadLetterBeforeRetryExhausted() {
		OutboxEvent claimed = OutboxEvent.builder()
				.id(11L)
				.aggregateType("PAYMENT_ORDER")
				.aggregateId("payment-001")
				.eventType("PAYMENT_CAPTURE")
				.eventData("{\"amountMinor\":50000}")
				.retryCount(0)
				.status(OutboxEvent.STATUS_PROCESSING)
				.build();
		when(outboxEventRepository.getPendingEvents(anyInt(), anyInt(), anyInt(), anyInt()))
				.thenReturn(List.of(claimed));
		when(outboxEventRepository.markAsFailed(eq(11L), contains("temporary timeout")))
				.thenReturn(true);
		when(outboxEventRepository.findById(11L))
				.thenReturn(Optional.of(OutboxEvent.builder()
						.id(11L)
						.retryCount(1)
						.status(OutboxEvent.STATUS_FAILED)
						.build()));

		CompletableFuture<SendResult<String, Object>> failedPublish = new CompletableFuture<>();
		failedPublish.completeExceptionally(new TimeoutException("temporary timeout"));
		when(kafkaTemplate.send(anyString(), anyString(), any())).thenReturn(failedPublish);

		outboxEventPublisher.processPendingEvents();

		verify(outboxEventRepository, never()).addToDeadLetter(11L);
	}

	@Test
	void processPendingEvents_movesNonTransientFailureToDeadLetterImmediately() {
		OutboxEvent claimed = OutboxEvent.builder()
				.id(12L)
				.aggregateType("TRANSFER")
				.aggregateId("transfer-001")
				.eventType("TRANSFER_COMPLETED")
				.eventData("{\"amountMinor\":\"invalid\"}")
				.retryCount(0)
				.status(OutboxEvent.STATUS_PROCESSING)
				.build();
		when(outboxEventRepository.getPendingEvents(anyInt(), anyInt(), anyInt(), anyInt()))
				.thenReturn(List.of(claimed));
		when(outboxEventRepository.markAsTerminalFailed(eq(12L), contains("Publish failed (non-transient)"), eq(3)))
				.thenReturn(true);
		when(outboxEventRepository.addToDeadLetter(12L)).thenReturn(true);

		CompletableFuture<SendResult<String, Object>> failedPublish = new CompletableFuture<>();
		failedPublish.completeExceptionally(new IllegalArgumentException("invalid payload"));
		when(kafkaTemplate.send(anyString(), anyString(), any())).thenReturn(failedPublish);

		outboxEventPublisher.processPendingEvents();

		verify(outboxEventRepository).markAsTerminalFailed(eq(12L), contains("Publish failed (non-transient)"), eq(3));
		verify(outboxEventRepository, never()).markAsFailed(eq(12L), anyString());
		verify(outboxEventRepository).addToDeadLetter(12L);
	}
}
