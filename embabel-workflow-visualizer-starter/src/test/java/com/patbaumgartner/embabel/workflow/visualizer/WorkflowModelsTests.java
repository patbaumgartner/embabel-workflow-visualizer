package com.patbaumgartner.embabel.workflow.visualizer;

import com.patbaumgartner.embabel.workflow.visualizer.WorkflowModels.AgentWorkflow;
import com.patbaumgartner.embabel.workflow.visualizer.WorkflowModels.WorkflowStep;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowModelsTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	/**
	 * The JSON emitted by {@code /actuator/embabel} and {@code /embabel-workflows/api} is
	 * a public contract consumed by the bundled UI and by third-party tooling. Adding a
	 * property is backwards compatible; renaming or dropping one is not. Either way the
	 * change must be deliberate, which is what this test forces.
	 */
	@Test
	void stepJsonContractIsStable() {
		assertThat(propertiesOf(WorkflowStep.builder("n", "Action", "m").build())).containsOnlyKeys("name", "type",
				"description", "method", "pre", "post", "inputs", "output", "goal", "costMethod", "valueMethod", "cost",
				"value", "goalValue", "possibleOutputs", "canRerun", "readOnly", "outputBinding", "clearBlackboard",
				"tags", "examples", "llmTool", "llmToolDescription", "exportedRemote", "exportName", "trigger",
				"retryPolicy", "llmToolReturnDirect", "llmToolCategory", "actionRetryPolicy", "conditionCost",
				"exportedLocal", "exportStartingInputTypes", "llmToolName", "llmToolMetadata", "providedInputs",
				"nameMatchInputs", "registered", "plannerGenerated");
	}

	@Test
	void agentJsonContractIsStable() {
		assertThat(propertiesOf(AgentWorkflow.builder("a", "C").build())).containsOnlyKeys("agentName", "description",
				"version", "plannerType", "opaque", "className", "steps", "provider", "beanName", "scan", "retryPolicy",
				"retryPolicyExpression", "registered");
	}

	/**
	 * The 1.0.x constructors are kept so code compiled against that release still links,
	 * and they are the only members here nothing else calls. Each hands twenty-nine
	 * arguments to a thirty-nine-component record positionally, where neighbours of the
	 * same type — {@code costMethod} beside {@code valueMethod}, {@code tags} beside
	 * {@code examples} — would swap without a compiler error and quietly mislabel every
	 * step of a consumer still on 1.0.x. Giving each argument its own name as its value
	 * is what makes such a swap visible.
	 */
	@Test
	@SuppressWarnings("removal")
	void theOneZeroStepConstructorStillLandsEveryArgumentWhereItBelongs() {
		WorkflowStep step = new WorkflowStep("name", "type", "description", "method", List.of("pre"), List.of("post"),
				List.of("inputs"), "output", true, "costMethod", "valueMethod", 1.0, 2.0, 3.0,
				List.of("possibleOutputs"), false, true, "outputBinding", false, List.of("tags"), List.of("examples"),
				true, "llmToolDescription", false, "exportName", "trigger", "retryPolicy", true, "llmToolCategory");

		assertThat(step.name()).isEqualTo("name");
		assertThat(step.type()).isEqualTo("type");
		assertThat(step.description()).isEqualTo("description");
		assertThat(step.method()).isEqualTo("method");
		assertThat(step.pre()).containsExactly("pre");
		assertThat(step.post()).containsExactly("post");
		assertThat(step.inputs()).containsExactly("inputs");
		assertThat(step.output()).isEqualTo("output");
		assertThat(step.goal()).isTrue();
		assertThat(step.costMethod()).isEqualTo("costMethod");
		assertThat(step.valueMethod()).isEqualTo("valueMethod");
		assertThat(step.cost()).isEqualTo(1.0);
		assertThat(step.value()).isEqualTo(2.0);
		assertThat(step.goalValue()).isEqualTo(3.0);
		assertThat(step.possibleOutputs()).containsExactly("possibleOutputs");
		assertThat(step.canRerun()).isFalse();
		assertThat(step.readOnly()).isTrue();
		assertThat(step.outputBinding()).isEqualTo("outputBinding");
		assertThat(step.clearBlackboard()).isFalse();
		assertThat(step.tags()).containsExactly("tags");
		assertThat(step.examples()).containsExactly("examples");
		assertThat(step.llmTool()).isTrue();
		assertThat(step.llmToolDescription()).isEqualTo("llmToolDescription");
		assertThat(step.exportedRemote()).isFalse();
		assertThat(step.exportName()).isEqualTo("exportName");
		assertThat(step.trigger()).isEqualTo("trigger");
		assertThat(step.retryPolicy()).isEqualTo("retryPolicy");
		assertThat(step.llmToolReturnDirect()).isTrue();
		assertThat(step.llmToolCategory()).isEqualTo("llmToolCategory");
	}

	/**
	 * Everything the 1.0.x constructor cannot express. {@code exportedLocal} is knowingly
	 * wrong — Embabel defaults {@code @Export(local)} to {@code true} — because a
	 * positional constructor has nowhere to say so; the builder is what corrects it.
	 */
	@Test
	@SuppressWarnings("removal")
	void theOneZeroStepConstructorLeavesTheLaterAttributesUndeclared() {
		WorkflowStep step = new WorkflowStep("n", "Action", "", "m", List.of(), List.of(), List.of(), "void", false,
				null, null, null, null, null, null, false, false, null, false, List.of(), List.of(), false, null, false,
				null, null, null, false, null);

		assertThat(step.actionRetryPolicy()).isNull();
		assertThat(step.conditionCost()).isNull();
		assertThat(step.exportedLocal()).isFalse();
		assertThat(step.exportStartingInputTypes()).isEmpty();
		assertThat(step.llmToolName()).isNull();
		assertThat(step.llmToolMetadata()).isEmpty();
		assertThat(step.providedInputs()).isEmpty();
		assertThat(step.nameMatchInputs()).isEmpty();
		assertThat(step.registered()).isNull();
		assertThat(step.plannerGenerated()).isFalse();
	}

	@Test
	@SuppressWarnings("removal")
	void theOneZeroAgentConstructorStillLandsEveryArgumentWhereItBelongs() {
		AgentWorkflow agent = new AgentWorkflow("agentName", "description", "version", "plannerType", true, "className",
				List.of(WorkflowStep.builder("n", "Action", "m").build()), "provider");

		assertThat(agent.agentName()).isEqualTo("agentName");
		assertThat(agent.description()).isEqualTo("description");
		assertThat(agent.version()).isEqualTo("version");
		assertThat(agent.plannerType()).isEqualTo("plannerType");
		assertThat(agent.opaque()).isTrue();
		assertThat(agent.className()).isEqualTo("className");
		assertThat(agent.steps()).singleElement().extracting(WorkflowStep::name).isEqualTo("n");
		assertThat(agent.provider()).isEqualTo("provider");

		assertThat(agent.beanName()).isNull();
		assertThat(agent.scan()).isTrue();
		assertThat(agent.retryPolicy()).isNull();
		assertThat(agent.retryPolicyExpression()).isNull();
		assertThat(agent.registered()).isNull();
	}

	@Test
	void stepBuilderDefaultsMeanNotDeclared() {
		WorkflowStep step = WorkflowStep.builder("draft", "Action", "draftPlan").build();

		assertThat(step.name()).isEqualTo("draft");
		assertThat(step.type()).isEqualTo("Action");
		assertThat(step.method()).isEqualTo("draftPlan");
		assertThat(step.output()).isEqualTo("void");
		assertThat(step.pre()).isEmpty();
		assertThat(step.post()).isEmpty();
		assertThat(step.cost()).isNull();
		assertThat(step.outputBinding()).isNull();
		assertThat(step.possibleOutputs()).isNull();
		assertThat(step.goal()).isFalse();
		// null means "no platform was available to ask", not "not registered"
		assertThat(step.registered()).isNull();
		assertThat(step.plannerGenerated()).isFalse();
		// @Export(local) defaults to true in Embabel, so a step is locally callable
		// unless it opts out
		assertThat(step.exportedLocal()).isTrue();
	}

	@Test
	void agentBuilderDefaultsMeanNotDeclared() {
		AgentWorkflow agent = AgentWorkflow.builder("demo", "com.example.Demo").build();

		assertThat(agent.steps()).isEmpty();
		assertThat(agent.provider()).isNull();
		assertThat(agent.beanName()).isNull();
		assertThat(agent.retryPolicy()).isNull();
		// @Agent(scan) defaults to true in Embabel
		assertThat(agent.scan()).isTrue();
		assertThat(agent.registered()).isNull();
	}

	@Test
	void collectionsAreDefensivelyCopied() {
		List<String> mutableTags = new ArrayList<>(List.of("initial"));
		WorkflowStep step = WorkflowStep.builder("n", "Action", "m").tags(mutableTags).build();

		mutableTags.add("added-after-build");

		assertThat(step.tags()).containsExactly("initial");
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> propertiesOf(Object value) {
		return JSON.readValue(JSON.writeValueAsString(value), Map.class);
	}

	@Test
	void nullCollectionsBecomeEmptyNotNull() {
		WorkflowStep step = WorkflowStep.builder("n", "Action", "m").tags(null).inputs(null).build();

		assertThat(step.tags()).isEmpty();
		assertThat(step.inputs()).isEmpty();
	}

	/**
	 * The reconciler derives steps and agents from existing ones, so a {@code toBuilder}
	 * that quietly dropped an attribute would erase it from the catalog. Every component
	 * of the fixtures below is set away from its default first, so a dropped one cannot
	 * coincide with what the builder would have defaulted it to anyway.
	 */
	@Test
	void rebuildingAStepFromItselfChangesNothing() {
		WorkflowStep step = fullyPopulatedStep();
		assertEveryComponentIsDistinctive(step, WorkflowStep.class);

		assertThat(step.toBuilder().build()).isEqualTo(step);
	}

	@Test
	void rebuildingAnAgentFromItselfChangesNothing() {
		AgentWorkflow agent = fullyPopulatedAgent();
		assertEveryComponentIsDistinctive(agent, AgentWorkflow.class);

		assertThat(agent.toBuilder().build()).isEqualTo(agent);
	}

	@Test
	void derivingAStepChangesOnlyWhatWasAskedFor() {
		WorkflowStep step = fullyPopulatedStep();

		WorkflowStep derived = step.toBuilder().registered(false).build();

		assertThat(derived.registered()).isFalse();
		assertThat(derived).isEqualTo(step.toBuilder().registered(false).build());
		assertThat(derived.toBuilder().registered(step.registered()).build()).isEqualTo(step);
	}

	private static WorkflowStep fullyPopulatedStep() {
		return WorkflowStep.builder("stepName", "Action", "stepMethod")
			.description("description")
			.pre(List.of("pre"))
			.post(List.of("post"))
			.inputs(List.of("Input"))
			.output("Output")
			.goal(true)
			.costMethod("costMethod")
			.valueMethod("valueMethod")
			.cost(1.5)
			.value(2.5)
			.goalValue(3.5)
			.possibleOutputs(List.of("Alternative"))
			.canRerun(true)
			.readOnly(true)
			.outputBinding("binding")
			.clearBlackboard(true)
			.tags(List.of("tag"))
			.examples(List.of("example"))
			.llmTool(true)
			.llmToolDescription("toolDescription")
			.exportedRemote(true)
			.exportName("exportName")
			.trigger("Trigger")
			.retryPolicy("retryPolicy")
			.llmToolReturnDirect(true)
			.llmToolCategory("category")
			.actionRetryPolicy("FIRE_ONCE")
			.conditionCost(4.5)
			.exportedLocal(true)
			.exportStartingInputTypes(List.of("Starting"))
			.llmToolName("toolName")
			.llmToolMetadata(List.of(new WorkflowModels.ToolMetadata("key", "value")))
			.providedInputs(List.of("Provided"))
			.nameMatchInputs(List.of("Named:bound"))
			.registered(true)
			.plannerGenerated(true)
			.build();
	}

	private static AgentWorkflow fullyPopulatedAgent() {
		return AgentWorkflow.builder("agentName", "com.example.AgentClass")
			.description("description")
			.version("9.9.9")
			.plannerType("HYBRID")
			.opaque(true)
			.steps(List.of(fullyPopulatedStep()))
			.provider("provider")
			.beanName("beanName")
			.scan(true)
			.retryPolicy("FIRE_ONCE")
			.retryPolicyExpression("expression")
			.registered(true)
			.build();
	}

	/**
	 * Fails when a component of {@code recordType} still holds the value the builder
	 * would default it to, which would make the round-trip above pass by coincidence.
	 * Adding a fortieth component therefore breaks this test until the fixture sets it.
	 */
	private static void assertEveryComponentIsDistinctive(Object record, Class<?> recordType) {
		for (RecordComponent component : recordType.getRecordComponents()) {
			Object value;
			try {
				value = component.getAccessor().invoke(record);
			}
			catch (ReflectiveOperationException ex) {
				throw new AssertionError("could not read component " + component.getName(), ex);
			}
			assertThat(value)
				.describedAs("component '%s' is left at its default, so this fixture cannot detect a toBuilder() "
						+ "that drops it", component.getName())
				.isNotNull()
				.isNotEqualTo(false)
				.isNotEqualTo(0.0d)
				.isNotEqualTo("")
				.isNotEqualTo(List.of());
		}
	}

}
