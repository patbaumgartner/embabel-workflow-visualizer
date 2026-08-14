package com.patbaumgartner.embabel.workflow.visualizer;

import com.patbaumgartner.embabel.workflow.visualizer.AgentPlatformReader.RuntimeAgent;
import com.patbaumgartner.embabel.workflow.visualizer.AgentPlatformReader.RuntimeStep;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AgentPlatformReaderTests {

	private final AgentPlatformReader reader = new AgentPlatformReader(new AnnotationConfigApplicationContext());

	@Test
	void readsAgentsActionsGoalsAndConditions() {
		FakeAgentPlatform.Platform platform = new FakeAgentPlatform.Platform(
				List.of(new FakeAgentPlatform.Agent("com.example.ReviewAgent", "Reviews things", "acme", true,
						List.of(new FakeAgentPlatform.Action("com.example.ReviewAgent.screen", "Screen it",
								FakeAgentPlatform.IoBinding.of("it:com.example.Request"),
								FakeAgentPlatform.IoBinding.of("screening:com.example.Screening"), Map.of(), Map.of(),
								true, true)),
						Set.of(new FakeAgentPlatform.Goal("com.example.ReviewAgent.verdict", "A verdict", Set.of(),
								new FakeAgentPlatform.DomainType("com.example.Verdict"))),
						Set.of(new FakeAgentPlatform.Condition("isClean", "")))));

		List<RuntimeAgent> agents = this.reader.readAgentsFrom(platform);

		assertThat(agents).singleElement().satisfies(agent -> {
			assertThat(agent.name()).isEqualTo("com.example.ReviewAgent");
			assertThat(agent.description()).isEqualTo("Reviews things");
			assertThat(agent.provider()).isEqualTo("acme");
			assertThat(agent.opaque()).isTrue();
		});
		assertThat(agents.get(0).steps())
			.extracting(RuntimeStep::simpleStepName, RuntimeStep::type, RuntimeStep::output)
			.containsExactlyInAnyOrder(org.assertj.core.api.Assertions.tuple("screen", "Action", "Screening"),
					org.assertj.core.api.Assertions.tuple("verdict", "AchievesGoal", "Verdict"),
					org.assertj.core.api.Assertions.tuple("isClean", "Condition", "boolean"));
	}

	/** {@code IoBinding} values are {@code "bindingName:fully.Qualified.Type"}. */
	@Test
	void unwrapsIoBindingsIntoSimpleTypeNames() {
		RuntimeStep action = readSingleAction(new FakeAgentPlatform.Action("a", "",
				FakeAgentPlatform.IoBinding.of("it:com.example.Request"),
				FakeAgentPlatform.IoBinding.of("marketData:com.example.MarketData"), Map.of(), Map.of(), false, false));

		assertThat(action.inputs()).containsExactly("Request");
		assertThat(action.output()).isEqualTo("MarketData");
	}

	/**
	 * A runtime binding names a nested type in binary form; the annotation scan reports
	 * the simple name. If they disagree the diagram cannot connect the two steps.
	 */
	@Test
	void unwrapsNestedTypesTheSameWayTheAnnotationScanDoes() {
		RuntimeStep action = readSingleAction(new FakeAgentPlatform.Action("a", "",
				FakeAgentPlatform.IoBinding.of("it:com.example.ResearchModels$ResearchRequest"),
				FakeAgentPlatform.IoBinding.of("it:com.example.ResearchModels$ResearchReport"), Map.of(), Map.of(),
				false, false));

		assertThat(action.inputs()).containsExactly("ResearchRequest");
		assertThat(action.output()).isEqualTo("ResearchReport");
	}

	@Test
	void anActionWithoutOutputsIsVoid() {
		assertThat(readSingleAction(FakeAgentPlatform.Action.named("a")).output()).isEqualTo("void");
	}

	/**
	 * Runtime pre-conditions and effects carry planner bookkeeping that describes no part
	 * of the authored workflow: {@code hasRun_*} sequences actions,
	 * {@code __unobtanium__} makes a goal deliberately unreachable, and
	 * {@code binding:type} entries merely restate the step's own inputs and outputs.
	 */
	@Test
	void dropsPlannerBookkeepingFromConditions() {
		RuntimeStep action = readSingleAction(new FakeAgentPlatform.Action("a", "", Set.of(), Set.of(),
				linkedKeys("it:com.example.Request", "hasRun_com.example.Agent.a", "isClean"),
				linkedKeys("__unobtanium__", "it:java.lang.Record", "hasScreened"), false, false));

		assertThat(action.pre()).containsExactly("isClean");
		assertThat(action.post()).containsExactly("hasScreened");
	}

	@Test
	void reportsNothingWhenThePlatformIsNotShapedAsExpected() {
		assertThat(this.reader.readAgentsFrom("not a platform")).isEmpty();
		assertThat(this.reader.readAgentsFrom(new Object())).isEmpty();
	}

	/**
	 * The catalog must degrade to the declared view rather than fail when no agent
	 * platform is present — the starter is usable without Embabel on the classpath.
	 */
	@Test
	void reportsNothingWithoutAnAgentPlatformBean() {
		try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
			ctx.refresh();

			assertThat(new AgentPlatformReader(ctx).readAgents()).isEmpty();
		}
	}

	@Test
	void stripsEmbabelsQualifierFromRuntimeNames() {
		assertThat(AgentPlatformReader.simpleName("com.example.ReviewAgent.screen")).isEqualTo("screen");
		assertThat(AgentPlatformReader.simpleName("BillingState.handleBilling")).isEqualTo("handleBilling");
		assertThat(AgentPlatformReader.simpleName("Nirvana")).isEqualTo("Nirvana");
	}

	private RuntimeStep readSingleAction(FakeAgentPlatform.Action action) {
		FakeAgentPlatform.Platform platform = new FakeAgentPlatform.Platform(
				List.of(FakeAgentPlatform.Agent.named("agent", List.of(action), Set.of())));
		return this.reader.readAgentsFrom(platform).get(0).steps().get(0);
	}

	private Map<String, String> linkedKeys(String... keys) {
		java.util.LinkedHashMap<String, String> map = new java.util.LinkedHashMap<>();
		for (String key : keys) {
			map.put(key, "TRUE");
		}
		return map;
	}

}
