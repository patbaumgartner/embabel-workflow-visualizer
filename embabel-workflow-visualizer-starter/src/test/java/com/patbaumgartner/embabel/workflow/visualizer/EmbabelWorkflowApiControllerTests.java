package com.patbaumgartner.embabel.workflow.visualizer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.patbaumgartner.embabel.workflow.visualizer.WorkflowModels.AgentWorkflow;
import com.patbaumgartner.embabel.workflow.visualizer.WorkflowModels.WorkflowCatalog;
import com.patbaumgartner.embabel.workflow.visualizer.WorkflowModels.WorkflowStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class EmbabelWorkflowApiControllerTests {

    private EmbabelWorkflowCatalogService catalogService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        this.catalogService = mock(EmbabelWorkflowCatalogService.class);
        MappingJackson2HttpMessageConverter jackson = new MappingJackson2HttpMessageConverter(new ObjectMapper());
        this.mockMvc = MockMvcBuilders.standaloneSetup(new EmbabelWorkflowApiController(catalogService))
                .setMessageConverters(jackson)
                .build();
    }

    @Test
    void returnsCatalogAsJson() throws Exception {
        WorkflowStep step = new WorkflowStep("doWork", "Action", "desc", "doWork", List.of(), List.of("done"),
                List.of("Input"), "Output", false);
        AgentWorkflow agent = new AgentWorkflow("demo-agent", "Demo", "1.0", "com.example.DemoAgent", List.of(step));
        given(catalogService.catalog()).willReturn(new WorkflowCatalog(List.of(agent)));

        mockMvc.perform(get("/embabel-workflows/api"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.agents[0].agentName").value("demo-agent"))
                .andExpect(jsonPath("$.agents[0].steps[0].method").value("doWork"))
                .andExpect(jsonPath("$.agents[0].steps[0].type").value("Action"));
    }

    @Test
    void returnsEmptyCatalogWhenNoAgents() throws Exception {
        given(catalogService.catalog()).willReturn(new WorkflowCatalog(List.of()));

        mockMvc.perform(get("/embabel-workflows/api"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agents").isArray())
                .andExpect(jsonPath("$.agents").isEmpty());
    }

}
