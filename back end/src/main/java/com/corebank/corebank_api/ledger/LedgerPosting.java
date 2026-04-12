package com.corebank.corebank_api.ledger;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("ledger_postings")
public class LedgerPosting {

	@Id
	@Column("posting_id")
	private Long postingId;

	@Column("journal_id")
	private UUID journalId;

	@Column("ledger_account_id")
	private UUID ledgerAccountId;

	@Column("customer_account_id")
	private UUID customerAccountId;

	@Column("entry_side")
	private String entrySide;

	@Column("amount_minor")
	private long amountMinor;

	@Column("currency")
	private String currency;

	@Column("created_at")
	private Instant createdAt;

	public Long getPostingId() {
		return postingId;
	}

	public void setPostingId(Long postingId) {
		this.postingId = postingId;
	}

	public UUID getJournalId() {
		return journalId;
	}

	public void setJournalId(UUID journalId) {
		this.journalId = journalId;
	}

	public UUID getLedgerAccountId() {
		return ledgerAccountId;
	}

	public void setLedgerAccountId(UUID ledgerAccountId) {
		this.ledgerAccountId = ledgerAccountId;
	}

	public UUID getCustomerAccountId() {
		return customerAccountId;
	}

	public void setCustomerAccountId(UUID customerAccountId) {
		this.customerAccountId = customerAccountId;
	}

	public String getEntrySide() {
		return entrySide;
	}

	public void setEntrySide(String entrySide) {
		this.entrySide = entrySide;
	}

	public long getAmountMinor() {
		return amountMinor;
	}

	public void setAmountMinor(long amountMinor) {
		this.amountMinor = amountMinor;
	}

	public String getCurrency() {
		return currency;
	}

	public void setCurrency(String currency) {
		this.currency = currency;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(Instant createdAt) {
		this.createdAt = createdAt;
	}
}