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
