package com.corebank.corebank_api.limits;

import com.corebank.corebank_api.common.CoreBankException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service to check and enforce transactional and periodic limits.
 *
 * <p>Limit types:
 * <ul>
 *   <li>TRANSACTIONAL - per transaction limits (amount, count)</li>
 *   <li>PERIODIC - periodic limits (daily, weekly, monthly, yearly)</li>
 * </ul>
 * </p>
 */
@Service
public class LimitCheckService {

	private final JdbcTemplate jdbcTemplate;

	public LimitCheckService(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	/**
	 * Check all limits for a transaction.
	 *
	 * @param customerAccountId customer account ID
	 * @param amountMinor transaction amount in minor units
	 * @param currency currency code
	 * @throws CoreBankException if any limit is exceeded
	 */
	@Transactional
	public void enforceLimits(UUID customerAccountId, long amountMinor, String currency) {
		// Get limit profile for the account
		LimitProfile profile = getLimitProfile(customerAccountId, currency);

		if (profile == null) {
			// No limits assigned - allow transaction
			return;
		}

		// Get all active rules for this profile
		List<LimitRule> rules = getActiveRules(profile.profileId());

		for (LimitRule rule : rules) {
			if ("TRANSACTIONAL".equals(rule.ruleType())) {
				checkTransactionalLimit(rule, amountMinor);
			} else if ("PERIODIC".equals(rule.ruleType())) {
				checkPeriodicLimit(customerAccountId, rule, amountMinor, currency);
			}
		}
	}

	/**
	 * Check transactional limit.
	 *
	 * @param rule limit rule
	 * @param amountMinor transaction amount
	 * @throws CoreBankException if limit is exceeded
	 */
	private void checkTransactionalLimit(LimitRule rule, long amountMinor) {
		if ("AMOUNT".equals(rule.limitType())) {
			if (amountMinor > rule.limitValue()) {
				throw new CoreBankException(
						"Transaction amount " + amountMinor + " exceeds limit " + rule.limitValue() +
						" for rule " + rule.ruleName());
			}
		}
		// Transactional count limits are not applicable per transaction
	}

	/**
	 * Check periodic limit.
	 *
	 * @param customerAccountId customer account ID
	 * @param rule limit rule
	 * @param amountMinor transaction amount
	 * @param currency currency code
	 * @throws CoreBankException if limit is exceeded
	 */
	private void checkPeriodicLimit(UUID customerAccountId, LimitRule rule, long amountMinor, String currency) {
		// Get or create usage counter (this will use the same period logic as incrementUsage)
		LimitUsageCounter counter = getOrCreateCounterForNow(customerAccountId, rule.ruleId(), currency);

		if ("AMOUNT".equals(rule.limitType())) {
			long projectedUsage = counter.usedValue() + amountMinor;
			if (projectedUsage > rule.limitValue()) {
				throw new CoreBankException(
						"Periodic amount limit exceeded. Used: " + counter.usedValue() +
						", Limit: " + rule.limitValue() +
						", Transaction: " + amountMinor +
						" for rule " + rule.ruleName());
			}
		} else if ("COUNT".equals(rule.limitType())) {
			int projectedCount = counter.usageCount() + 1;
			if (projectedCount > rule.limitValue()) {
				throw new CoreBankException(
						"Periodic count limit exceeded. Used: " + counter.usageCount() +
						", Limit: " + rule.limitValue() +
						" for rule " + rule.ruleName());
			}
		}
	}

	/**
	 * Increment usage counter after successful transaction.
	 *
	 * @param customerAccountId customer account ID
	 * @param amountMinor transaction amount
	 * @param currency currency code
	 */
	@Transactional
	public void incrementUsage(UUID customerAccountId, long amountMinor, String currency) {
		// Get limit profile for the account
		LimitProfile profile = getLimitProfile(customerAccountId, currency);

		if (profile == null) {
			// No limits assigned - nothing to increment
			return;
		}

		// Get all active periodic rules for this profile
		List<LimitRule> rules = getActiveRules(profile.profileId()).stream()
				.filter(rule -> "PERIODIC".equals(rule.ruleType()))
				.toList();

		Instant now = Instant.now();

		for (LimitRule rule : rules) {
			Instant periodStart = getPeriodStart(now, rule.periodType());
			Instant periodEnd = getPeriodEnd(now, rule.periodType());

			// Get or create usage counter
			LimitUsageCounter counter = getOrCreateCounter(customerAccountId, rule.ruleId(), currency, periodStart, periodEnd);

			// Increment counter
			if ("AMOUNT".equals(rule.limitType())) {
				incrementAmountCounter(counter.counterId(), amountMinor);
			} else if ("COUNT".equals(rule.limitType())) {
				incrementCountCounter(counter.counterId());
			}
		}
	}

	/**
	 * Get limit profile for customer account.
	 *
	 * @param customerAccountId customer account ID
	 * @param currency currency code
	 * @return limit profile or null if not found
	 */
	private LimitProfile getLimitProfile(UUID customerAccountId, String currency) {
		List<LimitProfile> profiles = jdbcTemplate.query(
				"""
				SELECT lp.profile_id, lp.profile_code, lp.profile_name
				FROM limit_assignments la
				JOIN limit_profiles lp ON lp.profile_id = la.profile_id
				WHERE la.customer_account_id = ?
				  AND la.currency = ?
				  AND lp.status = 'ACTIVE'
				  AND (la.expires_at IS NULL OR la.expires_at > now())
				""",
				new RowMapper<LimitProfile>() {
					@Override
					public LimitProfile mapRow(ResultSet rs, int rowNum) throws SQLException {
						return new LimitProfile(
								rs.getObject("profile_id", UUID.class),
								rs.getString("profile_code"),
								rs.getString("profile_name"));
					}
				},
				customerAccountId,
				currency);

		return profiles.isEmpty() ? null : profiles.get(0);
	}

	/**
	 * Get active rules for profile.
	 *
	 * @param profileId profile ID
	 * @return list of active rules
	 */
	private List<LimitRule> getActiveRules(UUID profileId) {
		return jdbcTemplate.query(
				"""
				SELECT rule_id, profile_id, rule_name, rule_type, limit_type, limit_value, currency, period_type
				FROM limit_rules
				WHERE profile_id = ?
				  AND status = 'ACTIVE'
				""",
				new RowMapper<LimitRule>() {
					@Override
					public LimitRule mapRow(ResultSet rs, int rowNum) throws SQLException {
						return new LimitRule(
								rs.getObject("rule_id", UUID.class),
								rs.getObject("profile_id", UUID.class),
								rs.getString("rule_name"),
								rs.getString("rule_type"),
								rs.getString("limit_type"),
								rs.getLong("limit_value"),
								rs.getString("currency"),
								rs.getString("period_type"));
					}
				},
				profileId);
	}

	/**
	 * Get or create usage counter for the current period.
	 *
	 * @param customerAccountId customer account ID
	 * @param ruleId rule ID
	 * @param currency currency code
	 * @return usage counter
	 */
	private LimitUsageCounter getOrCreateCounterForNow(UUID customerAccountId, UUID ruleId, String currency) {
		// Get the rule to determine period type
		List<String> periodTypes = jdbcTemplate.query(
				"""
				SELECT period_type FROM limit_rules WHERE rule_id = ?
				""",
				(rs, rowNum) -> rs.getString("period_type"),
				ruleId);

		if (periodTypes.isEmpty()) {
			throw new CoreBankException("Limit rule not found: " + ruleId);
		}

		String periodType = periodTypes.get(0);
		Instant now = Instant.now();
		Instant periodStart = getPeriodStart(now, periodType);
		Instant periodEnd = getPeriodEnd(now, periodType);

		return getOrCreateCounter(customerAccountId, ruleId, currency, periodStart, periodEnd);
	}

	/**
	 * Get or create usage counter.
	 *
	 * @param customerAccountId customer account ID
	 * @param ruleId rule ID
	 * @param currency currency code
	 * @param periodStart period start
	 * @param periodEnd period end
	 * @return usage counter
	 */
	private LimitUsageCounter getOrCreateCounter(UUID customerAccountId, UUID ruleId, String currency, Instant periodStart, Instant periodEnd) {
		List<LimitUsageCounter> counters = jdbcTemplate.query(
				"""
				SELECT counter_id, customer_account_id, rule_id, currency, period_start, period_end, used_value, usage_count
				FROM limit_usage_counters
				WHERE customer_account_id = ?
				  AND rule_id = ?
				  AND period_start = ?
				""",
				new RowMapper<LimitUsageCounter>() {
					@Override
					public LimitUsageCounter mapRow(ResultSet rs, int rowNum) throws SQLException {
						return new LimitUsageCounter(
								rs.getLong("counter_id"),
								rs.getObject("customer_account_id", UUID.class),
								rs.getObject("rule_id", UUID.class),
								rs.getString("currency"),
								rs.getTimestamp("period_start").toInstant(),
								rs.getTimestamp("period_end").toInstant(),
								rs.getLong("used_value"),
								rs.getInt("usage_count"));
					}
				},
				customerAccountId,
				ruleId,
				java.sql.Timestamp.from(periodStart));

		if (!counters.isEmpty()) {
			return counters.get(0);
		}

		// Create new counter and get the generated counter_id
		Long counterId = jdbcTemplate.queryForObject(
				"""
				INSERT INTO limit_usage_counters (customer_account_id, rule_id, currency, period_start, period_end, used_value, usage_count)
				VALUES (?, ?, ?, ?, ?, 0, 0)
				RETURNING counter_id
				""",
				Long.class,
				customerAccountId,
				ruleId,
				currency,
				java.sql.Timestamp.from(periodStart),
				java.sql.Timestamp.from(periodEnd));

		return new LimitUsageCounter(counterId, customerAccountId, ruleId, currency, periodStart, periodEnd, 0L, 0);
	}

	/**
	 * Increment amount counter.
	 *
	 * @param counterId counter ID
	 * @param amount amount to add
	 */
	private void incrementAmountCounter(Long counterId, long amount) {
		jdbcTemplate.update(
				"""
				UPDATE limit_usage_counters
				SET used_value = used_value + ?,
				    updated_at = now()
				WHERE counter_id = ?
				""",
				amount,
				counterId);
	}

	/**
	 * Increment count counter.
	 *
	 * @param counterId counter ID
	 */
	private void incrementCountCounter(Long counterId) {
		jdbcTemplate.update(
				"""
				UPDATE limit_usage_counters
				SET usage_count = usage_count + 1,
				    updated_at = now()
				WHERE counter_id = ?
				""",
				counterId);
	}

	/**
	 * Get period start based on period type.
	 *
	 * @param now current time
	 * @param periodType period type
	 * @return period start
	 */
	private Instant getPeriodStart(Instant now, String periodType) {
		return switch (periodType) {
			case "DAILY" -> now.truncatedTo(ChronoUnit.DAYS);
			case "WEEKLY" -> {
				// Start of week (Monday)
				yield now.truncatedTo(ChronoUnit.DAYS)
						.minus(now.atZone(java.time.ZoneOffset.UTC).getDayOfWeek().getValue() - 1, ChronoUnit.DAYS);
			}
			case "MONTHLY" -> {
				// Start of month
				var zdt = now.atZone(java.time.ZoneOffset.UTC);
				yield zdt.withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS).toInstant();
			}
			case "YEARLY" -> {
				// Start of year
				var zdt = now.atZone(java.time.ZoneOffset.UTC);
				yield zdt.withDayOfYear(1).truncatedTo(ChronoUnit.DAYS).toInstant();
			}
			default -> throw new CoreBankException("Unknown period type: " + periodType);
		};
	}

