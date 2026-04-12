package com.corebank.corebank_api.reporting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.corebank.corebank_api.TestcontainersConfiguration;
import com.corebank.corebank_api.reporting.SnapshotService.BalanceSnapshot;
import com.corebank.corebank_api.reporting.SnapshotService.SnapshotComparison;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class SnapshotServiceTest {

	@Autowired
	private SnapshotService snapshotService;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private UUID testAccountId;

	@BeforeEach
	void setUp() {
		// Clean up snapshot table
		jdbcTemplate.update("DELETE FROM account_balance_snapshots");

		// Create test account
		testAccountId = UUID.randomUUID();
		UUID customerId = UUID.randomUUID();
		UUID productId = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567801");

		jdbcTemplate.update(
				"""
				INSERT INTO customers (customer_id, customer_type, full_name, email, phone, status, risk_band)
				VALUES (?, 'INDIVIDUAL', 'Test Customer', 'test@example.com', '1234567890', 'ACTIVE', 'LOW')
				""",
				customerId);

		jdbcTemplate.update(
				"""
				INSERT INTO customer_accounts (customer_account_id, customer_id, product_id, account_number, currency, status, posted_balance_minor, available_balance_minor, version)
				VALUES (?, ?, ?, ?, 'VND', 'ACTIVE', 10000000000, 8000000000, 0)
				""",
				testAccountId,
				customerId,
				productId,
				"TEST-" + testAccountId.toString().substring(0, 8));
	}

	@Test
	void createDailySnapshotCreatesSnapshot() {
		LocalDate snapshotDate = LocalDate.of(2026, 3, 23);

		int count = snapshotService.createDailySnapshot(snapshotDate);

		assertTrue(count > 0);

		// Verify snapshot was created
		Optional<BalanceSnapshot> snapshot = snapshotService.getSnapshot(testAccountId, snapshotDate);
		assertTrue(snapshot.isPresent());
		assertEquals(10000000000L, snapshot.get().postedBalance());
		assertEquals(8000000000L, snapshot.get().availableBalance());
	}

	@Test
	void getSnapshotsByDateReturnsCorrectSnapshots() {
		LocalDate snapshotDate = LocalDate.of(2026, 3, 23);

		snapshotService.createDailySnapshot(snapshotDate);

		List<BalanceSnapshot> snapshots = snapshotService.getSnapshotsByDate(snapshotDate, 10);

		assertTrue(snapshots.size() > 0);
	}

	@Test
	void getAccountSnapshotsReturnsAccountHistory() {
		LocalDate date1 = LocalDate.of(2026, 3, 22);
		LocalDate date2 = LocalDate.of(2026, 3, 23);

		snapshotService.createDailySnapshot(date1);
		snapshotService.createDailySnapshot(date2);

		List<BalanceSnapshot> snapshots = snapshotService.getAccountSnapshots(testAccountId, 10);

		assertEquals(2, snapshots.size());
	}

	@Test
	void compareSnapshotsDetectsChanges() {
		LocalDate date1 = LocalDate.of(2026, 3, 22);
		LocalDate date2 = LocalDate.of(2026, 3, 23);

		snapshotService.createDailySnapshot(date1);
		snapshotService.createDailySnapshot(date2);

		Optional<SnapshotComparison> comparison = snapshotService.compareSnapshots(testAccountId, date1, date2);

		assertTrue(comparison.isPresent());
		assertEquals(0L, comparison.get().postedBalanceChange()); // No change
		assertEquals(0L, comparison.get().availableBalanceChange()); // No change
	}

	@Test
	void duplicateSnapshotIsIgnored() {
		LocalDate snapshotDate = LocalDate.of(2026, 3, 23);

		// Create snapshot twice
		snapshotService.createDailySnapshot(snapshotDate);
		int count = snapshotService.createDailySnapshot(snapshotDate);

		// Should not create duplicate
		assertEquals(0, count);

		// Verify only one snapshot exists
		List<BalanceSnapshot> snapshots = snapshotService.getAccountSnapshots(testAccountId, 10);
		assertEquals(1, snapshots.size());
	}
}