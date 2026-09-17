package com.corebank.corebank_api.ops.iam;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.corebank.corebank_api.TestcontainersConfiguration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.server.ResponseStatusException;

/**
 * {@link IamAuthorizationService} had no test at all despite being the single
 * choke point every ops controller uses for role/permission checks (and, since
 * this review, every payment/transfer/lending/deposit controller too). Covers
 * both resolution paths: Spring-authority fallback for a principal with no
 * {@code iam_staff_users} row (the demo accounts), and the DB-backed
 * role/permission tables for one that has been provisioned.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class IamAuthorizationServiceTest {

	@Autowired
	private IamAuthorizationService iamAuthorizationService;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private Authentication authenticationFor(String username, String... authorities) {
		List<SimpleGrantedAuthority> granted = List.of(authorities).stream()
				.map(SimpleGrantedAuthority::new)
				.toList();
		return new UsernamePasswordAuthenticationToken(username, "n/a", granted);
	}

	@Test
	void unknownUserFallsBackToSpringAuthoritiesForRoleAndPermissionChecks() {
		// "unknown-to-iam" has no iam_staff_users row, so resolution must fall back
		// to the Spring-granted authorities on the token itself (the demo accounts'
		// exact situation — see security/DemoSecurityConfig's in-memory users).
		Authentication authentication = authenticationFor("unknown-to-iam-" + UUID.randomUUID(), "ROLE_OPS");

		assertTrue(iamAuthorizationService.hasRole(authentication, "ROLE_OPS"));
		assertFalse(iamAuthorizationService.hasRole(authentication, "ROLE_ADMIN"));
		// ROLE_OPS's fallback permission set (FALLBACK_ROLE_PERMISSIONS) includes APPROVAL_EXECUTE.
		assertTrue(iamAuthorizationService.hasPermission(authentication, "APPROVAL_EXECUTE"));
		assertFalse(iamAuthorizationService.hasPermission(authentication, "APPROVAL_DECIDE"));
	}

	@Test
	void requireAnyRole_throwsForbidden_whenNoneOfTheRolesMatch() {
		Authentication authentication = authenticationFor("no-roles-" + UUID.randomUUID(), "ROLE_USER");

		assertThrows(
				ResponseStatusException.class,
				() -> iamAuthorizationService.requireAnyRole(authentication, "ROLE_OPS", "ROLE_ADMIN"));
	}

	@Test
	void requireAnyRole_throwsForbidden_whenAuthenticationIsNull() {
		assertThrows(
				ResponseStatusException.class,
				() -> iamAuthorizationService.requireAnyRole(null, "ROLE_USER"));
	}

	@Test
	void provisionedStaffUser_resolvesPermissionsFromDatabase_ignoringSpringAuthorities() {
		String username = "iam-test-maker-" + UUID.randomUUID();
		UUID userId = UUID.randomUUID();
		jdbcTemplate.update(
				"INSERT INTO iam_staff_users (user_id, username, display_name, status) VALUES (?, ?, ?, 'ACTIVE')",
				userId, username, "IAM Test Maker");

		UUID makerRoleId = jdbcTemplate.queryForObject(
				"SELECT role_id FROM iam_roles WHERE role_code = 'ROLE_MAKER'", UUID.class);
		jdbcTemplate.update(
				"INSERT INTO iam_user_roles (user_id, role_id) VALUES (?, ?)", userId, makerRoleId);

		// Deliberately no Spring authorities on the token: if the DB path were not
		// actually consulted, every hasRole/hasPermission check below would fail.
		Authentication authentication = authenticationFor(username);

		assertTrue(iamAuthorizationService.hasRole(authentication, "ROLE_MAKER"));
		assertTrue(iamAuthorizationService.hasPermission(authentication, "APPROVAL_CREATE"));
		// ROLE_MAKER is seeded (V23) with APPROVAL_CREATE only, not APPROVAL_DECIDE.
		assertFalse(iamAuthorizationService.hasPermission(authentication, "APPROVAL_DECIDE"));
	}

	@Test
	void roleAdmin_bypassesGranularPermissionCheck() {
		String username = "iam-test-admin-" + UUID.randomUUID();
		UUID userId = UUID.randomUUID();
		jdbcTemplate.update(
				"INSERT INTO iam_staff_users (user_id, username, display_name, status) VALUES (?, ?, ?, 'ACTIVE')",
				userId, username, "IAM Test Admin");

		UUID adminRoleId = jdbcTemplate.queryForObject(
				"SELECT role_id FROM iam_roles WHERE role_code = 'ROLE_ADMIN'", UUID.class);
		jdbcTemplate.update(
				"INSERT INTO iam_user_roles (user_id, role_id) VALUES (?, ?)", userId, adminRoleId);

		Authentication authentication = authenticationFor(username);

		// A permission code that exists nowhere in iam_permissions — ROLE_ADMIN still
		// passes, because hasPermission short-circuits to true for ROLE_ADMIN.
		assertTrue(iamAuthorizationService.hasPermission(authentication, "SOME_PERMISSION_THAT_DOES_NOT_EXIST"));
	}
}
