package com.patbaumgartner.embabel.workflow.visualizer;

import com.patbaumgartner.embabel.workflow.visualizer.WorkflowModels.AgentWorkflow;
import com.patbaumgartner.embabel.workflow.visualizer.WorkflowModels.ToolMetadata;
import com.patbaumgartner.embabel.workflow.visualizer.WorkflowModels.WorkflowCatalog;
import com.patbaumgartner.embabel.workflow.visualizer.WorkflowModels.WorkflowStep;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

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

	@Test
	void agentProviderIsReflected() {
		AgentWorkflow agent = catalogWith(RichActionSampleAgent.class).agents().get(0);

		assertThat(agent.provider()).isEqualTo("acme");
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
		// No static cost/value declared — must be null, not 0.0
		assertThat(step.cost()).isNull();
		assertThat(step.value()).isNull();
		// No trigger / retry policy on this action
		assertThat(step.trigger()).isNull();
		assertThat(step.retryPolicy()).isNull();
	}

	@Test
	void actionTriggerAndRetryPolicyAreReflected() {
		Map<String, WorkflowStep> byMethod = catalogWith(RichActionSampleAgent.class).agents()
			.get(0)
			.steps()
			.stream()
			.collect(Collectors.toMap(WorkflowStep::method, s -> s));

		WorkflowStep step = byMethod.get("onRefresh");
		assertThat(step.trigger()).isEqualTo("RefreshEvent");
		assertThat(step.retryPolicy()).isEqualTo("maxAttempts=3");
	}

	@Test
	void staticCostAndValueAreReflected() {
		Map<String, WorkflowStep> byMethod = catalogWith(RichActionSampleAgent.class).agents()
			.get(0)
			.steps()
			.stream()
			.collect(Collectors.toMap(WorkflowStep::method, s -> s));

		WorkflowStep step = byMethod.get("staticCostAction");
		assertThat(step.cost()).isEqualTo(2.5);
		assertThat(step.value()).isEqualTo(0.4);
		assertThat(step.costMethod()).isNull();
		assertThat(step.valueMethod()).isNull();
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
		assertThat(step.goalValue()).isEqualTo(0.9);
		assertThat(step.exportedRemote()).isTrue();
		assertThat(step.exportName()).isEqualTo("richGoalTool");
		// @AchievesGoal(value=) must not leak into the @Action static cost/value
		assertThat(step.cost()).isNull();
		assertThat(step.value()).isNull();
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
		assertThat(step.llmToolReturnDirect()).isTrue();
		assertThat(step.llmToolCategory()).isEqualTo("utility");
	}

	// -------------------------------------------------------------------------
	// Full annotation-attribute coverage
	// -------------------------------------------------------------------------

	private Map<String, WorkflowStep> fullCoverageStepsByMethod() {
		return catalogWith(FullCoverageSampleAgent.class).agents()
			.get(0)
			.steps()
			.stream()
			.collect(Collectors.toMap(WorkflowStep::method, s -> s));
	}

	@Test
	void hybridPlannerAndAgentLevelAttributesAreReflected() {
		AgentWorkflow agent = catalogWith(FullCoverageSampleAgent.class).agents().get(0);

		assertThat(agent.plannerType()).isEqualTo("HYBRID");
		assertThat(agent.beanName()).isEqualTo("fullCoverageBean");
		assertThat(agent.retryPolicy()).isEqualTo("FIRE_ONCE");
		assertThat(agent.retryPolicyExpression()).isEqualTo("agentMaxAttempts=2");
		assertThat(agent.scan()).isTrue();
	}

	@Test
	void agentRetryPolicyIsNullWhenLeftAtDefault() {
		AgentWorkflow agent = catalogWith(RichActionSampleAgent.class).agents().get(0);

		assertThat(agent.retryPolicy()).isNull();
		assertThat(agent.retryPolicyExpression()).isNull();
		assertThat(agent.beanName()).isNull();
	}

	@Test
	void actionRetryPolicyConstantIsReflected() {
		Map<String, WorkflowStep> byMethod = fullCoverageStepsByMethod();

		assertThat(byMethod.get("fireOnce").actionRetryPolicy()).isEqualTo("FIRE_ONCE");
		// ActionRetryPolicy.DEFAULT means "not configured" and must not be reported
		assertThat(byMethod.get("bindParameters").actionRetryPolicy()).isNull();
	}

	@Test
	void conditionCostIsReflected() {
		Map<String, WorkflowStep> byMethod = fullCoverageStepsByMethod();

		WorkflowStep condition = byMethod.get("isReady");
		assertThat(condition.type()).isEqualTo("Condition");
		assertThat(condition.conditionCost()).isEqualTo(0.25);
	}

	@Test
	void exportLocalAndStartingInputTypesAreReflected() {
		WorkflowStep step = fullCoverageStepsByMethod().get("achieveExportedGoal");

		assertThat(step.exportedRemote()).isTrue();
		assertThat(step.exportedLocal()).isFalse();
		assertThat(step.exportName()).isEqualTo("hiddenLocally");
		assertThat(step.exportStartingInputTypes()).containsExactly("Draft");
	}

	@Test
	void exportDefaultsToLocallyCallable() {
		Map<String, WorkflowStep> byMethod = catalogWith(RichActionSampleAgent.class).agents()
			.get(0)
			.steps()
			.stream()
			.collect(Collectors.toMap(WorkflowStep::method, s -> s));

		assertThat(byMethod.get("achieveRichGoal").exportedLocal()).isTrue();
	}

	@Test
	void llmToolNameAndMetadataAreReflected() {
		WorkflowStep step = fullCoverageStepsByMethod().get("annotatedTool");

		assertThat(step.llmToolName()).isEqualTo("explicitToolName");
		assertThat(step.llmToolMetadata()).extracting(ToolMetadata::key, ToolMetadata::value)
			.containsExactly(tuple("owner", "platform"), tuple("stability", "beta"));
	}

	@Test
	void providedAndRequireNameMatchParametersAreReflected() {
		WorkflowStep step = fullCoverageStepsByMethod().get("bindParameters");

		assertThat(step.providedInputs()).containsExactly("Draft");
		assertThat(step.nameMatchInputs()).containsExactly("String:editorNotes");
	}

}
