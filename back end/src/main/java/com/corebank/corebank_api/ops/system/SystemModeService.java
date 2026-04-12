package com.corebank.corebank_api.ops.system;

import com.corebank.corebank_api.common.CoreBankException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

/**
 * Service to check and control system runtime mode.
 *
 * <p>System modes:
 * <ul>
 *   <li>RUNNING - normal operations</li>
 *   <li>EOD_LOCK - end of day processing, restrict writes</li>
 *   <li>MAINTENANCE - system maintenance, restrict writes</li>
 *   <li>READ_ONLY - read only mode</li>
 * </ul>
 * </p>
 */
@Service
public class SystemModeService {

	private final JdbcTemplate jdbcTemplate;

	public SystemModeService(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	/**
	 * Get current system runtime mode.
	 *
	 * @return current system mode
	 */
	public SystemMode getCurrentMode() {
		Optional<String> modeValue = jdbcTemplate.query(
				"""
				SELECT config_value->>'status' as status
				FROM system_configs
				WHERE config_key = 'runtime_mode'
				""",
				rs -> rs.next() ? Optional.of(rs.getString("status")) : Optional.empty());

		if (modeValue.isEmpty()) {
			// Default to RUNNING if config not found
			return SystemMode.RUNNING;
		}

		try {
			return SystemMode.valueOf(modeValue.get());
		} catch (IllegalArgumentException ex) {
			// Default to RUNNING if invalid value
			return SystemMode.RUNNING;
		}
	}

	/**
	 * Check if system is in running mode.
	 *
	 * @return true if system is running
	 */
	public boolean isRunning() {
		return getCurrentMode() == SystemMode.RUNNING;
	}

	/**
	 * Check if writes are allowed.
	 *
	 * @return true if writes are allowed
	 */
	public boolean isWriteAllowed() {
		return getCurrentMode() == SystemMode.RUNNING;
	}

	/**
	 * Check if reads are allowed.
	 *
	 * @return true if reads are allowed
	 */
	public boolean isReadAllowed() {
		SystemMode mode = getCurrentMode();
		return mode == SystemMode.RUNNING || mode == SystemMode.READ_ONLY;
	}

	/**
	 * Enforce that writes are allowed.
	 *
	 * @throws CoreBankException if writes are not allowed
	 */
	public void enforceWriteAllowed() {
		if (!isWriteAllowed()) {
			throw new CoreBankException(
					"System is in " + getCurrentMode() + " mode. Writes are not allowed.");
		}
	}

	/**
	 * Enforce that reads are allowed.
	 *
	 * @throws CoreBankException if reads are not allowed
	 */
	public void enforceReadAllowed() {
		if (!isReadAllowed()) {
			throw new CoreBankException(
					"System is in " + getCurrentMode() + " mode. Reads are not allowed.");
		}
	}

	/**
	 * Set system mode.
	 *
	 * @param mode new system mode
	 * @param operator operator who changed the mode
	 */
	public void setMode(SystemMode mode, String operator) {
		jdbcTemplate.update(
				"""
				UPDATE system_configs
				SET config_value = jsonb_set(config_value, '{status}', to_jsonb(?::text)),
				    updated_at = now(),
				    updated_by = ?
				WHERE config_key = 'runtime_mode'
				""",
				mode.name(),
				operator);
	}

	/**
	 * Get business date.
	 *
	 * @return business date or null if not set
	 */
	public String getBusinessDate() {
		return jdbcTemplate.query(
				"""
				SELECT config_value->>'business_date' as business_date
				FROM system_configs
				WHERE config_key = 'eod_control'
				""",
				rs -> rs.next() ? rs.getString("business_date") : null);
	}

	/**
	 * Set business date.
	 *
	 * @param businessDate business date in ISO format (yyyy-MM-dd)
	 * @param operator operator who set the business date
	 */
	public void setBusinessDate(String businessDate, String operator) {
		jdbcTemplate.update(
				"""
				UPDATE system_configs
				SET config_value = jsonb_set(config_value, '{business_date}', to_jsonb(?::text)),
				    updated_at = now(),
				    updated_by = ?
				WHERE config_key = 'eod_control'
				""",
				businessDate,
				operator);
	}

	/**
	 * Check if EOD is open.
	 *
	 * @return true if EOD is open
	 */
	public boolean isEodOpen() {
		Boolean isOpen = jdbcTemplate.query(
				"""
				SELECT (config_value->>'is_open')::boolean as is_open
				FROM system_configs
				WHERE config_key = 'eod_control'
				""",
				rs -> rs.next() ? rs.getBoolean("is_open") : null);

		return isOpen != null && isOpen;
	}

	/**
	 * Set EOD open status.
	 *
	 * @param open EOD open status
	 * @param operator operator who changed the status
	 */
	public void setEodOpen(boolean open, String operator) {
		jdbcTemplate.update(
				"""
				UPDATE system_configs
				SET config_value = jsonb_set(config_value, '{is_open}', to_jsonb(?::boolean)),
				    updated_at = now(),
				    updated_by = ?
				WHERE config_key = 'eod_control'
				""",
				open,
				operator);
	}

	/**
	 * System runtime modes.
	 */
	public enum SystemMode {
		RUNNING,
		EOD_LOCK,
		MAINTENANCE,
		READ_ONLY
	}
}