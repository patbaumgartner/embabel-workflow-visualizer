package com.patbaumgartner.embabel.workflow.visualizer;

import com.patbaumgartner.embabel.workflow.visualizer.WorkflowModels.AgentWorkflow;
import com.patbaumgartner.embabel.workflow.visualizer.WorkflowModels.WorkflowStep;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowModelsTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	/**
	 * The JSON emitted by {@code /actuator/embabel} and {@code /embabel-workflows/api} is
	 * a public contract consumed by the bundled UI and by third-party tooling. Renaming,
	 * dropping, or adding a property is a breaking change and must be a deliberate one.
	 */
	@Test
	void stepJsonContractIsStable() {
		assertThat(propertiesOf(WorkflowStep.builder("n", "Action", "m").build())).containsOnlyKeys("name", "type",
				"description", "method", "pre", "post", "inputs", "output", "goal", "costMethod", "valueMethod", "cost",
				"value", "goalValue", "possibleOutputs", "canRerun", "readOnly", "outputBinding", "clearBlackboard",
				"tags", "examples", "llmTool", "llmToolDescription", "exportedRemote", "exportName", "trigger",
				"retryPolicy", "llmToolReturnDirect", "llmToolCategory", "actionRetryPolicy", "conditionCost",
				"exportedLocal", "exportStartingInputTypes", "llmToolName", "llmToolMetadata", "providedInputs",
				"nameMatchInputs");
	}

	@Test
	void agentJsonContractIsStable() {
		assertThat(propertiesOf(AgentWorkflow.builder("a", "C").build())).containsOnlyKeys("agentName", "description",
				"version", "plannerType", "opaque", "className", "steps", "provider", "beanName", "scan", "retryPolicy",
				"retryPolicyExpression");
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

}
