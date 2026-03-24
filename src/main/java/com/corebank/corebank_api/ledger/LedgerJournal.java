package com.corebank.corebank_api.ledger;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("ledger_journals")
public class LedgerJournal {

	@Id
	@Column("journal_id")
	private UUID journalId;

	@Column("journal_type")
	private String journalType;

	@Column("reference_type")
	private String referenceType;

	@Column("reference_id")
	private UUID referenceId;

	@Column("currency")
	private String currency;

	@Column("reversal_of_journal_id")
	private UUID reversalOfJournalId;

	@Column("created_by_actor")
	private String createdByActor;

	@Column("correlation_id")
	private UUID correlationId;

	@Column("created_at")
	private Instant createdAt;

	@Column("prev_row_hash")
	private byte[] prevRowHash;

	@Column("row_hash")
	private byte[] rowHash;

	public UUID getJournalId() {
		return journalId;
	}

	public void setJournalId(UUID journalId) {
		this.journalId = journalId;
	}

	public String getJournalType() {
		return journalType;
	}

	public void setJournalType(String journalType) {
		this.journalType = journalType;
	}

	public String getReferenceType() {
		return referenceType;
	}

	public void setReferenceType(String referenceType) {
		this.referenceType = referenceType;
	}

	public UUID getReferenceId() {
		return referenceId;
	}

	public void setReferenceId(UUID referenceId) {
		this.referenceId = referenceId;
	}

	public String getCurrency() {
		return currency;
	}

	public void setCurrency(String currency) {
		this.currency = currency;
	}

	public UUID getReversalOfJournalId() {
		return reversalOfJournalId;
	}

	public void setReversalOfJournalId(UUID reversalOfJournalId) {
		this.reversalOfJournalId = reversalOfJournalId;
	}

	public String getCreatedByActor() {
		return createdByActor;
	}

	public void setCreatedByActor(String createdByActor) {
		this.createdByActor = createdByActor;
	}

	public UUID getCorrelationId() {
		return correlationId;
	}

	public void setCorrelationId(UUID correlationId) {
		this.correlationId = correlationId;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(Instant createdAt) {
		this.createdAt = createdAt;
	}

	public byte[] getPrevRowHash() {
		return prevRowHash;
	}

	public void setPrevRowHash(byte[] prevRowHash) {
		this.prevRowHash = prevRowHash;
	}

	public byte[] getRowHash() {
		return rowHash;
	}

	public void setRowHash(byte[] rowHash) {
		this.rowHash = rowHash;
	}
}