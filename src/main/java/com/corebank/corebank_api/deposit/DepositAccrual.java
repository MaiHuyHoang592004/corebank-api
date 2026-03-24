package com.corebank.corebank_api.deposit;

import java.time.LocalDate;
import java.util.UUID;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Data
@Table("deposit_accruals")
public class DepositAccrual {

	@Id
	@Column("accrual_id")
	private Long accrualId;

	@Column("contract_id")
	private UUID contractId;

	@Column("accrual_date")
	private LocalDate accrualDate;

	@Column("accrued_interest")
	private long accruedInterest;

	@Column("running_balance")
	private long runningBalance;

	@Column("created_at")
	private java.time.Instant createdAt;
}