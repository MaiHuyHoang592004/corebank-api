package com.corebank.corebank_api.product;

import com.corebank.corebank_api.common.CoreBankException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductGovernanceService {

	private final JdbcTemplate jdbcTemplate;
	private final ObjectMapper objectMapper;

	private final RowMapper<ProductVersionView> versionRowMapper = new RowMapper<>() {
		@Override
		public ProductVersionView mapRow(ResultSet rs, int rowNum) throws SQLException {
			return new ProductVersionView(
					rs.getObject("product_version_id", UUID.class),
					rs.getObject("product_id", UUID.class),
					rs.getInt("version_no"),
					readInstant(rs.getTimestamp("effective_from")),
					readInstant(rs.getTimestamp("effective_to")),
					rs.getString("status"),
					readConfigurationMap(rs.getString("configuration_json")));
		}
	};

	public ProductGovernanceService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
		this.jdbcTemplate = jdbcTemplate;
		this.objectMapper = objectMapper.copy().findAndRegisterModules();
	}

	@Transactional
	public ProductVersionView createDraftVersion(UUID productId, CreateVersionCommand command) {
		ProductSnapshot product = requireProduct(productId);

		Instant effectiveFrom = command.effectiveFrom() == null ? Instant.now() : command.effectiveFrom();
		Instant effectiveTo = command.effectiveTo();
		if (effectiveTo != null && !effectiveTo.isAfter(effectiveFrom)) {
			throw new CoreBankException("effectiveTo must be later than effectiveFrom");
		}

		int versionNo = command.versionNo() == null ? nextVersionNo(product.productId()) : command.versionNo();
		if (versionNo <= 0) {
			throw new CoreBankException("versionNo must be positive");
		}

		UUID productVersionId = UUID.randomUUID();
		String configurationJson = toJson(command.configuration());

		jdbcTemplate.update(
				"""
				INSERT INTO bank_product_versions (
				    product_version_id,
				    product_id,
				    version_no,
				    effective_from,
				    effective_to,
				    status,
				    configuration_json,
				    created_at
				) VALUES (?, ?, ?, ?, ?, 'DRAFT', CAST(? AS jsonb), now())
				""",
				productVersionId,
				product.productId(),
				versionNo,
				Timestamp.from(effectiveFrom),
				effectiveTo == null ? null : Timestamp.from(effectiveTo),
				configurationJson);

		return getVersion(product.productId(), productVersionId);
	}

	@Transactional
	public ProductVersionView activateVersion(UUID productId, UUID productVersionId) {
		ProductVersionView version = getVersionForUpdate(productId, productVersionId);
		if ("RETIRED".equals(version.status())) {
			throw new CoreBankException("Retired version cannot be reactivated");
		}

		int activeOtherVersions = countInt(
				"""
				SELECT COUNT(*)
				FROM bank_product_versions
				WHERE product_id = ?
				  AND status = 'ACTIVE'
				  AND product_version_id <> ?
				""",
				productId,
				productVersionId);
		if (activeOtherVersions > 0) {
			throw new CoreBankException("Another ACTIVE version already exists for this product");
		}

		jdbcTemplate.update(
				"""
				UPDATE bank_product_versions
				SET status = 'ACTIVE'
				WHERE product_id = ?
				  AND product_version_id = ?
				""",
				productId,
				productVersionId);

		return getVersion(productId, productVersionId);
	}

	@Transactional
	public ProductVersionView retireVersion(UUID productId, UUID productVersionId) {
		getVersionForUpdate(productId, productVersionId);
		jdbcTemplate.update(
				"""
				UPDATE bank_product_versions
				SET status = 'RETIRED'
				WHERE product_id = ?
				  AND product_version_id = ?
				""",
				productId,
				productVersionId);
		return getVersion(productId, productVersionId);
	}

	@Transactional(readOnly = true)
	public ProductVersionPage listVersions(UUID productId) {
		requireProduct(productId);
		List<ProductVersionView> versions = jdbcTemplate.query(
				"""
				SELECT product_version_id,
				       product_id,
				       version_no,
				       effective_from,
				       effective_to,
				       status,
				       configuration_json::text AS configuration_json
				FROM bank_product_versions
				WHERE product_id = ?
				ORDER BY version_no DESC, effective_from DESC, product_version_id DESC
				""",
				versionRowMapper,
				productId);
		return new ProductVersionPage(productId, versions);
	}

	@Transactional(readOnly = true)
	public MissingActiveVersionPage listMissingActiveVersions(Integer requestedLimit) {
		int limit = normalizeLimit(requestedLimit, 100, 500, "limit");
		Instant asOf = Instant.now();
		int totalMissingCount = countMissingActiveVersions(asOf);
		List<MissingActiveVersionItem> items = findMissingActiveVersions(asOf, limit);
		return new MissingActiveVersionPage(
				asOf,
				limit,
				totalMissingCount,
				totalMissingCount > items.size(),
				items);
	}

	@Transactional
	public BackfillActiveVersionsResult backfillMissingActiveVersions(Boolean dryRun, Integer requestedLimit) {
		boolean useDryRun = dryRun == null || dryRun;
		int limit = normalizeLimit(requestedLimit, 100, 500, "limit");
		Instant asOf = Instant.now();
		List<MissingActiveVersionItem> items = findMissingActiveVersions(asOf, limit);

		int fixedCount = 0;
		int skippedCount = 0;
		int errorCount = 0;
		List<BackfillActiveVersionItem> results = new java.util.ArrayList<>();

		for (MissingActiveVersionItem item : items) {
			try {
				if (hasActiveEffectiveVersion(item.productId(), asOf)) {
					skippedCount++;
					results.add(new BackfillActiveVersionItem(
							item.productId(),
							item.productCode(),
							item.productName(),
							"SKIPPED_ALREADY_OK",
							null,
							"Product already has ACTIVE/effective version"));
					continue;
				}

				Optional<ProductVersionSnapshot> latestVersion = findLatestVersion(item.productId());
				if (latestVersion.isPresent()) {
					ProductVersionSnapshot chosen = latestVersion.get();
					if (useDryRun) {
						results.add(new BackfillActiveVersionItem(
								item.productId(),
								item.productCode(),
								item.productName(),
								"WOULD_FIX_PROMOTED",
								chosen.productVersionId(),
								"Would promote latest version to ACTIVE"));
					} else {
						promoteLatestVersion(item.productId(), chosen.productVersionId(), asOf);
						fixedCount++;
						results.add(new BackfillActiveVersionItem(
								item.productId(),
								item.productCode(),
								item.productName(),
								"FIXED_PROMOTED",
								chosen.productVersionId(),
								"Promoted latest version to ACTIVE"));
					}
					continue;
				}

				int nextVersionNo = nextVersionNo(item.productId());
				UUID createdVersionId = UUID.randomUUID();
				if (useDryRun) {
					results.add(new BackfillActiveVersionItem(
							item.productId(),
							item.productCode(),
							item.productName(),
							"WOULD_FIX_CREATED",
							createdVersionId,
							"Would create ACTIVE version for legacy product"));
				} else {
					createBackfillActiveVersion(item.productId(), createdVersionId, nextVersionNo, asOf);
					fixedCount++;
					results.add(new BackfillActiveVersionItem(
							item.productId(),
							item.productCode(),
							item.productName(),
							"FIXED_CREATED",
							createdVersionId,
							"Created ACTIVE version for legacy product"));
				}
			} catch (RuntimeException ex) {
				errorCount++;
				results.add(new BackfillActiveVersionItem(
						item.productId(),
						item.productCode(),
						item.productName(),
						"ERROR",
						null,
						ex.getMessage() == null ? "Unknown backfill error" : ex.getMessage()));
			}
		}

		return new BackfillActiveVersionsResult(
				asOf,
				useDryRun,
				limit,
				items.size(),
				items.size(),
				fixedCount,
				skippedCount,
				errorCount,
				results);
	}

	@Transactional(readOnly = true)
	public void validateProductVersionBinding(UUID productId, UUID productVersionId) {
		if (productId == null) {
			throw new CoreBankException("productId is required");
		}
		if (productVersionId == null) {
			throw new CoreBankException("productVersionId is required");
		}

		ProductSnapshot product = requireProduct(productId);
		if (!"ACTIVE".equals(product.status())) {
			throw new CoreBankException("Product is not ACTIVE");
		}

		ProductVersionView version = getVersion(productId, productVersionId);
		Instant now = Instant.now();

		if (!"ACTIVE".equals(version.status())) {
			throw new CoreBankException("Product version is not ACTIVE");
		}
		if (version.effectiveFrom() != null && version.effectiveFrom().isAfter(now)) {
			throw new CoreBankException("Product version is not effective yet");
		}
		if (version.effectiveTo() != null && !version.effectiveTo().isAfter(now)) {
			throw new CoreBankException("Product version is expired");
		}
	}

	private int countMissingActiveVersions(Instant asOf) {
		Integer count = jdbcTemplate.queryForObject(
				"""
				SELECT COUNT(*)
				FROM bank_products bp
				WHERE bp.status = 'ACTIVE'
				  AND NOT EXISTS (
				    SELECT 1
				    FROM bank_product_versions bpv
				    WHERE bpv.product_id = bp.product_id
				      AND bpv.status = 'ACTIVE'
				      AND bpv.effective_from <= ?
				      AND (bpv.effective_to IS NULL OR bpv.effective_to > ?)
				  )
				""",
				Integer.class,
				Timestamp.from(asOf),
				Timestamp.from(asOf));
		return count == null ? 0 : count;
	}

	private List<MissingActiveVersionItem> findMissingActiveVersions(Instant asOf, int limit) {
		return jdbcTemplate.query(
				"""
				SELECT bp.product_id, bp.product_code, bp.product_name
				FROM bank_products bp
				WHERE bp.status = 'ACTIVE'
				  AND NOT EXISTS (
				    SELECT 1
				    FROM bank_product_versions bpv
				    WHERE bpv.product_id = bp.product_id
				      AND bpv.status = 'ACTIVE'
				      AND bpv.effective_from <= ?
				      AND (bpv.effective_to IS NULL OR bpv.effective_to > ?)
				  )
				ORDER BY bp.product_code ASC, bp.product_id ASC
				LIMIT ?
				""",
				(rs, rowNum) -> new MissingActiveVersionItem(
						rs.getObject("product_id", UUID.class),
						rs.getString("product_code"),
						rs.getString("product_name")),
				Timestamp.from(asOf),
				Timestamp.from(asOf),
				limit);
	}

	private boolean hasActiveEffectiveVersion(UUID productId, Instant asOf) {
		Integer count = jdbcTemplate.queryForObject(
				"""
				SELECT COUNT(*)
				FROM bank_product_versions
				WHERE product_id = ?
				  AND status = 'ACTIVE'
				  AND effective_from <= ?
				  AND (effective_to IS NULL OR effective_to > ?)
				""",
				Integer.class,
				productId,
				Timestamp.from(asOf),
				Timestamp.from(asOf));
		return count != null && count > 0;
	}

	private Optional<ProductVersionSnapshot> findLatestVersion(UUID productId) {
		List<ProductVersionSnapshot> rows = jdbcTemplate.query(
				"""
				SELECT product_version_id, version_no, effective_from
				FROM bank_product_versions
				WHERE product_id = ?
				ORDER BY version_no DESC, effective_from DESC, product_version_id DESC
				LIMIT 1
				""",
				(rs, rowNum) -> new ProductVersionSnapshot(
						rs.getObject("product_version_id", UUID.class),
						rs.getInt("version_no"),
						readInstant(rs.getTimestamp("effective_from"))),
				productId);
		return rows.stream().findFirst();
	}

	private void promoteLatestVersion(UUID productId, UUID productVersionId, Instant asOf) {
		jdbcTemplate.update(
				"""
				UPDATE bank_product_versions
				SET status = 'DRAFT'
				WHERE product_id = ?
				  AND status = 'ACTIVE'
				  AND product_version_id <> ?
				""",
				productId,
				productVersionId);

		int updated = jdbcTemplate.update(
				"""
				UPDATE bank_product_versions
				SET status = 'ACTIVE',
				    effective_from = CASE
				        WHEN effective_from > ? THEN ?
				        ELSE effective_from
				    END,
				    effective_to = NULL
				WHERE product_id = ?
				  AND product_version_id = ?
				""",
				Timestamp.from(asOf),
				Timestamp.from(asOf),
				productId,
				productVersionId);
		if (updated != 1) {
			throw new CoreBankException("Unable to promote latest version for product");
		}
	}

	private void createBackfillActiveVersion(UUID productId, UUID productVersionId, int versionNo, Instant asOf) {
		Map<String, Object> configuration = Map.of(
				"source", "strict-rollout-backfill",
				"generatedAt", asOf.toString());
		jdbcTemplate.update(
				"""
				INSERT INTO bank_product_versions (
				    product_version_id,
				    product_id,
				    version_no,
				    effective_from,
				    effective_to,
				    status,
				    configuration_json,
				    created_at
				) VALUES (?, ?, ?, ?, NULL, 'ACTIVE', CAST(? AS jsonb), now())
				""",
				productVersionId,
				productId,
				versionNo,
				Timestamp.from(asOf),
				toJson(configuration));
	}

	private int normalizeLimit(Integer requestedLimit, int defaultLimit, int maxLimit, String fieldName) {
		int limit = requestedLimit == null ? defaultLimit : requestedLimit;
		if (limit <= 0 || limit > maxLimit) {
			throw new CoreBankException(fieldName + " must be between 1 and " + maxLimit);
		}
		return limit;
	}

	private ProductSnapshot requireProduct(UUID productId) {
		List<ProductSnapshot> products = jdbcTemplate.query(
				"""
				SELECT product_id, status
				FROM bank_products
				WHERE product_id = ?
				""",
				(rs, rowNum) -> new ProductSnapshot(
						rs.getObject("product_id", UUID.class),
						rs.getString("status")),
				productId);

		if (products.isEmpty()) {
			throw new CoreBankException("Product not found: " + productId);
		}
		return products.get(0);
	}

	private ProductVersionView getVersion(UUID productId, UUID productVersionId) {
		List<ProductVersionView> rows = jdbcTemplate.query(
				"""
				SELECT product_version_id,
				       product_id,
				       version_no,
				       effective_from,
				       effective_to,
				       status,
				       configuration_json::text AS configuration_json
				FROM bank_product_versions
				WHERE product_id = ?
				  AND product_version_id = ?
				""",
				versionRowMapper,
				productId,
				productVersionId);

		if (rows.isEmpty()) {
			throw new CoreBankException("Product version not found for product");
		}
		return rows.get(0);
	}

	private ProductVersionView getVersionForUpdate(UUID productId, UUID productVersionId) {
		List<ProductVersionView> rows = jdbcTemplate.query(
				"""
				SELECT product_version_id,
				       product_id,
				       version_no,
				       effective_from,
				       effective_to,
				       status,
				       configuration_json::text AS configuration_json
				FROM bank_product_versions
				WHERE product_id = ?
				  AND product_version_id = ?
				FOR UPDATE
				""",
				versionRowMapper,
				productId,
				productVersionId);

		if (rows.isEmpty()) {
			throw new CoreBankException("Product version not found for product");
		}
		return rows.get(0);
	}

	private int nextVersionNo(UUID productId) {
		Integer maxVersion = jdbcTemplate.queryForObject(
				"SELECT COALESCE(MAX(version_no), 0) FROM bank_product_versions WHERE product_id = ?",
				Integer.class,
				productId);
		return (maxVersion == null ? 0 : maxVersion) + 1;
	}

	private int countInt(String sql, Object... args) {
		Integer count = jdbcTemplate.queryForObject(sql, Integer.class, args);
		return count == null ? 0 : count;
	}

	private String toJson(Map<String, Object> configuration) {
		Map<String, Object> safeConfiguration = Optional.ofNullable(configuration).orElseGet(Map::of);
		try {
			return objectMapper.writeValueAsString(safeConfiguration);
		} catch (JsonProcessingException ex) {
			throw new CoreBankException("Unable to serialize product version configuration", ex);
		}
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> readConfigurationMap(String json) {
		if (json == null || json.isBlank()) {
			return Map.of();
		}
		try {
			Object value = objectMapper.readValue(json, Map.class);
			if (value instanceof Map<?, ?> mapValue) {
				return (Map<String, Object>) mapValue;
			}
			return Map.of();
		} catch (JsonProcessingException ex) {
			throw new IllegalStateException("Unable to parse product version configuration JSON", ex);
		}
	}

	private Instant readInstant(Timestamp timestamp) {
		return timestamp == null ? null : timestamp.toInstant();
	}

	private record ProductSnapshot(
			UUID productId,
			String status) {
	}

	public record CreateVersionCommand(
			Integer versionNo,
			Instant effectiveFrom,
			Instant effectiveTo,
			Map<String, Object> configuration) {
	}

	public record ProductVersionPage(
			UUID productId,
			List<ProductVersionView> items) {
	}

	public record MissingActiveVersionPage(
			Instant asOf,
			int limit,
			int totalMissingCount,
			boolean truncated,
			List<MissingActiveVersionItem> items) {
	}

	public record MissingActiveVersionItem(
			UUID productId,
			String productCode,
			String productName) {
	}

	public record BackfillActiveVersionsResult(
			Instant asOf,
			boolean dryRun,
			int limit,
			int requestedCount,
			int processedCount,
			int fixedCount,
			int skippedCount,
			int errorCount,
			List<BackfillActiveVersionItem> items) {
	}

	public record BackfillActiveVersionItem(
			UUID productId,
			String productCode,
			String productName,
			String action,
			UUID productVersionId,
			String message) {
	}

	public record ProductVersionView(
			UUID productVersionId,
			UUID productId,
			int versionNo,
			Instant effectiveFrom,
			Instant effectiveTo,
			String status,
			Map<String, Object> configuration) {
	}

	private record ProductVersionSnapshot(
			UUID productVersionId,
			int versionNo,
			Instant effectiveFrom) {
	}
}
