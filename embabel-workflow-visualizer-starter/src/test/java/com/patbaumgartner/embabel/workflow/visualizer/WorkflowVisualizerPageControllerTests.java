package com.patbaumgartner.embabel.workflow.visualizer;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.StaticWebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WorkflowVisualizerPageControllerTests {

	@Test
	void servesTheVisualizerPageAsHtml() throws Exception {
		mockMvcWithBasePath(null).perform(get("/embabel-workflows"))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith("text/html"))
			.andExpect(content().encoding("UTF-8"))
			.andExpect(content().string(containsString("Embabel Workflow Visualizer")))
			.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache"));
	}

	@Test
	void servesTheVisualizerPageFromACustomBasePath() throws Exception {
		MockMvc mockMvc = mockMvcWithBasePath("/internal/agent-flows");

		mockMvc.perform(get("/internal/agent-flows")).andExpect(status().isOk());
		mockMvc.perform(get("/embabel-workflows")).andExpect(status().isNotFound());
	}

	/**
	 * The page resolves its API URL from the browser's own location, so it keeps working
	 * behind a servlet context path or a reverse-proxy prefix. A hard-coded absolute path
	 * would silently 404 in those deployments.
	 */
	@Test
	void pageDoesNotHardCodeAnAbsoluteApiPath() {
		String page = new WorkflowVisualizerPageController().index().getBody();

		assertThat(page).doesNotContain("fetch('/embabel-workflows/api')").contains("window.location.pathname");
	}

	private MockMvc mockMvcWithBasePath(String basePath) {
		StaticWebApplicationContext context = new StaticWebApplicationContext();
		context.setServletContext(new MockServletContext());
		if (basePath != null) {
			MockEnvironment environment = new MockEnvironment();
			environment.setProperty("embabel.workflow.visualizer.base-path", basePath);
			context.setEnvironment(environment);
		}
		context.registerSingleton("pageController", WorkflowVisualizerPageController.class);
		context.refresh();
		return MockMvcBuilders.webAppContextSetup(context).build();
	}

}