	/**
	 * Get period end based on period type.
	 *
	 * @param now current time
	 * @param periodType period type
	 * @return period end
	 */
	private Instant getPeriodEnd(Instant now, String periodType) {
		return switch (periodType) {
			case "DAILY" -> now.truncatedTo(ChronoUnit.DAYS).plus(1, ChronoUnit.DAYS);
			case "WEEKLY" -> {
				// End of week (Sunday)
				yield now.truncatedTo(ChronoUnit.DAYS)
						.minus(now.atZone(java.time.ZoneOffset.UTC).getDayOfWeek().getValue() - 1, ChronoUnit.DAYS)
						.plus(7, ChronoUnit.DAYS);
			}
			case "MONTHLY" -> {
				// End of month
				var zdt = now.atZone(java.time.ZoneOffset.UTC);
				yield zdt.withDayOfMonth(1).plusMonths(1).truncatedTo(ChronoUnit.DAYS).toInstant();
			}
			case "YEARLY" -> {
				// End of year
				var zdt = now.atZone(java.time.ZoneOffset.UTC);
				yield zdt.withDayOfYear(1).plusYears(1).truncatedTo(ChronoUnit.DAYS).toInstant();
			}
			default -> throw new CoreBankException("Unknown period type: " + periodType);
		};
	}

	// Record definitions

	public record LimitProfile(
			UUID profileId,
			String profileCode,
			String profileName) {
	}

	public record LimitRule(
			UUID ruleId,
			UUID profileId,
			String ruleName,
			String ruleType,
			String limitType,
			long limitValue,
			String currency,
			String periodType) {
	}

	public record LimitUsageCounter(
			Long counterId,
			UUID customerAccountId,
			UUID ruleId,
			String currency,
			Instant periodStart,
			Instant periodEnd,
			long usedValue,
			int usageCount) {
	}
}
