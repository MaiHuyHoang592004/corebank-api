package com.corebank.corebank_api.reporting;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reporting")
public class ReportingController {

	private final ReadModelQueryService readModelQueryService;

	public ReportingController(ReadModelQueryService readModelQueryService) {
		this.readModelQueryService = readModelQueryService;
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
			@RequestParam(defaultValue = "50") int limit) {
		ReadModelQueryService.AggregateEventPage response =
				readModelQueryService.findAggregateEvents(aggregateType, aggregateId, limit);
		return ResponseEntity.ok(response);
	}
}
