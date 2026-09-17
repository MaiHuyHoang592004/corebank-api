package com.corebank.corebank_api.customer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.corebank.corebank_api.TestcontainersConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** Was previously untested. Thin as the repository itself, but closes the gap
 * for the entity the whole system's identity concept is built on. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class CustomerRepositoryTest {

	@Autowired
	private CustomerRepository customerRepository;

	private Customer newCustomer() {
		Customer customer = new Customer();
		customer.setCustomerId(UUID.randomUUID());
		customer.setCustomerType("INDIVIDUAL");
		customer.setFullName("Repository Test Customer");
		customer.setEmail("repo-test-" + UUID.randomUUID() + "@example.com");
		customer.setPhone("0900000003");
		customer.setStatus("ACTIVE");
		customer.setRiskBand("LOW");
		return customer;
	}

	@Test
	void save_thenFindById_roundTripsEveryField() {
		Customer saved = customerRepository.save(newCustomer());

		Customer found = customerRepository.findById(saved.getCustomerId()).orElseThrow();

		assertEquals(saved.getCustomerId(), found.getCustomerId());
		assertEquals("INDIVIDUAL", found.getCustomerType());
		assertEquals("Repository Test Customer", found.getFullName());
		assertEquals(saved.getEmail(), found.getEmail());
		assertEquals("0900000003", found.getPhone());
		assertEquals("ACTIVE", found.getStatus());
		assertEquals("LOW", found.getRiskBand());
	}

	@Test
	void save_onExistingId_updatesRatherThanDuplicates() {
		Customer saved = customerRepository.save(newCustomer());

		saved.setStatus("INACTIVE");
		saved.setRiskBand("HIGH");
		customerRepository.save(saved);

		Customer updated = customerRepository.findById(saved.getCustomerId()).orElseThrow();
		assertEquals("INACTIVE", updated.getStatus());
		assertEquals("HIGH", updated.getRiskBand());
	}

	@Test
	void findById_returnsEmpty_whenCustomerDoesNotExist() {
		assertFalse(customerRepository.findById(UUID.randomUUID()).isPresent());
	}

	@Test
	void existsById_reflectsPersistedState() {
		Customer saved = customerRepository.save(newCustomer());
		assertTrue(customerRepository.existsById(saved.getCustomerId()));
		assertFalse(customerRepository.existsById(UUID.randomUUID()));
	}
}
