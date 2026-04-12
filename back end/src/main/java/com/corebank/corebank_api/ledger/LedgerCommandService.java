package com.corebank.corebank_api.ledger;

import com.corebank.corebank_api.account.AccountBalanceRepository;
import com.corebank.corebank_api.account.CustomerAccount;
import com.corebank.corebank_api.common.CoreBankException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LedgerCommandService {

	private final JdbcTemplate jdbcTemplate;
	private final AccountBalanceRepository accountBalanceRepository;
	private final HotAccountSlotRuntimeService hotAccountSlotRuntimeService;

	public LedgerCommandService(
			JdbcTemplate jdbcTemplate,
			AccountBalanceRepository accountBalanceRepository,
			HotAccountSlotRuntimeService hotAccountSlotRuntimeService) {
		this.jdbcTemplate = jdbcTemplate;
		this.accountBalanceRepository = accountBalanceRepository;
		this.hotAccountSlotRuntimeService = hotAccountSlotRuntimeService;
	}

	@Transactional
	public UUID postJournal(PostJournalCommand command) {
		validateBalancedJournal(command.postings());

		List<UUID> customerAccountIds = command.postings().stream()
				.map(PostingInstruction::customerAccountId)
				.filter(Objects::nonNull)
				.distinct()
				.sorted(Comparator.naturalOrder())
				.toList();

		Map<UUID, CustomerAccount> lockedAccounts = new LinkedHashMap<>();
		if (!customerAccountIds.isEmpty()) {
			for (CustomerAccount account : accountBalanceRepository.lockByIdsInDeterministicOrder(customerAccountIds)) {
				lockedAccounts.put(account.getCustomerAccountId(), account);
			}

			if (lockedAccounts.size() != customerAccountIds.size()) {
				throw new CoreBankException("Unable to lock all customer accounts required for ledger posting");
			}
		}

		Map<UUID, HotAccountSlotRuntimeService.HotAccountContext> hotAccountContexts =
				hotAccountSlotRuntimeService.lockActiveContextsInDeterministicOrder(command.postings().stream()
						.map(PostingInstruction::ledgerAccountId)
						.filter(Objects::nonNull)
						.toList());
		List<HotAccountSlotRuntimeService.SlottingDecision> slottingDecisions = new ArrayList<>();

		UUID journalId = UUID.randomUUID();
		byte[] prevRowHash = findLatestJournalRowHash().orElse(null);
		byte[] rowHash = buildJournalRowHash(command, journalId, prevRowHash);

		jdbcTemplate.update(
				"""
				INSERT INTO ledger_journals (
				    journal_id,
				    journal_type,
				    reference_type,
				    reference_id,
				    currency,
				    reversal_of_journal_id,
				    created_by_actor,
				    correlation_id,
				    prev_row_hash,
				    row_hash
				) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				""",
				journalId,
				command.journalType(),
				command.referenceType(),
				command.referenceId(),
				command.currency(),
				command.reversalOfJournalId(),
				command.createdByActor(),
				command.correlationId(),
				prevRowHash,
				rowHash);

		for (PostingInstruction posting : command.postings()) {
			jdbcTemplate.update(
					"""
					INSERT INTO ledger_postings (
					    journal_id,
					    ledger_account_id,
					    customer_account_id,
					    entry_side,
					    amount_minor,
					    currency
					) VALUES (?, ?, ?, ?, ?, ?)
					""",
					journalId,
					posting.ledgerAccountId(),
					posting.customerAccountId(),
					posting.entrySide(),
					posting.amountMinor(),
					posting.currency());

			HotAccountSlotRuntimeService.HotAccountContext hotAccountContext =
					hotAccountContexts.get(posting.ledgerAccountId());
			if (hotAccountContext != null) {
				slottingDecisions.add(hotAccountSlotRuntimeService.applySlotDelta(
						hotAccountContext,
						command.referenceId(),
						posting.entrySide(),
						posting.amountMinor()));
			}

			// Only update posted balance if explicitly requested (e.g., for captures, not for transfers)
			if (posting.customerAccountId() != null && posting.updatePostedBalance()) {
				long delta = posting.entrySide().equals("C") ? posting.amountMinor() : -posting.amountMinor();
				jdbcTemplate.update(
						"""
						UPDATE customer_accounts
						SET posted_balance_minor = posted_balance_minor + ?,
						    version = version + 1,
						    updated_at = now()
						WHERE customer_account_id = ?
				""",
						delta,
						posting.customerAccountId());
			}
		}

		hotAccountSlotRuntimeService.appendSlottingAudit(journalId, command, slottingDecisions);
		return journalId;
	}

	private void validateBalancedJournal(List<PostingInstruction> postings) {
		if (postings == null || postings.isEmpty()) {
			throw new CoreBankException("Ledger journal must contain at least one posting");
		}

		long totalDebit = 0L;
		long totalCredit = 0L;

		for (PostingInstruction posting : postings) {
			if (posting.amountMinor() <= 0) {
				throw new CoreBankException("Ledger posting amount must be positive");
			}

			if (!"D".equals(posting.entrySide()) && !"C".equals(posting.entrySide())) {
				throw new CoreBankException("Ledger posting side must be D or C");
			}

			if (!Objects.equals(posting.currency(), postings.get(0).currency())) {
				throw new CoreBankException("All ledger postings in a journal must use the same currency");
			}

			if ("D".equals(posting.entrySide())) {
				totalDebit += posting.amountMinor();
			} else {
				totalCredit += posting.amountMinor();
			}
		}

		if (totalDebit != totalCredit) {
			throw new CoreBankException("Ledger journal is not balanced");
		}
	}

	private Optional<byte[]> findLatestJournalRowHash() {
		List<byte[]> hashes = jdbcTemplate.query(
				"""
				SELECT row_hash
				FROM ledger_journals
				ORDER BY created_at DESC, journal_id DESC
				LIMIT 1
				""",
				(rs, rowNum) -> rs.getBytes("row_hash"));

		return hashes.stream().findFirst();
	}

	private byte[] buildJournalRowHash(PostJournalCommand command, UUID journalId, byte[] prevRowHash) {
		List<String> postingParts = new ArrayList<>();
		for (PostingInstruction posting : command.postings()) {
			postingParts.add(
					posting.ledgerAccountId() + "|"
							+ posting.customerAccountId() + "|"
							+ posting.entrySide() + "|"
							+ posting.amountMinor() + "|"
							+ posting.currency());
		}

		String payload = String.join(
				"||",
				journalId.toString(),
				command.journalType(),
				command.referenceType(),
				command.referenceId().toString(),
				command.currency(),
				String.valueOf(command.reversalOfJournalId()),
				String.valueOf(command.createdByActor()),
				String.valueOf(command.correlationId()),
				Base64.getEncoder().encodeToString(prevRowHash == null ? new byte[0] : prevRowHash),
				String.join("##", postingParts));

		return sha256(payload);
	}

	private byte[] sha256(String value) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return digest.digest(value.getBytes(StandardCharsets.UTF_8));
		} catch (NoSuchAlgorithmException ex) {
			throw new CoreBankException("SHA-256 algorithm is unavailable", ex);
		}
	}

	public record PostJournalCommand(
			String journalType,
			String referenceType,
			UUID referenceId,
			String currency,
			UUID reversalOfJournalId,
			String createdByActor,
			UUID correlationId,
			List<PostingInstruction> postings) {
	}

	public record PostingInstruction(
			UUID ledgerAccountId,
			UUID customerAccountId,
			String entrySide,
			long amountMinor,
			String currency,
			boolean updatePostedBalance) {
	}
}
