package com.corebank.corebank_api.transfer.api;

import com.corebank.corebank_api.common.CoreBankException;
import com.corebank.corebank_api.transfer.TransferService;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/transfers")
public class TransferController {

	private final TransferService transferService;

	public TransferController(TransferService transferService) {
		this.transferService = transferService;
	}

	@PostMapping("/internal")
	public ResponseEntity<TransferService.TransferResponse> transfer(
			@RequestBody TransferService.TransferRequest request) {
		try {
			return ResponseEntity.ok(transferService.transfer(request));
		} catch (CoreBankException ex) {
			throw toHttpException(ex);
		}
	}

	private ResponseStatusException toHttpException(CoreBankException exception) {
		String message = exception.getMessage() == null ? "Transfer command failed" : exception.getMessage();
		String lowered = message.toLowerCase(Locale.ROOT);
		HttpStatus status = lowered.contains("not found")
				? HttpStatus.NOT_FOUND
				: lowered.contains("writes are not allowed")
						? HttpStatus.CONFLICT
						: HttpStatus.BAD_REQUEST;
		return new ResponseStatusException(status, message, exception);
	}
}

