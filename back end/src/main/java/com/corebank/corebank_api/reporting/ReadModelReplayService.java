package com.corebank.corebank_api.reporting;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Replays persisted outbox events into the non-authoritative read model.
 */
@Service
public class ReadModelReplayService {

	private final JdbcTemplate jdbcTemplate;
	private final ReadModelProjector readModelProjector;

	public ReadModelReplayService(JdbcTemplate jdbcTemplate, ReadModelProjector readModelProjector) {
		this.jdbcTemplate = jdbcTemplate;
		this.readModelProjector = readModelProjector;
	}

	@Transactional
	public int replayFromOutbox() {
		List<Map<String, Object>> rows = jdbcTemplate.queryForList(
				"""
				SELECT event_data::text AS event_data
				FROM outbox_events
				ORDER BY id ASC
				""");

		int projectedCount = 0;
		for (Map<String, Object> row : rows) {
			projectedCount += readModelProjector.projectReplayedEvent(String.valueOf(row.get("event_data")));
		}
		return projectedCount;
	}

	@Transactional
	public int rebuildFromOutbox() {
		jdbcTemplate.update("DELETE FROM read_model_aggregate_activity");
		jdbcTemplate.update("DELETE FROM read_model_event_feed");
		return replayFromOutbox();
	}
}
