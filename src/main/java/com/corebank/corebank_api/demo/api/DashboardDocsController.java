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
 * Serves the small public document set linked from the demo dashboard.
 *
 * <p>The files are packaged on the classpath so the links work both from a source checkout and
 * from the container image, whose working directory contains only the application jar.
 */
@RestController
@RequestMapping("/dashboard/docs")
public class DashboardDocsController {

	private static final String CLASSPATH_PREFIX = "docs/";

	private static final Map<String, String> DOCS = Map.of(
			"readme", "README.md",
			"demo-script", "28-demo-script.md",
			"source-of-truth-map", "14-source-of-truth-map.md",
			"runtime-failure-modes", "19-runtime-failure-modes.md",
			"acceptance-criteria", "20-acceptance-criteria.md",
			"sequence-diagrams", "16-sequence-diagrams.md",
			"operations-runbook", "31-operations-runbook.md",
			"service-levels", "32-service-levels.md");

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

		for (Path candidate : new Path[] {Path.of("docs", fileName), Path.of(fileName)}) {
			Path absolute = candidate.toAbsolutePath().normalize();
			if (Files.isRegularFile(absolute)) {
				return new FileSystemResource(absolute);
			}
		}

		return null;
	}
}
