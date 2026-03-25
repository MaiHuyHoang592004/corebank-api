package com.corebank.corebank_api.product;

import com.corebank.corebank_api.common.CoreBankException;
import com.corebank.corebank_api.ops.iam.IamAuthorizationService;
import java.time.Instant;
import java.util.Map;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/products")
public class ProductGovernanceController {

	private final ProductGovernanceService productGovernanceService;
	private final IamAuthorizationService iamAuthorizationService;

	public ProductGovernanceController(
			ProductGovernanceService productGovernanceService,
			IamAuthorizationService iamAuthorizationService) {
		this.productGovernanceService = productGovernanceService;
		this.iamAuthorizationService = iamAuthorizationService;
	}

	@PostMapping("/{productId}/versions")
	public ResponseEntity<ProductGovernanceService.ProductVersionView> createVersion(
			@PathVariable UUID productId,
			@RequestBody(required = false) CreateProductVersionRequest request,
			Authentication authentication) {
		iamAuthorizationService.requirePermission(authentication, "PRODUCT_GOVERNANCE_WRITE");
		try {
			ProductGovernanceService.ProductVersionView version = productGovernanceService.createDraftVersion(
					productId,
					new ProductGovernanceService.CreateVersionCommand(
							request == null ? null : request.versionNo(),
							request == null ? null : request.effectiveFrom(),
							request == null ? null : request.effectiveTo(),
							request == null ? null : request.configuration()));
			return ResponseEntity.status(HttpStatus.CREATED).body(version);
		} catch (CoreBankException ex) {
			throw toHttpException(ex);
		}
	}

	@PostMapping("/{productId}/versions/{productVersionId}/activate")
	public ResponseEntity<ProductGovernanceService.ProductVersionView> activateVersion(
			@PathVariable UUID productId,
			@PathVariable UUID productVersionId,
			Authentication authentication) {
		iamAuthorizationService.requirePermission(authentication, "PRODUCT_GOVERNANCE_WRITE");
		try {
			return ResponseEntity.ok(productGovernanceService.activateVersion(productId, productVersionId));
		} catch (CoreBankException ex) {
			throw toHttpException(ex);
		}
	}

	@PostMapping("/{productId}/versions/{productVersionId}/retire")
	public ResponseEntity<ProductGovernanceService.ProductVersionView> retireVersion(
			@PathVariable UUID productId,
			@PathVariable UUID productVersionId,
			Authentication authentication) {
		iamAuthorizationService.requirePermission(authentication, "PRODUCT_GOVERNANCE_WRITE");
		try {
			return ResponseEntity.ok(productGovernanceService.retireVersion(productId, productVersionId));
		} catch (CoreBankException ex) {
			throw toHttpException(ex);
		}
	}

	@GetMapping("/{productId}/versions")
	public ResponseEntity<ProductGovernanceService.ProductVersionPage> listVersions(
			@PathVariable UUID productId,
			Authentication authentication) {
		iamAuthorizationService.requirePermission(authentication, "PRODUCT_GOVERNANCE_READ");
		try {
			return ResponseEntity.ok(productGovernanceService.listVersions(productId));
		} catch (CoreBankException ex) {
			throw toHttpException(ex);
		}
	}

	private ResponseStatusException toHttpException(CoreBankException exception) {
		String message = exception.getMessage() == null ? "Product governance validation failed" : exception.getMessage();
		String lowered = message.toLowerCase(Locale.ROOT);
		HttpStatus status = lowered.contains("already exists")
				|| lowered.contains("cannot be reactivated")
				? HttpStatus.CONFLICT
				: HttpStatus.BAD_REQUEST;
		return new ResponseStatusException(status, message, exception);
	}

	public record CreateProductVersionRequest(
			Integer versionNo,
			Instant effectiveFrom,
			Instant effectiveTo,
			Map<String, Object> configuration) {
	}
}
