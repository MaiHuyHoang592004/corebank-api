package com.corebank.corebank_api.reporting;

import com.corebank.corebank_api.integration.saga.SagaQueryService;
import com.corebank.corebank_api.ops.reconciliation.ReconciliationService;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/reporting")
public class ReportingController {

	private final ReadModelQueryService readModelQueryService;
	private final SagaQueryService sagaQueryService;
	private final OutboxReportingService outboxReportingService;
	private final ReconciliationService reconciliationService;

	public ReportingController(
			ReadModelQueryService readModelQueryService,
			SagaQueryService sagaQueryService,
			OutboxReportingService outboxReportingService,
			ReconciliationService reconciliationService) {
		this.readModelQueryService = readModelQueryService;
		this.sagaQueryService = sagaQueryService;
		this.outboxReportingService = outboxReportingService;
		this.reconciliationService = reconciliationService;
	}

	@GetMapping("/aggregate-activity")
	public ResponseEntity<ReadModelQueryService.AggregateActivityPage> aggregateActivity(
			@RequestParam(required = false) String aggregateType,
			@RequestParam(required = false) String aggregateId,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		ReadModelQueryService.AggregateActivityPage response =
				readModelQueryService.findAggregateActivity(aggregateType, aggregateId, page, size);
		return ResponseEntity.ok(response);
	}

	@GetMapping("/aggregate-activity/{aggregateType}/{aggregateId}/events")
	public ResponseEntity<ReadModelQueryService.AggregateEventPage> aggregateEvents(
			@PathVariable String aggregateType,
			@PathVariable String aggregateId,
			@RequestParam(required = false) String eventType,
			@RequestParam(required = false) Instant fromOccurredAt,
			@RequestParam(required = false) Instant toOccurredAt,
			@RequestParam(defaultValue = "50") int limit) {
		if (fromOccurredAt != null && toOccurredAt != null && fromOccurredAt.isAfter(toOccurredAt)) {
			throw new ResponseStatusException(
					HttpStatus.BAD_REQUEST,
					"fromOccurredAt must be earlier than or equal to toOccurredAt");
		}

		ReadModelQueryService.AggregateEventPage response =
				readModelQueryService.findAggregateEvents(
						aggregateType, aggregateId, eventType, fromOccurredAt, toOccurredAt, limit);
		return ResponseEntity.ok(response);
	}

	@GetMapping("/sagas")
	public ResponseEntity<SagaQueryService.SagaInstancePage> sagas(
			@RequestParam(required = false) String sagaType,
			@RequestParam(required = false) String status,
			@RequestParam(required = false) String businessKey,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		SagaQueryService.SagaInstancePage response =
				sagaQueryService.findSagas(sagaType, status, businessKey, page, size);
		return ResponseEntity.ok(response);
	}

	@GetMapping("/sagas/{sagaInstanceId}/steps")
	public ResponseEntity<SagaQueryService.SagaStepPage> sagaSteps(
			@PathVariable UUID sagaInstanceId,
			@RequestParam(defaultValue = "100") int limit) {
		SagaQueryService.SagaStepPage response =
				sagaQueryService.findSagaSteps(sagaInstanceId, limit);
		return ResponseEntity.ok(response);
	}

	@GetMapping("/outbox/summary")
	public ResponseEntity<OutboxReportingService.OutboxSummary> outboxSummary() {
		return ResponseEntity.ok(outboxReportingService.summary());
	}

	@GetMapping("/outbox/dead-letters")
	public ResponseEntity<OutboxReportingService.OutboxDeadLetterPage> outboxDeadLetters(
			@RequestParam(defaultValue = "50") int limit,
			@RequestParam(required = false) String eventType,
			@RequestParam(required = false) String aggregateType,
			@RequestParam(required = false) Instant fromDeadLetteredAt,
			@RequestParam(required = false) Instant toDeadLetteredAt) {
		if (fromDeadLetteredAt != null
				&& toDeadLetteredAt != null
				&& fromDeadLetteredAt.isAfter(toDeadLetteredAt)) {
			throw new ResponseStatusException(
					HttpStatus.BAD_REQUEST,
					"fromDeadLetteredAt must be earlier than or equal to toDeadLetteredAt");
		}

		return ResponseEntity.ok(outboxReportingService.deadLetters(
				limit,
				eventType,
				aggregateType,
				fromDeadLetteredAt,
				toDeadLetteredAt));
	}

	@GetMapping("/reconciliation/breaks")
	public ResponseEntity<ReconciliationService.ReconciliationBreakPage> reconciliationBreaks(
			@RequestParam(required = false) Long runId,
			@RequestParam(required = false) String status,
			@RequestParam(required = false) String severity,
			@RequestParam(defaultValue = "50") int limit) {
		return ResponseEntity.ok(reconciliationService.listBreaks(runId, status, severity, limit));
	}
}
