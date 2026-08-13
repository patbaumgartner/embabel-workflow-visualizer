package com.patbaumgartner.embabel.workflow.visualizer;

import com.patbaumgartner.embabel.workflow.visualizer.WorkflowModels.AgentWorkflow;
import com.patbaumgartner.embabel.workflow.visualizer.WorkflowModels.WorkflowCatalog;
import com.patbaumgartner.embabel.workflow.visualizer.WorkflowModels.WorkflowStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class EmbabelWorkflowApiControllerTests {

	private EmbabelWorkflowCatalogService catalogService;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		this.catalogService = mock(EmbabelWorkflowCatalogService.class);
		this.mockMvc = MockMvcBuilders.standaloneSetup(new EmbabelWorkflowApiController(catalogService))
			.setMessageConverters(new JacksonJsonHttpMessageConverter())
			.build();
	}

	@Test
	void returnsCatalogAsJson() throws Exception {
		WorkflowStep step = WorkflowStep.builder("doWork", "Action", "doWork")
			.description("desc")
			.post(List.of("done"))
			.inputs(List.of("Input"))
			.output("Output")
			.build();
		AgentWorkflow agent = AgentWorkflow.builder("demo-agent", "com.example.DemoAgent")
			.description("Demo")
			.version("1.0")
			.plannerType("GOAP")
			.steps(List.of(step))
			.build();
		given(catalogService.catalog()).willReturn(new WorkflowCatalog(List.of(agent)));

		mockMvc.perform(get("/embabel-workflows/api"))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.agents[0].agentName").value("demo-agent"))
			.andExpect(jsonPath("$.agents[0].description").value("Demo"))
			.andExpect(jsonPath("$.agents[0].version").value("1.0"))
			.andExpect(jsonPath("$.agents[0].plannerType").value("GOAP"))
			.andExpect(jsonPath("$.agents[0].opaque").value(false))
			.andExpect(jsonPath("$.agents[0].className").value("com.example.DemoAgent"))
			.andExpect(jsonPath("$.agents[0].steps[0].name").value("doWork"))
			.andExpect(jsonPath("$.agents[0].steps[0].method").value("doWork"))
			.andExpect(jsonPath("$.agents[0].steps[0].type").value("Action"))
			.andExpect(jsonPath("$.agents[0].steps[0].description").value("desc"))
			.andExpect(jsonPath("$.agents[0].steps[0].output").value("Output"))
			.andExpect(jsonPath("$.agents[0].steps[0].goal").value(false))
			.andExpect(jsonPath("$.agents[0].steps[0].canRerun").value(false))
			.andExpect(jsonPath("$.agents[0].steps[0].readOnly").value(false))
			.andExpect(jsonPath("$.agents[0].steps[0].inputs[0]").value("Input"))
			.andExpect(jsonPath("$.agents[0].steps[0].post[0]").value("done"));
	}

	@Test
	void returnsEmptyCatalogWhenNoAgents() throws Exception {
		given(catalogService.catalog()).willReturn(new WorkflowCatalog(List.of()));

		mockMvc.perform(get("/embabel-workflows/api"))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.agents").isArray())
			.andExpect(jsonPath("$.agents").isEmpty());
	}

	@Test
	void httpPostIsNotAllowed() throws Exception {
		mockMvc.perform(post("/embabel-workflows/api")).andExpect(status().isMethodNotAllowed());
	}

}
