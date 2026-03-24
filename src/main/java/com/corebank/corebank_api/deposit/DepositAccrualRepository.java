package com.corebank.corebank_api.deposit;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.CrudRepository;

public interface DepositAccrualRepository extends CrudRepository<DepositAccrual, Long> {

	List<DepositAccrual> findByContractIdOrderByAccrualDateDesc(UUID contractId);

	List<DepositAccrual> findByContractIdAndAccrualDateGreaterThanEqualOrderByAccrualDateAsc(
			UUID contractId, LocalDate startDate);

	Optional<DepositAccrual> findByContractIdAndAccrualDate(UUID contractId, LocalDate accrualDate);

	List<DepositAccrual> findByAccrualDate(LocalDate accrualDate);
}