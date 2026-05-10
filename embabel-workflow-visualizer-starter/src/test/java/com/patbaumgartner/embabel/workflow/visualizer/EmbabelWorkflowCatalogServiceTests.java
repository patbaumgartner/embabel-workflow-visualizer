package com.patbaumgartner.embabel.workflow.visualizer;

import com.patbaumgartner.embabel.workflow.visualizer.WorkflowModels.AgentWorkflow;
import com.patbaumgartner.embabel.workflow.visualizer.WorkflowModels.WorkflowCatalog;
import com.patbaumgartner.embabel.workflow.visualizer.WorkflowModels.WorkflowStep;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class EmbabelWorkflowCatalogServiceTests {

	// -------------------------------------------------------------------------
	// Helper
	// -------------------------------------------------------------------------

	/**
	 * Builds a {@link WorkflowCatalog} from a fresh
	 * {@link AnnotationConfigApplicationContext} that contains exactly the supplied bean
	 * classes, then closes the context.
	 */
	private WorkflowCatalog catalogWith(Class<?>... beanClasses) {
		try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
			for (Class<?> bc : beanClasses) {
				ctx.registerBean(bc.getSimpleName().toLowerCase(), bc);
			}
			ctx.refresh();
			return new EmbabelWorkflowCatalogService(ctx).catalog();
		}
	}

	// -------------------------------------------------------------------------
	// Context / bean scanning
	// -------------------------------------------------------------------------

	@Test
	void emptyContextProducesEmptyCatalog() {
		assertThat(catalogWith().agents()).isEmpty();
	}

	@Test
	void plainBeansAreIgnored() {
		assertThat(catalogWith(NotAnAgent.class).agents()).isEmpty();
	}

	// -------------------------------------------------------------------------
	// Agent metadata
	// -------------------------------------------------------------------------

	@Test
	void discoversAgentMetadata() {
		WorkflowCatalog catalog = catalogWith(SampleEmbabelAgent.class);

		assertThat(catalog.agents()).hasSize(1);
		AgentWorkflow agent = catalog.agents().get(0);
		assertThat(agent.agentName()).isEqualTo("demo-agent");
		assertThat(agent.description()).isEqualTo("Demo test agent");
		assertThat(agent.plannerType()).isEqualTo("GOAP");
		assertThat(agent.opaque()).isFalse();
		assertThat(agent.className()).isEqualTo(SampleEmbabelAgent.class.getName());
		assertThat(agent.steps()).extracting(WorkflowStep::method)
			.containsExactlyInAnyOrder("draftPlan", "hasInput", "completeGoal");
	}

	@Test
	void discoversEmbabelComponentBean() {
		WorkflowCatalog catalog = catalogWith(EmbabelComponentSampleBean.class);

		assertThat(catalog.agents()).hasSize(1);
		AgentWorkflow agent = catalog.agents().get(0);
		assertThat(agent.agentName()).isEqualTo(EmbabelComponentSampleBean.class.getSimpleName());
		assertThat(agent.plannerType()).isEqualTo("COMPONENT");
		assertThat(agent.opaque()).isFalse();
		assertThat(agent.steps()).extracting(WorkflowStep::method).containsExactly("doWork");
	}

	@Test
	void opaqueAttributeIsReflected() {
		AgentWorkflow agent = catalogWith(RichActionSampleAgent.class).agents().get(0);

		assertThat(agent.agentName()).isEqualTo("rich-agent");
		assertThat(agent.version()).isEqualTo("3.0.0");
		assertThat(agent.opaque()).isTrue();
	}

	// -------------------------------------------------------------------------
	// Sorting
	// -------------------------------------------------------------------------

	@Test
	void agentsAreSortedAlphabeticallyCaseInsensitive() {
		WorkflowCatalog catalog = catalogWith(MultiGoalSampleAgent.class, SampleEmbabelAgent.class);

		assertThat(catalog.agents()).extracting(AgentWorkflow::agentName)
			.containsExactly("demo-agent", "MultiGoalAgent");
	}

	@Test
	void stepsAreSortedAlphabeticallyByName() {
		assertThat(catalogWith(MultiGoalSampleAgent.class).agents()
			.get(0)
			.steps()
			.stream()
			.map(WorkflowStep::name)
			.toList()).isSortedAccordingTo(String.CASE_INSENSITIVE_ORDER);
	}

	// -------------------------------------------------------------------------
	// Step types and annotations
	// -------------------------------------------------------------------------

	@Test
	void multipleAchievesGoalMethodsAreAllCaptured() {
		AgentWorkflow agent = catalogWith(MultiGoalSampleAgent.class).agents().get(0);
		assertThat(agent.version()).isEqualTo("2.0.0");

		Map<String, WorkflowStep> byMethod = agent.steps()
			.stream()
			.collect(Collectors.toMap(WorkflowStep::method, s -> s));

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

	@Test
	void richActionAttributesAreReflected() {
		Map<String, WorkflowStep> byMethod = catalogWith(RichActionSampleAgent.class).agents()
			.get(0)
			.steps()
			.stream()
			.collect(Collectors.toMap(WorkflowStep::method, s -> s));

		WorkflowStep step = byMethod.get("processData");
		assertThat(step.pre()).containsExactly("precondition");
		assertThat(step.post()).containsExactly("postcondition");
		assertThat(step.canRerun()).isTrue();
		assertThat(step.readOnly()).isTrue();
		assertThat(step.outputBinding()).isEqualTo("myOutput");
		assertThat(step.costMethod()).isEqualTo("calcCost");
		assertThat(step.valueMethod()).isEqualTo("calcValue");
		assertThat(step.clearBlackboard()).isFalse();
	}

	@Test
	void achievesGoalTagsAndExamplesAreReflected() {
		Map<String, WorkflowStep> byMethod = catalogWith(RichActionSampleAgent.class).agents()
			.get(0)
			.steps()
			.stream()
			.collect(Collectors.toMap(WorkflowStep::method, s -> s));

		WorkflowStep step = byMethod.get("achieveRichGoal");
		assertThat(step.type()).isEqualTo("AchievesGoal");
		assertThat(step.goal()).isTrue();
		assertThat(step.tags()).containsExactlyInAnyOrder("tag1", "tag2");
		assertThat(step.examples()).containsExactlyInAnyOrder("example 1", "example 2");
	}

	@Test
	void llmToolAnnotationIsRecognized() {
		Map<String, WorkflowStep> byMethod = catalogWith(RichActionSampleAgent.class).agents()
			.get(0)
			.steps()
			.stream()
			.collect(Collectors.toMap(WorkflowStep::method, s -> s));

		WorkflowStep step = byMethod.get("helpTool");
		assertThat(step.type()).isEqualTo("LlmTool");
		assertThat(step.llmTool()).isTrue();
		assertThat(step.llmToolDescription()).isEqualTo("A helpful LLM tool");
		assertThat(step.description()).isEqualTo("A helpful LLM tool");
		assertThat(step.inputs()).containsExactly("String");
	}

}
