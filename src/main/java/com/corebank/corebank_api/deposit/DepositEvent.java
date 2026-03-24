package com.corebank.corebank_api.deposit;

import java.util.UUID;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Data
@Table("deposit_events")
public class DepositEvent {

	@Id
	@Column("event_id")
	private Long eventId;

	@Column("contract_id")
	private UUID contractId;

	@Column("event_type")
	private String eventType;

	@Column("amount_minor")
	private Long amountMinor;

	@Column("metadata_json")
	private String metadataJson;

	@Column("created_at")
	private java.time.Instant createdAt;
}