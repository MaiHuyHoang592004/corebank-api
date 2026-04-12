package com.corebank.corebank_api.deposit;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.CrudRepository;

public interface DepositContractRepository extends CrudRepository<DepositContract, UUID> {

	List<DepositContract> findByCustomerAccountId(UUID customerAccountId);

	List<DepositContract> findByStatus(String status);

	List<DepositContract> findByMaturityDate(LocalDate maturityDate);

	List<DepositContract> findByMaturityDateAndStatus(LocalDate maturityDate, String status);

	Optional<DepositContract> findByContractIdAndCustomerAccountId(UUID contractId, UUID customerAccountId);
}