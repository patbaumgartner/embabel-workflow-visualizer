package com.patbaumgartner.embabel.workflow.visualizer;

import com.patbaumgartner.embabel.workflow.visualizer.WorkflowModels.WorkflowCatalog;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;

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
