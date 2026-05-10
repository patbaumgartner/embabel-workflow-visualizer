package com.patbaumgartner.embabel.workflow.visualizer;

import com.patbaumgartner.embabel.workflow.visualizer.WorkflowModels.WorkflowCatalog;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
