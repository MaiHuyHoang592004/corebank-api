package com.corebank.corebank_api.demo.api;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/dashboard/docs")
public class DashboardDocsController {

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
		String relativePath = DOCS.get(docKey);
		if (relativePath == null) {
			throw new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Unknown document");
		}

		Path absolutePath = Path.of(relativePath).toAbsolutePath().normalize();
		if (!Files.exists(absolutePath) || !Files.isRegularFile(absolutePath)) {
			throw new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Document not found");
		}

		Resource resource = new FileSystemResource(absolutePath);
		return ResponseEntity.ok()
				.contentType(MediaType.parseMediaType("text/markdown"))
				.header(HttpHeaders.CACHE_CONTROL, "no-store")
				.body(resource);
	}
}
