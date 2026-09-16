package com.patbaumgartner.embabel.workflow.visualizer;

import com.patbaumgartner.embabel.workflow.visualizer.WorkflowModels.AgentWorkflow;
import com.patbaumgartner.embabel.workflow.visualizer.WorkflowModels.FlowEdge;
import com.patbaumgartner.embabel.workflow.visualizer.WorkflowModels.FlowNode;
import com.patbaumgartner.embabel.workflow.visualizer.WorkflowModels.FlowPath;
import com.patbaumgartner.embabel.workflow.visualizer.WorkflowModels.WorkflowCatalog;
import com.patbaumgartner.embabel.workflow.visualizer.WorkflowModels.WorkflowFlow;
import com.patbaumgartner.embabel.workflow.visualizer.WorkflowModels.WorkflowStep;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

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

	// -------------------------------------------------------------------------
	// Derived flow, against the steps the live platform really registers
	// -------------------------------------------------------------------------

	/**
	 * Two conditions split the flow; each branch ends in its own goal, and GOAP prefers
	 * the cheaper.
	 */
	@Test
	void aBranchingAgentHasOneRoutePerGoalWithItsCostsSummed() {
		WorkflowFlow flow = agent("LoanApplicationAgent").flow();

		assertThat(flow.entryTypes()).containsExactly("LoanRequest");
		assertThat(flow.paths()).extracting(FlowPath::goal, FlowPath::steps, FlowPath::totalCost, FlowPath::cheapest)
			.containsExactly(tuple("makeAutoDecision", List.of("analyzeCreditProfile", "makeAutoDecision"), 3.0, true),
					tuple("makeUnderwrittenDecision",
							List.of("analyzeCreditProfile", "conductUnderwriting", "makeUnderwrittenDecision"), 9.0,
							false));
		assertThat(node(flow, "decision:analyzeCreditProfile").detail()).isEqualTo("CONDITION");
		assertThat(edge(flow, "decision:analyzeCreditProfile", "step:makeAutoDecision").conditions())
			.containsExactly("canAutoDecide");
	}

	/**
	 * The synthetic supervisor produces the goal's type and so reaches the goal; the
	 * declared action the planner turned into a tool stays beside the chart.
	 */
	@Test
	void aSupervisorAgentReachesItsGoalThroughTheSyntheticAction() {
		WorkflowFlow flow = agent("ProductResearchAgent").flow();

		assertThat(flow.entryTypes()).containsExactly("MarketData", "ResearchRequest");
		assertThat(flow.paths()).extracting(FlowPath::goal, FlowPath::steps)
			.containsExactly(tuple("generateReport", List.of("generateReport")),
					tuple("generateReport", List.of("supervisor")));
		assertThat(flow.nodes()).filteredOn(node -> "DETACHED".equals(node.kind()))
			.extracting(FlowNode::step, FlowNode::detail)
			.containsExactly(tuple("analyzeCompetitors", "NOT_IN_PLAN"));
	}

	/**
	 * The routing action's {@code @State} alternatives are the branches; the UTILITY goal
	 * leads nowhere.
	 */
	@Test
	void aStateRoutingAgentBranchesOnTheStateAndLeavesNirvanaOutside() {
		WorkflowFlow flow = agent("TicketRoutingAgent").flow();

		assertThat(node(flow, "decision:routeToCategory").detail()).isEqualTo("STATE");
		assertThat(edge(flow, "decision:routeToCategory", "step:handleBilling").types())
			.containsExactly("BillingState");
		assertThat(flow.paths()).extracting(FlowPath::goal)
			.containsExactly("handleBilling", "handleGeneral", "handleTechnical");
		assertThat(flow.nodes()).filteredOn(node -> "DETACHED".equals(node.kind()))
			.extracting(FlowNode::step, FlowNode::detail)
			.containsExactly(tuple("Nirvana", "NO_GOAL_PATH"));
	}

	@Test
	void aRevisionLoopIsDrawnAsALoopBackToTheReview() {
		WorkflowFlow flow = agent("StoryWriterAgent").flow();

		FlowEdge loop = edge(flow, "step:reviseDraft", "step:reviewDraft");
		assertThat(loop.loop()).isTrue();
		assertThat(loop.types()).containsExactly("Draft");
		assertThat(node(flow, "decision:reviewDraft").detail()).isEqualTo("PLANNER");
		assertThat(flow.paths()).extracting(FlowPath::steps)
			.containsExactly(List.of("draftStory", "reviewDraft", "finalizeStory"),
					List.of("draftStory", "reviewDraft", "reviseDraft", "finalizeStory"));
	}

	/** Two independent analyses fork from the start and join before the decision. */
	@Test
	void independentAnalysesForkAndJoin() {
		WorkflowFlow flow = agent("ResumeScreeningAgent").flow();

		assertThat(flow.nodes()).extracting(FlowNode::id).contains("fork:start", "join:makeHiringDecision");
		assertThat(flow.paths()).singleElement()
			.extracting(FlowPath::steps)
			.isEqualTo(List.of("analyzeResume", "assessCultureFit", "makeHiringDecision"));
	}

	private static FlowNode node(WorkflowFlow flow, String id) {
		return flow.nodes()
			.stream()
			.filter(node -> id.equals(node.id()))
			.findFirst()
			.orElseThrow(() -> new AssertionError(
					"no node " + id + " in " + flow.nodes().stream().map(FlowNode::id).toList()));
	}

	private static FlowEdge edge(WorkflowFlow flow, String from, String to) {
		return flow.edges()
			.stream()
			.filter(edge -> from.equals(edge.from()) && to.equals(edge.to()))
			.findFirst()
			.orElseThrow(() -> new AssertionError("no edge " + from + " -> " + to + " in "
					+ flow.edges().stream().map(edge -> edge.from() + " -> " + edge.to()).toList()));
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
