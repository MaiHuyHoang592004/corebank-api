package com.corebank.corebank_api.deposit;

import com.corebank.corebank_api.common.CoreBankException;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

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
		try {
			DepositApplicationService.OpenDepositResponse response = depositApplicationService.openDeposit(request);
			return ResponseEntity.ok(response);
		} catch (CoreBankException ex) {
			throw toHttpException(ex);
		}
	}

	@PostMapping("/accrue")
	public ResponseEntity<DepositApplicationService.AccrueInterestResponse> accrueInterest(
			@RequestBody DepositApplicationService.AccrueInterestRequest request) {
		try {
			DepositApplicationService.AccrueInterestResponse response = depositApplicationService.accrueInterest(request);
			return ResponseEntity.ok(response);
		} catch (CoreBankException ex) {
			throw toHttpException(ex);
		}
	}

	@PostMapping("/maturity")
	public ResponseEntity<DepositApplicationService.MaturityResponse> processMaturity(
			@RequestBody DepositApplicationService.MaturityRequest request) {
		try {
			DepositApplicationService.MaturityResponse response = depositApplicationService.processMaturity(request);
			return ResponseEntity.ok(response);
		} catch (CoreBankException ex) {
			throw toHttpException(ex);
		}
	}

	private ResponseStatusException toHttpException(CoreBankException exception) {
		String message = exception.getMessage() == null ? "Deposit command failed" : exception.getMessage();
		String lowered = message.toLowerCase(Locale.ROOT);
		HttpStatus status = lowered.contains("not found")
				? HttpStatus.NOT_FOUND
				: lowered.contains("writes are not allowed")
						? HttpStatus.CONFLICT
						: HttpStatus.BAD_REQUEST;
		return new ResponseStatusException(status, message, exception);
	}
}
