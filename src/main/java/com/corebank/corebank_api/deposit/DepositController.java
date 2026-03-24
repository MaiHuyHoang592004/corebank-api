package com.corebank.corebank_api.deposit;

import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/deposits")
public class DepositController {

	private final DepositApplicationService depositApplicationService;

	public DepositController(DepositApplicationService depositApplicationService) {
		this.depositApplicationService = depositApplicationService;
	}

	@PostMapping("/open")
	public ResponseEntity<DepositApplicationService.OpenDepositResponse> openDeposit(
			@RequestBody DepositApplicationService.OpenDepositRequest request) {
		
		DepositApplicationService.OpenDepositResponse response = depositApplicationService.openDeposit(request);
		return ResponseEntity.ok(response);
	}

	@PostMapping("/accrue")
	public ResponseEntity<DepositApplicationService.AccrueInterestResponse> accrueInterest(
			@RequestBody DepositApplicationService.AccrueInterestRequest request) {
		
		DepositApplicationService.AccrueInterestResponse response = depositApplicationService.accrueInterest(request);
		return ResponseEntity.ok(response);
	}

	@PostMapping("/maturity")
	public ResponseEntity<DepositApplicationService.MaturityResponse> processMaturity(
			@RequestBody DepositApplicationService.MaturityRequest request) {
		
		DepositApplicationService.MaturityResponse response = depositApplicationService.processMaturity(request);
		return ResponseEntity.ok(response);
	}
}