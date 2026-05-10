package com.patbaumgartner.embabel.workflow.visualizer;

import com.patbaumgartner.embabel.workflow.visualizer.WorkflowModels.WorkflowCatalog;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;

/**
 * Spring Boot Actuator endpoint that exposes the Embabel workflow catalog.
 *
 * <p>
 * Available at {@code /actuator/embabel} when the endpoint is exposed via
 * {@code management.endpoints.web.exposure.include=embabel}.
 *
 * <p>
 * Returns the same {@link WorkflowModels.WorkflowCatalog} as the REST API so that both
 * the visualization UI and monitoring tooling can consume workflow metadata from a
 * consistent source.
 */
@Endpoint(id = "embabel")
public class EmbabelWorkflowActuatorEndpoint {

	private final EmbabelWorkflowCatalogService catalogService;

	public EmbabelWorkflowActuatorEndpoint(EmbabelWorkflowCatalogService catalogService) {
		this.catalogService = catalogService;
	}

	@ReadOperation
	public WorkflowCatalog workflows() {
		return catalogService.catalog();
	}

}
