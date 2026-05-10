package com.patbaumgartner.embabel.workflow.visualizer;

import com.patbaumgartner.embabel.workflow.visualizer.WorkflowModels.AgentWorkflow;
import com.patbaumgartner.embabel.workflow.visualizer.WorkflowModels.WorkflowCatalog;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class EmbabelWorkflowActuatorEndpointTests {

    @Test
    void readOperationDelegatesToCatalogService() {
        EmbabelWorkflowCatalogService service = mock(EmbabelWorkflowCatalogService.class);
        WorkflowCatalog expected = new WorkflowCatalog(
                List.of(new AgentWorkflow("a", null, null, "C", List.of())));
        given(service.catalog()).willReturn(expected);

        WorkflowCatalog actual = new EmbabelWorkflowActuatorEndpoint(service).workflows();

        assertThat(actual).isSameAs(expected);
        verify(service).catalog();
    }

}
