package com.patbaumgartner.embabel.workflow.visualizer;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WorkflowVisualizerPageControllerTests {

	private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new WorkflowVisualizerPageController()).build();

	@Test
	void forwardsToStaticHtml() {
		assertThat(new WorkflowVisualizerPageController().index()).isEqualTo("forward:/workflow-visualizer.html");
	}

	@Test
	void getRequestForwardsToStaticHtml() throws Exception {
		mockMvc.perform(get("/embabel-workflows"))
			.andExpect(status().isOk())
			.andExpect(forwardedUrl("/workflow-visualizer.html"));
	}

}
