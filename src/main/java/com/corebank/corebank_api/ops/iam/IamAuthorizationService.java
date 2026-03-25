package com.corebank.corebank_api.ops.iam;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class IamAuthorizationService {

	private static final String ROLE_ADMIN = "ROLE_ADMIN";

	private static final Map<String, Set<String>> FALLBACK_ROLE_PERMISSIONS = Map.of(
			"ROLE_ADMIN", Set.of("*"),
			"ROLE_MAKER", Set.of("APPROVAL_CREATE"),
			"ROLE_APPROVER", Set.of("APPROVAL_DECIDE"),
			"ROLE_OPS", Set.of("APPROVAL_EXECUTE", "PRODUCT_GOVERNANCE_READ", "PRODUCT_GOVERNANCE_WRITE"));

	private final JdbcTemplate jdbcTemplate;

	public IamAuthorizationService(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public void requireAnyRole(Authentication authentication, String... roleCodes) {
		requireAuthenticated(authentication);
		if (!hasAnyRole(authentication, Arrays.asList(roleCodes))) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Insufficient authority");
		}
	}

	public void requirePermission(Authentication authentication, String permissionCode) {
		requireAuthenticated(authentication);
		if (!hasPermission(authentication, permissionCode)) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Insufficient permission");
		}
	}

	public boolean hasRole(Authentication authentication, String roleCode) {
		if (authentication == null) {
			return false;
		}
		return resolveRoles(authentication).contains(roleCode);
	}

	public boolean hasAnyRole(Authentication authentication, Collection<String> roleCodes) {
		if (authentication == null) {
			return false;
		}

		Set<String> resolvedRoles = resolveRoles(authentication);
		return roleCodes.stream().anyMatch(resolvedRoles::contains);
	}

	public boolean hasPermission(Authentication authentication, String permissionCode) {
		if (authentication == null) {
			return false;
		}

		Set<String> resolvedRoles = resolveRoles(authentication);
		if (resolvedRoles.contains(ROLE_ADMIN)) {
			return true;
		}

		Set<String> permissions = resolvePermissions(authentication, resolvedRoles);
		return permissions.contains("*") || permissions.contains(permissionCode);
	}

	private void requireAuthenticated(Authentication authentication) {
		if (authentication == null) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Missing authentication");
		}
	}

	private Set<String> resolveRoles(Authentication authentication) {
		String username = authentication.getName();
		Set<String> dbRoles = loadDbRoles(username);
		if (!dbRoles.isEmpty()) {
			return dbRoles;
		}

		return authentication.getAuthorities().stream()
				.map(GrantedAuthority::getAuthority)
				.collect(Collectors.toCollection(LinkedHashSet::new));
	}

	private Set<String> resolvePermissions(Authentication authentication, Set<String> resolvedRoles) {
		String username = authentication.getName();
		Set<String> dbPermissions = loadDbPermissions(username);
		if (!dbPermissions.isEmpty()) {
			return dbPermissions;
		}

		Set<String> fallback = new LinkedHashSet<>();
		for (String role : resolvedRoles) {
			fallback.addAll(FALLBACK_ROLE_PERMISSIONS.getOrDefault(role, Collections.emptySet()));
		}
		return fallback;
	}

	private Set<String> loadDbRoles(String username) {
		try {
			List<String> dbRoles = jdbcTemplate.queryForList(
					"""
					SELECT r.role_code
					FROM iam_staff_users u
					JOIN iam_user_roles ur ON ur.user_id = u.user_id
					JOIN iam_roles r ON r.role_id = ur.role_id
					WHERE u.username = ?
					  AND u.status = 'ACTIVE'
					  AND r.status = 'ACTIVE'
					""",
					String.class,
					username);
			return dbRoles.stream().collect(Collectors.toCollection(LinkedHashSet::new));
		} catch (DataAccessException ex) {
			return Collections.emptySet();
		}
	}

	private Set<String> loadDbPermissions(String username) {
		try {
			List<String> dbPermissions = jdbcTemplate.queryForList(
					"""
					SELECT p.permission_code
					FROM iam_staff_users u
					JOIN iam_user_roles ur ON ur.user_id = u.user_id
					JOIN iam_roles r ON r.role_id = ur.role_id
					JOIN iam_role_permissions rp ON rp.role_id = r.role_id
					JOIN iam_permissions p ON p.permission_id = rp.permission_id
					WHERE u.username = ?
					  AND u.status = 'ACTIVE'
					  AND r.status = 'ACTIVE'
					""",
					String.class,
					username);
			return dbPermissions.stream().collect(Collectors.toCollection(LinkedHashSet::new));
		} catch (DataAccessException ex) {
			return Collections.emptySet();
		}
	}
}
