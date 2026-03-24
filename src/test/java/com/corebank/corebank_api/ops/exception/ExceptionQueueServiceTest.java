package com.corebank.corebank_api.ops.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.corebank.corebank_api.TestcontainersConfiguration;
import com.corebank.corebank_api.ops.exception.ExceptionQueueService.ExceptionRecord;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ExceptionQueueServiceTest {

	@Autowired
	private ExceptionQueueService exceptionQueueService;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void setUp() {
		// Clean up exception_queue table before each test
		jdbcTemplate.update("DELETE FROM exception_queue");
	}

	@Test
	void addExceptionCreatesNewException() {
		long exceptionId = exceptionQueueService.addException(
				"PAYMENT_FAILED",
				"Payment gateway timeout",
				"Connection timeout after 30 seconds",
				"PaymentService",
				"processPayment",
				"{\"orderId\": 12345, \"amount\": 1000}");

		assertNotNull(exceptionId);
		assertTrue(exceptionId > 0);

		// Verify exception was created
		Optional<ExceptionRecord> exception = exceptionQueueService.getException(exceptionId);
		assertTrue(exception.isPresent());
		assertEquals("PAYMENT_FAILED", exception.get().exceptionType());
		assertEquals("PENDING", exception.get().status());
		assertEquals(0, exception.get().retryCount());
	}

	@Test
	void getExceptionsByStatusReturnsPendingExceptions() {
		// Add multiple exceptions
		exceptionQueueService.addException("PAYMENT_FAILED", "Error 1", "Detail 1", "Service1", "Op1", "{}");
		exceptionQueueService.addException("TRANSFER_FAILED", "Error 2", "Detail 2", "Service2", "Op2", "{}");
		exceptionQueueService.addException("PAYMENT_FAILED", "Error 3", "Detail 3", "Service1", "Op1", "{}");

		// Get pending exceptions
		List<ExceptionRecord> pendingExceptions = exceptionQueueService.getExceptionsByStatus("PENDING", 10);

		assertEquals(3, pendingExceptions.size());
	}

	@Test
	void getRetryableExceptionsReturnsOnlyRetryable() {
		// Add exceptions with different retry counts
		long id1 = exceptionQueueService.addException("PAYMENT_FAILED", "Error 1", "Detail 1", "Service1", "Op1", "{}");
		long id2 = exceptionQueueService.addException("TRANSFER_FAILED", "Error 2", "Detail 2", "Service2", "Op2", "{}");

		// Increment retry count for one exception
		exceptionQueueService.incrementRetryCount(id1);
		exceptionQueueService.incrementRetryCount(id1);
		exceptionQueueService.incrementRetryCount(id1); // Now retry_count = 3, max_retries = 3

		// Get retryable exceptions (should only return id2)
		List<ExceptionRecord> retryableExceptions = exceptionQueueService.getRetryableExceptions(10);

		assertEquals(1, retryableExceptions.size());
		assertEquals(id2, retryableExceptions.get(0).exceptionId());
	}

	@Test
	void markInProgressChangesStatus() {
		long exceptionId = exceptionQueueService.addException("PAYMENT_FAILED", "Error", "Detail", "Service", "Op", "{}");

		exceptionQueueService.markInProgress(exceptionId);

		Optional<ExceptionRecord> exception = exceptionQueueService.getException(exceptionId);
		assertTrue(exception.isPresent());
		assertEquals("IN_PROGRESS", exception.get().status());
	}

	@Test
	void markResolvedChangesStatusAndStoresResolution() {
		long exceptionId = exceptionQueueService.addException("PAYMENT_FAILED", "Error", "Detail", "Service", "Op", "{}");

		exceptionQueueService.markResolved(exceptionId, "operator1", "Fixed manually");

		Optional<ExceptionRecord> exception = exceptionQueueService.getException(exceptionId);
		assertTrue(exception.isPresent());
		assertEquals("RESOLVED", exception.get().status());
		assertEquals("operator1", exception.get().resolvedBy());
		assertEquals("Fixed manually", exception.get().resolutionNote());
	}

	@Test
	void markIgnoredChangesStatus() {
		long exceptionId = exceptionQueueService.addException("PAYMENT_FAILED", "Error", "Detail", "Service", "Op", "{}");

		exceptionQueueService.markIgnored(exceptionId, "operator1", "Not critical");

		Optional<ExceptionRecord> exception = exceptionQueueService.getException(exceptionId);
		assertTrue(exception.isPresent());
		assertEquals("IGNORED", exception.get().status());
	}

	@Test
	void incrementRetryCountIncrementsCount() {
		long exceptionId = exceptionQueueService.addException("PAYMENT_FAILED", "Error", "Detail", "Service", "Op", "{}");

		exceptionQueueService.incrementRetryCount(exceptionId);
		exceptionQueueService.incrementRetryCount(exceptionId);

		Optional<ExceptionRecord> exception = exceptionQueueService.getException(exceptionId);
		assertTrue(exception.isPresent());
		assertEquals(2, exception.get().retryCount());
	}

	@Test
	void getExceptionCountByStatusReturnsCorrectCount() {
		exceptionQueueService.addException("PAYMENT_FAILED", "Error 1", "Detail 1", "Service1", "Op1", "{}");
		exceptionQueueService.addException("TRANSFER_FAILED", "Error 2", "Detail 2", "Service2", "Op2", "{}");

		long id3 = exceptionQueueService.addException("PAYMENT_FAILED", "Error 3", "Detail 3", "Service1", "Op1", "{}");
		exceptionQueueService.markResolved(id3, "operator1", "Fixed");

		assertEquals(2, exceptionQueueService.getExceptionCountByStatus("PENDING"));
		assertEquals(1, exceptionQueueService.getExceptionCountByStatus("RESOLVED"));
	}
}