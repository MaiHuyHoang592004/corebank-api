package com.corebank.corebank_api.customer;

import java.time.Instant;
import java.util.UUID;
import lombok.NoArgsConstructor;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;
@Table("customers")
@Data
@NoArgsConstructor
public class Customer {

	@Id
	@Column("customer_id")
	private UUID customerId;

	@Column("customer_type")
	private String customerType;

	@Column("full_name")
	private String fullName;

	@Column("email")
	private String email;

	@Column("phone")
	private String phone;

	@Column("status")
	private String status;

	@Column("risk_band")
	private String riskBand;

	@Column("created_at")
	private Instant createdAt;

	@Column("updated_at")
	private Instant updatedAt;
}
