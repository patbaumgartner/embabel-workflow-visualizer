package com.patbaumgartner.embabel.workflow.visualizer;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowVisualizerPageControllerTests {

    @Test
    void forwardsToStaticHtml() {
        assertThat(new WorkflowVisualizerPageController().index()).isEqualTo("forward:/workflow-visualizer.html");
    }

}
