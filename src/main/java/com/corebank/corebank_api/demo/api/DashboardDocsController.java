package com.corebank.corebank_api.demo.api;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Serves the handful of project documents the dashboard links to.
 *
 * <p>These are read from the classpath. The previous version resolved them against the process
 * working directory, which works when the jar is launched from a checkout but not inside the
 * container image, whose working directory holds only {@code app.jar} — every one of these links
 * returned 404 on the deployed demo. The build copies the referenced files into {@code docs/} on
 * the classpath (see the {@code <resources>} block in {@code pom.xml}), so the repository stays the
 * single source of truth and the links work in both places.
 */
@RestController
@RequestMapping("/dashboard/docs")
public class DashboardDocsController {

	private static final String CLASSPATH_PREFIX = "docs/";

	private static final Map<String, String> DOCS = Map.of(
			"readme", "README.md",
			"demo-script", "28-demo-script.md",
			"interview-prep", "29-interview-prep.md",
			"source-of-truth-map", "14-source-of-truth-map.md",
			"runtime-failure-modes", "19-runtime-failure-modes.md",
			"acceptance-criteria", "20-acceptance-criteria.md",
			"sequence-diagrams", "16-sequence-diagrams.md");

	@GetMapping("/{docKey}")
	public ResponseEntity<Resource> open(@PathVariable String docKey) {
		String fileName = DOCS.get(docKey);
		if (fileName == null) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown document");
		}

		Resource resource = resolve(fileName);
		if (resource == null) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found");
		}

		return ResponseEntity.ok()
				.contentType(MediaType.parseMediaType("text/markdown"))
				.header(HttpHeaders.CACHE_CONTROL, "no-store")
				.body(resource);
	}

	private Resource resolve(String fileName) {
		ClassPathResource packaged = new ClassPathResource(CLASSPATH_PREFIX + fileName);
		if (packaged.exists() && packaged.isReadable()) {
			return packaged;
		}

		// Fallback for a developer running from a checkout without rebuilding resources.
		for (Path candidate : new Path[] {Path.of("docs", fileName), Path.of(fileName)}) {
			Path absolute = candidate.toAbsolutePath().normalize();
			if (Files.isRegularFile(absolute)) {
				return new FileSystemResource(absolute);
			}
		}

		return null;
	}
}
