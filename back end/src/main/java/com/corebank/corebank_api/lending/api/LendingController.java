package com.corebank.corebank_api.lending.api;

import com.corebank.corebank_api.common.CoreBankException;
import com.corebank.corebank_api.lending.LoanApplicationService;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/lending")
public class LendingController {

	private final LoanApplicationService loanApplicationService;

	public LendingController(LoanApplicationService loanApplicationService) {
		this.loanApplicationService = loanApplicationService;
	}

	@PostMapping("/disburse")
	public ResponseEntity<LoanApplicationService.LoanDisbursementResponse> disburse(
			@RequestBody LoanApplicationService.LoanDisbursementRequest request) {
		try {
			return ResponseEntity.ok(loanApplicationService.disburseLoan(request));
		} catch (CoreBankException ex) {
			throw toHttpException(ex);
		}
	}

	@PostMapping("/repay")
	public ResponseEntity<LoanApplicationService.LoanRepaymentResponse> repay(
			@RequestBody LoanApplicationService.LoanRepaymentRequest request) {
		try {
			return ResponseEntity.ok(loanApplicationService.repayLoan(request));
		} catch (CoreBankException ex) {
			throw toHttpException(ex);
		}
	}

	private ResponseStatusException toHttpException(CoreBankException exception) {
		String message = exception.getMessage() == null ? "Lending command failed" : exception.getMessage();
		String lowered = message.toLowerCase(Locale.ROOT);
		HttpStatus status = lowered.contains("not found")
				? HttpStatus.NOT_FOUND
				: lowered.contains("writes are not allowed")
						? HttpStatus.CONFLICT
						: HttpStatus.BAD_REQUEST;
		return new ResponseStatusException(status, message, exception);
	}
}

