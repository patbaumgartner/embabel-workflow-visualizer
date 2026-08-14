package com.patbaumgartner.embabel.workflow.visualizer;

import com.patbaumgartner.embabel.workflow.visualizer.WorkflowModels.AgentWorkflow;
import com.patbaumgartner.embabel.workflow.visualizer.WorkflowModels.WorkflowCatalog;
import com.patbaumgartner.embabel.workflow.visualizer.WorkflowModels.WorkflowStep;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the runtime view against a real Embabel {@code AgentPlatform}.
 *
 * <p>
 * The starter's own tests drive {@link AgentPlatformReader} with platform-shaped fakes,
 * which prove the reflective reading but cannot prove the shape still matches Embabel.
 * This test is the one that fails when Embabel changes its runtime API — the whole point
 * of reading it by name rather than compiling against it.
 */
@SpringBootTest
@DisplayName("Workflow catalog — reconciled against the live agent platform")
class EmbabelWorkflowCatalogRuntimeIntegrationTests {

	@Autowired
	private EmbabelWorkflowCatalogService catalogService;

	@Test
	void everyAnnotatedAgentIsDeployedByThePlatform() {
		WorkflowCatalog catalog = this.catalogService.catalog();

		assertThat(catalog.agents()).isNotEmpty();
		assertThat(catalog.agents()).allSatisfy(agent -> assertThat(agent.registered())
			.describedAs("%s should be reported as deployed", agent.agentName())
			.isTrue());
	}

	/**
	 * The divergence annotations cannot express: a {@code SUPERVISOR} agent's declared
	 * actions are not planner actions. Embabel replaces them with a single synthetic
	 * supervisor action that orchestrates them as tools, so the declared steps must be
	 * shown as not run by the planner, and the supervisor must be shown at all.
	 */
	@Test
	void aSupervisorAgentReportsItsSyntheticActionAndItsUnrunDeclaredSteps() {
		AgentWorkflow research = agent("ProductResearchAgent");

		WorkflowStep supervisor = research.steps()
			.stream()
			.filter(WorkflowStep::plannerGenerated)
			.findFirst()
			.orElseThrow(() -> new AssertionError("no planner-generated step on " + research.agentName()));
		assertThat(supervisor.name()).isEqualTo("supervisor");
		assertThat(supervisor.registered()).isTrue();

		assertThat(step(research, "analyzeCompetitors").registered())
			.describedAs("a SUPERVISOR agent's declared action is a tool, not a planner action")
			.isFalse();
		assertThat(step(research, "generateReport").registered())
			.describedAs("the goal is registered even when the actions are not")
			.isTrue();
	}

	/** The UTILITY planner adds a goal that no annotation declares. */
	@Test
	void aUtilityAgentReportsItsSyntheticGoal() {
		assertThat(agent("TicketRoutingAgent").steps()).filteredOn(WorkflowStep::plannerGenerated)
			.extracting(WorkflowStep::name)
			.contains("Nirvana");
	}

	/**
	 * An {@code @EmbabelComponent} is registered as an agent in its own right, named by
	 * its fully-qualified class name rather than the simple name the annotation scan
	 * uses. Matching has to bridge that.
	 */
	@Test
	void anEmbabelComponentIsMatchedDespiteItsQualifiedRuntimeName() {
		AgentWorkflow utils = agent("ResearchUtils");

		assertThat(utils.registered()).isTrue();
		assertThat(step(utils, "gatherMarketData").registered()).isTrue();
	}

	@Test
	void plainGoapAgentsReconcileWithoutSyntheticStepsOrDrift() {
		AgentWorkflow fraud = agent("FraudDetectionAgent");

		assertThat(fraud.steps()).noneMatch(WorkflowStep::plannerGenerated);
		assertThat(fraud.steps()).extracting(WorkflowStep::registered).containsOnly(true);
	}

	private AgentWorkflow agent(String name) {
		List<AgentWorkflow> agents = this.catalogService.catalog().agents();
		return agents.stream()
			.filter(candidate -> name.equals(candidate.agentName()))
			.findFirst()
			.orElseThrow(() -> new AssertionError(
					"no agent named " + name + " in " + agents.stream().map(AgentWorkflow::agentName).toList()));
	}

	private WorkflowStep step(AgentWorkflow agent, String method) {
		return agent.steps()
			.stream()
			.filter(step -> method.equals(step.method()))
			.findFirst()
			.orElseThrow(() -> new AssertionError("no step " + method + " on " + agent.agentName()));
	}

}
