package com.corebank.corebank_api.deposit;

import java.time.LocalDate;
import java.util.UUID;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Data
@Table("deposit_contracts")
public class DepositContract {

	@Id
	@Column("contract_id")
	private UUID contractId;

	@Column("customer_account_id")
	private UUID customerAccountId;

	@Column("product_id")
	private UUID productId;

	@Column("product_version_id")
	private UUID productVersionId;

	@Column("principal_amount")
	private long principalAmount;

	@Column("currency")
	private String currency;

	@Column("interest_rate")
	private double interestRate;

	@Column("term_months")
	private int termMonths;

	@Column("start_date")
	private LocalDate startDate;

	@Column("maturity_date")
	private LocalDate maturityDate;

	@Column("status")
	private String status;

	@Column("early_closure_penalty_rate")
	private double earlyClosurePenaltyRate;

	@Column("auto_renew")
	private boolean autoRenew;

	@Column("created_at")
	private java.time.Instant createdAt;

	@Column("updated_at")
	private java.time.Instant updatedAt;
}