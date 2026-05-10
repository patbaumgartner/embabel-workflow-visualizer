package com.patbaumgartner.embabel.workflow.visualizer;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the Embabel Workflow Visualizer HTML page.
 *
 * <p>
 * Mapped to {@code /embabel-workflows} to avoid conflicting with the root path of the
 * host application.
 */
@Controller
@SuppressWarnings("unused") // instantiated by EmbabelWorkflowVisualizerAutoConfiguration
public class WorkflowVisualizerPageController {

	@GetMapping({ "/embabel-workflows" })
	public String index() {
		return "forward:/workflow-visualizer.html";
	}

}
