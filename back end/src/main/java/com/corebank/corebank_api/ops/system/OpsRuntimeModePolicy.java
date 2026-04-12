package com.corebank.corebank_api.ops.system;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class OpsRuntimeModePolicy {

	private final SystemModeService systemModeService;

	public OpsRuntimeModePolicy(SystemModeService systemModeService) {
		this.systemModeService = systemModeService;
	}

	public void requireRunningForMoneyImpactWrite() {
		SystemModeService.SystemMode mode = systemModeService.getCurrentMode();
		if (mode != SystemModeService.SystemMode.RUNNING) {
			throw new ResponseStatusException(
					HttpStatus.CONFLICT,
					"Money-impact operation requires RUNNING mode. Current mode: " + mode);
		}
	}

	public void requireNonRunningForMaintenanceJob() {
		SystemModeService.SystemMode mode = systemModeService.getCurrentMode();
		if (mode == SystemModeService.SystemMode.RUNNING) {
			throw new ResponseStatusException(
					HttpStatus.CONFLICT,
					"Maintenance operation requires non-RUNNING mode. Current mode: " + mode);
		}
	}
}
