package com.patbaumgartner.embabel.workflow.visualizer;

import com.patbaumgartner.embabel.workflow.visualizer.WorkflowModels.WorkflowCatalog;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller that exposes the Embabel workflow catalog as JSON.
 *
 * <p>
 * Serves {@code GET /embabel-workflows/api} when
 * {@code embabel.workflow.visualizer.enabled=true} is set. The response is consumed by
 * the bundled visualization UI to render the interactive workflow diagram.
 *
 * <p>
 * Returns the same {@link WorkflowModels.WorkflowCatalog} as the actuator endpoint so
 * that both surfaces always reflect the same agent discovery result.
 */
@RestController
@RequestMapping(path = "/embabel-workflows/api", produces = MediaType.APPLICATION_JSON_VALUE)
@SuppressWarnings("unused") // instantiated by EmbabelWorkflowVisualizerAutoConfiguration
public class EmbabelWorkflowApiController {

	private final EmbabelWorkflowCatalogService catalogService;

	public EmbabelWorkflowApiController(EmbabelWorkflowCatalogService catalogService) {
		this.catalogService = catalogService;
	}

	@GetMapping
	public WorkflowCatalog workflows() {
		return catalogService.catalog();
	}

}
