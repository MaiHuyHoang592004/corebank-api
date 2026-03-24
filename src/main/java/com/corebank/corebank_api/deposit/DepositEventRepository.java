package com.corebank.corebank_api.deposit;

import java.util.List;
import java.util.UUID;
import org.springframework.data.repository.CrudRepository;

public interface DepositEventRepository extends CrudRepository<DepositEvent, Long> {

	List<DepositEvent> findByContractIdOrderByCreatedAtAsc(UUID contractId);

	List<DepositEvent> findByContractIdAndEventTypeOrderByCreatedAtAsc(UUID contractId, String eventType);
}