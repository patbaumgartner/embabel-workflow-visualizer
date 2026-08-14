package com.patbaumgartner.embabel.workflow.visualizer;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import static com.patbaumgartner.embabel.workflow.visualizer.EmbabelWorkflowVisualizerProperties.BASE_PATH_PLACEHOLDER;

/**
 * Serves the Embabel Workflow Visualizer page from
 * {@code embabel.workflow.visualizer.base-path} (default {@code /embabel-workflows}).
 *
 * <p>
 * The page is a private classpath resource rather than a {@code static/} one on purpose.
 * Spring Boot serves {@code classpath:/static/**} from every jar on the classpath, so a
 * page published there would stay reachable even with the visualizer disabled, and would
 * compete for file names with the consuming application's own assets.
 *
 * <p>
 * For the same reason the condition is repeated on the class. {@code @RestController} is
 * a {@code @Component} stereotype, so a consuming application whose base package encloses
 * this one component-scans this class and would otherwise serve the page whatever
 * {@code embabel.workflow.visualizer.enabled} says. Component scanning evaluates
 * {@code @Conditional} as well, so the property governs both routes to this bean.
 */
@RestController
@ConditionalOnProperty(prefix = "embabel.workflow.visualizer", name = "enabled", havingValue = "true")
@SuppressWarnings("unused") // instantiated by EmbabelWorkflowVisualizerAutoConfiguration
public class WorkflowVisualizerPageController {

	static final String PAGE_RESOURCE = "com/patbaumgartner/embabel/workflow/visualizer/workflow-visualizer.html";

	private final String page;

	public WorkflowVisualizerPageController() {
		this.page = readPage();
	}

	@GetMapping(path = BASE_PATH_PLACEHOLDER, produces = MediaType.TEXT_HTML_VALUE)
	public ResponseEntity<String> index() {
		return ResponseEntity.ok()
			.contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
			.cacheControl(CacheControl.noCache())
			.body(this.page);
	}

	private static String readPage() {
		ClassPathResource resource = new ClassPathResource(PAGE_RESOURCE,
				WorkflowVisualizerPageController.class.getClassLoader());
		try {
			return resource.getContentAsString(StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Embabel Workflow Visualizer page is missing from " + PAGE_RESOURCE, ex);
		}
	}

}
