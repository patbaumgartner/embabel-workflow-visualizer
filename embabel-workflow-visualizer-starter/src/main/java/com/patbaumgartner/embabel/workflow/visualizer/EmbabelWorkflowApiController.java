package com.patbaumgartner.embabel.workflow.visualizer;

import com.patbaumgartner.embabel.workflow.visualizer.WorkflowModels.WorkflowCatalog;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.patbaumgartner.embabel.workflow.visualizer.EmbabelWorkflowVisualizerProperties.BASE_PATH_PLACEHOLDER;

/**
 * REST controller that exposes the Embabel workflow catalog as JSON.
 *
 * <p>
 * Serves {@code GET <base-path>/api} (default {@code /embabel-workflows/api}) when
 * {@code embabel.workflow.visualizer.enabled=true} is set. The response is consumed by
 * the bundled visualization UI to render the interactive workflow diagram.
 *
 * <p>
 * Returns the same {@link WorkflowModels.WorkflowCatalog} as the actuator endpoint so
 * that both surfaces always reflect the same agent discovery result.
 *
 * <p>
 * {@code @RestController} is a {@code @Component} stereotype, so a consuming application
 * whose base package encloses this one component-scans this class and would register it
 * directly, bypassing the auto-configuration and serving the API on an application that
 * had switched the visualizer off. The condition is therefore repeated on the class:
 * component scanning evaluates {@code @Conditional} too, so the property governs both
 * routes to this bean.
 */
@RestController
@ConditionalOnProperty(prefix = "embabel.workflow.visualizer", name = "enabled", havingValue = "true")
@RequestMapping(path = BASE_PATH_PLACEHOLDER + "/api", produces = MediaType.APPLICATION_JSON_VALUE)
@SuppressWarnings("unused") // instantiated by EmbabelWorkflowVisualizerAutoConfiguration
public class EmbabelWorkflowApiController {

	private final EmbabelWorkflowCatalogService catalogService;

	public EmbabelWorkflowApiController(EmbabelWorkflowCatalogService catalogService) {
		this.catalogService = catalogService;
	}

	@GetMapping
	public WorkflowCatalog workflows() {
		return this.catalogService.catalog();
	}

}
