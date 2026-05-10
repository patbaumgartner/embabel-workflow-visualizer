package com.patbaumgartner.embabel.workflow.visualizer;

import com.patbaumgartner.embabel.workflow.visualizer.WorkflowModels.AgentWorkflow;
import com.patbaumgartner.embabel.workflow.visualizer.WorkflowModels.WorkflowCatalog;
import com.patbaumgartner.embabel.workflow.visualizer.WorkflowModels.WorkflowStep;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.support.GenericApplicationContext;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EmbabelWorkflowCatalogServiceTests {

    @Test
    void emptyContextProducesEmptyCatalog() {
        try (GenericApplicationContext ctx = new GenericApplicationContext()) {
            ctx.refresh();
            WorkflowCatalog catalog = new EmbabelWorkflowCatalogService(ctx).catalog();
            assertThat(catalog.agents()).isEmpty();
        }
    }

    @Test
    void plainBeansAreIgnored() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.registerBean("notAnAgent", NotAnAgent.class);
            ctx.refresh();
            WorkflowCatalog catalog = new EmbabelWorkflowCatalogService(ctx).catalog();
            assertThat(catalog.agents()).isEmpty();
        }
    }

    @Test
    void discoversAgentMetadata() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.registerBean("sample", SampleEmbabelAgent.class);
            ctx.refresh();
            WorkflowCatalog catalog = new EmbabelWorkflowCatalogService(ctx).catalog();

            assertThat(catalog.agents()).hasSize(1);
            AgentWorkflow agent = catalog.agents().get(0);
            assertThat(agent.agentName()).isEqualTo("demo-agent");
            assertThat(agent.description()).isEqualTo("Demo test agent");
            assertThat(agent.className()).isEqualTo(SampleEmbabelAgent.class.getName());
            assertThat(agent.steps()).extracting(WorkflowStep::method)
                    .containsExactlyInAnyOrder("draftPlan", "hasInput", "completeGoal");
        }
    }

    @Test
    void agentsAreSortedAlphabeticallyCaseInsensitive() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.registerBean("multi", MultiGoalSampleAgent.class);
            ctx.registerBean("simple", SampleEmbabelAgent.class);
            ctx.refresh();
            WorkflowCatalog catalog = new EmbabelWorkflowCatalogService(ctx).catalog();

            assertThat(catalog.agents()).extracting(AgentWorkflow::agentName)
                    .containsExactly("demo-agent", "MultiGoalAgent");
        }
    }

    @Test
    void multipleAchievesGoalMethodsAreAllCaptured() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.registerBean("multi", MultiGoalSampleAgent.class);
            ctx.refresh();
            WorkflowCatalog catalog = new EmbabelWorkflowCatalogService(ctx).catalog();

            AgentWorkflow agent = catalog.agents().get(0);
            assertThat(agent.version()).isEqualTo("2.0.0");

            Map<String, WorkflowStep> byMethod = agent.steps()
                    .stream()
                    .collect(java.util.stream.Collectors.toMap(WorkflowStep::method, s -> s));

            assertThat(byMethod).containsKeys("inspect", "isFastPath", "completeFast", "completeSlow");

            WorkflowStep inspect = byMethod.get("inspect");
            assertThat(inspect.type()).isEqualTo("Action");
            assertThat(inspect.post()).containsExactlyInAnyOrder(MultiGoalSampleAgent.FAST_PATH,
                    MultiGoalSampleAgent.SLOW_PATH);
            assertThat(inspect.inputs()).containsExactly("Request");
            assertThat(inspect.output()).isEqualTo("Inspection");
            assertThat(inspect.goal()).isFalse();

            WorkflowStep fast = byMethod.get("completeFast");
            assertThat(fast.type()).isEqualTo("AchievesGoal");
            assertThat(fast.goal()).isTrue();
            assertThat(fast.pre()).containsExactly(MultiGoalSampleAgent.FAST_PATH);
            assertThat(fast.inputs()).containsExactly("Inspection");
            assertThat(fast.output()).isEqualTo("Result");

            WorkflowStep slow = byMethod.get("completeSlow");
            assertThat(slow.type()).isEqualTo("AchievesGoal");
            assertThat(slow.goal()).isTrue();
            assertThat(slow.pre()).containsExactly(MultiGoalSampleAgent.SLOW_PATH);

            WorkflowStep cond = byMethod.get("isFastPath");
            assertThat(cond.type()).isEqualTo("Condition");
            assertThat(cond.output()).isEqualTo("boolean");
        }
    }

    @Test
    void stepsAreSortedAlphabeticallyByName() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.registerBean("multi", MultiGoalSampleAgent.class);
            ctx.refresh();
            WorkflowCatalog catalog = new EmbabelWorkflowCatalogService(ctx).catalog();

            List<String> names = catalog.agents().get(0).steps().stream().map(WorkflowStep::name).toList();
            List<String> sorted = names.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
            assertThat(names).isEqualTo(sorted);
        }
    }

}
