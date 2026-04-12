package com.corebank.corebank_api.account;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("customer_accounts")
public class CustomerAccount {

	@Id
	@Column("customer_account_id")
	private UUID customerAccountId;

	@Column("customer_id")
	private UUID customerId;

	@Column("product_id")
	private UUID productId;

	@Column("account_number")
	private String accountNumber;

	@Column("currency")
	private String currency;

	@Column("status")
	private String status;

	@Column("posted_balance_minor")
	private long postedBalanceMinor;

	@Column("available_balance_minor")
	private long availableBalanceMinor;

	@Column("version")
	private long version;

	@Column("created_at")
	private Instant createdAt;

	@Column("updated_at")
	private Instant updatedAt;

	public CustomerAccount() {
	}

	public UUID getCustomerAccountId() {
		return customerAccountId;
	}

	public void setCustomerAccountId(UUID customerAccountId) {
		this.customerAccountId = customerAccountId;
	}

	public UUID getCustomerId() {
		return customerId;
	}

	public void setCustomerId(UUID customerId) {
		this.customerId = customerId;
	}

	public UUID getProductId() {
		return productId;
	}

	public void setProductId(UUID productId) {
		this.productId = productId;
	}

	public String getAccountNumber() {
		return accountNumber;
	}

	public void setAccountNumber(String accountNumber) {
		this.accountNumber = accountNumber;
	}

	public String getCurrency() {
		return currency;
	}

	public void setCurrency(String currency) {
		this.currency = currency;
	}

	public String getStatus() {
		return status;
	}

	public void setStatus(String status) {
		this.status = status;
	}

	public long getPostedBalanceMinor() {
		return postedBalanceMinor;
	}

	public void setPostedBalanceMinor(long postedBalanceMinor) {
		this.postedBalanceMinor = postedBalanceMinor;
	}

	public long getAvailableBalanceMinor() {
		return availableBalanceMinor;
	}

	public void setAvailableBalanceMinor(long availableBalanceMinor) {
		this.availableBalanceMinor = availableBalanceMinor;
	}

	public long getVersion() {
		return version;
	}

	public void setVersion(long version) {
		this.version = version;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(Instant createdAt) {
		this.createdAt = createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

	public void setUpdatedAt(Instant updatedAt) {
		this.updatedAt = updatedAt;
	}
}