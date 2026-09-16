package com.patbaumgartner.embabel.workflow.visualizer;

import com.patbaumgartner.embabel.workflow.visualizer.WorkflowModels.AgentWorkflow;
import com.patbaumgartner.embabel.workflow.visualizer.WorkflowModels.FlowEdge;
import com.patbaumgartner.embabel.workflow.visualizer.WorkflowModels.FlowNode;
import com.patbaumgartner.embabel.workflow.visualizer.WorkflowModels.FlowPath;
import com.patbaumgartner.embabel.workflow.visualizer.WorkflowModels.WorkflowFlow;
import com.patbaumgartner.embabel.workflow.visualizer.WorkflowModels.WorkflowStep;
import com.patbaumgartner.embabel.workflow.visualizer.WorkflowModels.WorkflowStep.Builder;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * The flow is derived from steps alone, so each scenario below is an agent shaped like
 * one of the sample application's, reduced to the inputs, outputs and conditions that
 * shape it.
 */
class WorkflowFlowBuilderTests {

	private final WorkflowFlowBuilder builder = new WorkflowFlowBuilder();

	// -------------------------------------------------------------------------
	// Straight lines
	// -------------------------------------------------------------------------

	@Test
	void aLinearPipelineIsOneRouteFromStartToEnd() {
		WorkflowFlow flow = flowOf(action("enrich").inputs(List.of("Request")).output("Context"),
				action("screen").inputs(List.of("Context")).output("Screening"),
				goal("decide").inputs(List.of("Screening")).output("Decision"));

		assertThat(flow.entryTypes()).containsExactly("Request");
		assertThat(flow.nodes()).extracting(FlowNode::id)
			.containsExactly("start", "step:decide", "step:enrich", "step:screen", "end:decide");
		assertThat(node(flow, "end:decide").label()).isEqualTo("Decision");
		assertThat(flow.edges()).extracting(FlowEdge::from, FlowEdge::to, FlowEdge::types)
			.containsExactly(tuple("start", "step:enrich", List.of("Request")),
					tuple("step:enrich", "step:screen", List.of("Context")),
					tuple("step:screen", "step:decide", List.of("Screening")),
					tuple("step:decide", "end:decide", List.of("Decision")));
		assertThat(flow.edges()).allSatisfy(edge -> assertThat(edge.paths()).containsExactly(0));
		assertThat(flow.paths())
			.containsExactly(new FlowPath("decide", List.of("enrich", "screen", "decide"), null, false, false));
		assertThat(flow.truncated()).isFalse();
	}

	@Test
	void anAgentWithoutStepsIsJustAStart() {
		WorkflowFlow flow = flowOf();

		assertThat(flow.entryTypes()).isEmpty();
		assertThat(flow.nodes()).extracting(FlowNode::kind).containsExactly("START");
		assertThat(flow.edges()).isEmpty();
		assertThat(flow.paths()).isEmpty();
	}

	@Test
	void anInputTheBlackboardNeverGetsCannotStartAGoal() {
		WorkflowFlow flow = flowOf(action("prepare").inputs(List.of("Request")).output("Prepared"),
				goal("finish").inputs(List.of("Prepared", "Approval")).output("Result"));

		assertThat(flow.entryTypes()).containsExactly("Approval", "Request");
		assertThat(flow.paths()).singleElement().extracting(FlowPath::steps).isEqualTo(List.of("prepare", "finish"));
		assertThat(edge(flow, "start", "step:prepare").types()).containsExactly("Request");
	}

	// -------------------------------------------------------------------------
	// Decisions
	// -------------------------------------------------------------------------

	/**
	 * Shaped like {@code LoanApplicationAgent}: two conditions, a goal on each branch.
	 */
	@Test
	void postedConditionsBecomeADecisionWhoseBranchesCarryThem() {
		WorkflowFlow flow = flowOf(
				action("analyze").inputs(List.of("Request"))
					.output("Analysis")
					.post(List.of("canAutoDecide", "requiresUnderwriting"))
					.cost(2.0),
				condition("canAutoDecide"), condition("requiresUnderwriting"),
				action("underwrite").inputs(List.of("Analysis"))
					.output("Assessment")
					.pre(List.of("requiresUnderwriting"))
					.cost(5.0),
				goal("decideAutomatically").inputs(List.of("Analysis", "Request"))
					.output("Decision")
					.pre(List.of("canAutoDecide"))
					.cost(1.0),
				goal("decideUnderwritten").inputs(List.of("Analysis", "Assessment", "Request"))
					.output("Decision")
					.pre(List.of("requiresUnderwriting"))
					.cost(2.0));

		FlowNode decision = node(flow, "decision:analyze");
		assertThat(decision.kind()).isEqualTo("DECISION");
		assertThat(decision.detail()).isEqualTo("CONDITION");
		assertThat(decision.label()).isEqualTo("condition");
		assertThat(edge(flow, "step:analyze", "decision:analyze").types()).containsExactly("Analysis");
		assertThat(edge(flow, "decision:analyze", "step:decideAutomatically").conditions())
			.containsExactly("canAutoDecide");
		assertThat(edge(flow, "decision:analyze", "step:underwrite").conditions())
			.containsExactly("requiresUnderwriting");
		assertThat(edge(flow, "step:underwrite", "step:decideUnderwritten").types()).containsExactly("Assessment");
		// the analysis reaches the underwritten decision through the underwriting step
		assertThat(flow.edges())
			.noneMatch(edge -> edge.to().equals("step:decideUnderwritten") && !edge.from().equals("step:underwrite"));
		assertThat(flow.nodes()).extracting(FlowNode::kind).doesNotContain("FORK", "JOIN");
		assertThat(flow.nodes()).filteredOn(node -> "END".equals(node.kind()))
			.extracting(FlowNode::step, FlowNode::label)
			.containsExactly(tuple("decideAutomatically", "Decision"), tuple("decideUnderwritten", "Decision"));
	}

	@Test
	void theCheapestRouteIsMarkedAndCostsAreSummedPerRoute() {
		WorkflowFlow flow = flowOf(
				action("analyze").inputs(List.of("Request"))
					.output("Analysis")
					.post(List.of("auto", "manual"))
					.cost(2.0),
				action("underwrite").inputs(List.of("Analysis")).output("Assessment").pre(List.of("manual")).cost(5.0),
				goal("decideAutomatically").inputs(List.of("Analysis"))
					.output("Decision")
					.pre(List.of("auto"))
					.cost(1.0),
				goal("decideUnderwritten").inputs(List.of("Assessment"))
					.output("Decision")
					.pre(List.of("manual"))
					.cost(2.0));

		assertThat(flow.paths()).containsExactly(
				new FlowPath("decideAutomatically", List.of("analyze", "decideAutomatically"), 3.0, false, true),
				new FlowPath("decideUnderwritten", List.of("analyze", "underwrite", "decideUnderwritten"), 9.0, false,
						false));
		assertThat(edge(flow, "decision:analyze", "step:decideAutomatically").paths()).containsExactly(0);
		assertThat(edge(flow, "decision:analyze", "step:underwrite").paths()).containsExactly(1);
		assertThat(edge(flow, "step:analyze", "decision:analyze").paths()).containsExactly(0, 1);
	}

	@Test
	void nothingIsMarkedCheapestWhenEveryRouteCostsTheSame() {
		WorkflowFlow flow = flowOf(
				action("inspect").inputs(List.of("Request")).output("Inspection").post(List.of("fast", "slow")),
				goal("completeFast").inputs(List.of("Inspection")).output("Result").pre(List.of("fast")),
				goal("completeSlow").inputs(List.of("Inspection")).output("Result").pre(List.of("slow")));

		assertThat(flow.paths()).hasSize(2).noneMatch(FlowPath::cheapest);
		assertThat(flow.paths()).extracting(FlowPath::totalCost).containsOnlyNulls();
	}

	@Test
	void aCostMethodMakesTheRouteTotalALowerBound() {
		WorkflowFlow flow = flowOf(cost("deepCost"),
				action("classify").inputs(List.of("Feedback")).output("Classification").cost(1.0),
				action("deepAnalyze").inputs(List.of("Classification")).output("Insight").costMethod("deepCost"),
				goal("respond").inputs(List.of("Insight")).output("Response"));

		assertThat(flow.paths()).singleElement()
			.extracting(FlowPath::totalCost, FlowPath::dynamicCost)
			.containsExactly(1.0, true);
		// a @Cost function is not a step the planner runs, so it is neither in the flow
		// nor beside it
		assertThat(flow.nodes()).extracting(FlowNode::step).doesNotContain("deepCost");
	}

	/**
	 * Shaped like {@code ContentModerationAgent}: both branches produce the goal's input.
	 */
	@Test
	void branchesThatProduceTheSameTypeMergeWithoutAJoin() {
		WorkflowFlow flow = flowOf(
				action("analyze").inputs(List.of("Request")).output("Analysis").post(List.of("clear", "flagged")),
				action("autoTag").inputs(List.of("Analysis")).output("Tagged").pre(List.of("clear")),
				action("deepReview").inputs(List.of("Analysis")).output("Tagged").pre(List.of("flagged")),
				goal("record").inputs(List.of("Tagged")).output("Decision"));

		assertThat(flow.nodes()).extracting(FlowNode::kind).doesNotContain("JOIN");
		assertThat(flow.edges()).filteredOn(edge -> edge.to().equals("step:record"))
			.extracting(FlowEdge::from, FlowEdge::paths)
			.containsExactlyInAnyOrder(tuple("step:autoTag", List.of(0)), tuple("step:deepReview", List.of(1)));
		assertThat(edge(flow, "step:record", "end:record").paths()).containsExactly(0, 1);
	}

	/**
	 * Shaped like {@code TicketRoutingAgent}: a routing action returning one of several
	 * states.
	 */
	@Test
	void stateRoutingIsADecisionWithOneBranchPerState() {
		WorkflowFlow flow = flowOf(action("classify").inputs(List.of("Ticket")).output("Classification"),
				action("route").inputs(List.of("Ticket", "Classification"))
					.output("Category")
					.possibleOutputs(List.of("BillingState", "TechnicalState")),
				goal("handleBilling").inputs(List.of("BillingState")).output("Resolution"),
				goal("handleTechnical").inputs(List.of("TechnicalState")).output("Resolution"));

		FlowNode decision = node(flow, "decision:route");
		assertThat(decision.detail()).isEqualTo("STATE");
		assertThat(decision.label()).isEqualTo("state");
		assertThat(edge(flow, "step:route", "decision:route").types()).containsExactly("Category");
		assertThat(edge(flow, "decision:route", "step:handleBilling").types()).containsExactly("BillingState");
		assertThat(edge(flow, "decision:route", "step:handleTechnical").types()).containsExactly("TechnicalState");
		assertThat(flow.paths()).extracting(FlowPath::steps)
			.containsExactly(List.of("classify", "route", "handleBilling"),
					List.of("classify", "route", "handleTechnical"));
	}

	/**
	 * Shaped like {@code StoryWriterAgent}: revise or finalize, both on the same
	 * precondition.
	 */
	@Test
	void aSharedPreconditionNobodyPostsIsAPlannerChoiceGuardedOnBothBranches() {
		WorkflowFlow flow = flowOf(action("draft").inputs(List.of("Request")).output("Draft"),
				action("review").inputs(List.of("Request", "Draft")).output("Review"),
				action("revise").inputs(List.of("Request", "Draft", "Review"))
					.output("Draft")
					.pre(List.of("Review"))
					.canRerun(true),
				goal("finalize").inputs(List.of("Request", "Draft", "Review")).output("Story").pre(List.of("Review")));

		FlowNode decision = node(flow, "decision:review");
		assertThat(decision.detail()).isEqualTo("PLANNER");
		assertThat(decision.label()).isEqualTo("planner choice");
		assertThat(edge(flow, "decision:review", "step:finalize").conditions()).containsExactly("Review");
		assertThat(edge(flow, "decision:review", "step:revise").conditions()).containsExactly("Review");
		assertThat(edge(flow, "step:revise", "step:finalize").types()).containsExactly("Draft");
		assertThat(flow.paths()).extracting(FlowPath::steps)
			.containsExactly(List.of("draft", "review", "finalize"), List.of("draft", "review", "revise", "finalize"));
	}

	@Test
	void aRerunnableActionFeedingAnEarlierStepIsALoopThatBelongsToNoRoute() {
		WorkflowFlow flow = flowOf(action("draft").inputs(List.of("Request")).output("Draft"),
				action("review").inputs(List.of("Draft")).output("Review"),
				action("revise").inputs(List.of("Draft", "Review")).output("Draft").canRerun(true),
				goal("finalize").inputs(List.of("Draft", "Review")).output("Story"));

		FlowEdge loop = edge(flow, "step:revise", "step:review");
		assertThat(loop.loop()).isTrue();
		assertThat(loop.types()).containsExactly("Draft");
		assertThat(loop.paths()).isEmpty();
		assertThat(flow.edges()).filteredOn(FlowEdge::loop).hasSize(1);
	}

	@Test
	void anActionThatCannotRerunDoesNotLoop() {
		WorkflowFlow flow = flowOf(action("draft").inputs(List.of("Request")).output("Draft"),
				action("review").inputs(List.of("Draft")).output("Review"),
				action("revise").inputs(List.of("Draft", "Review")).output("Draft"),
				goal("finalize").inputs(List.of("Draft", "Review")).output("Story"));

		assertThat(flow.edges()).noneMatch(FlowEdge::loop);
	}

	/**
	 * Shaped like {@code ProductResearchAgent} run by GOAP: a SpEL gate and a nullable
	 * input.
	 */
	@Test
	void aSpelGateOnAnOptionalStepIsADecisionLabelledWithTheExpression() {
		WorkflowFlow flow = flowOf(action("gather").inputs(List.of("Request")).output("MarketData"),
				action("analyzeCompetitors").inputs(List.of("Request", "MarketData"))
					.output("Competitors")
					.pre(List.of("spel:marketData.confidenceScore > 0.6")),
				goal("report").inputs(List.of("Request", "MarketData", "Competitors"))
					.output("Report")
					.optionalInputs(List.of("Competitors")));

		assertThat(flow.entryTypes()).containsExactly("Request");
		FlowNode decision = node(flow, "decision:gather");
		assertThat(decision.detail()).isEqualTo("SPEL");
		assertThat(decision.label()).isEqualTo("marketData.confidenceScore > 0.6");
		assertThat(edge(flow, "decision:gather", "step:analyzeCompetitors").conditions())
			.containsExactly("spel:marketData.confidenceScore > 0.6");
		assertThat(edge(flow, "decision:gather", "step:report").conditions()).isEmpty();
		assertThat(flow.paths()).extracting(FlowPath::steps)
			.containsExactly(List.of("gather", "report"), List.of("gather", "analyzeCompetitors", "report"));
	}

	@Test
	void aConditionNobodyPostsGuardsTheEdgeIntoTheStepWithoutADecision() {
		WorkflowFlow flow = flowOf(condition("isReady"),
				action("prepare").inputs(List.of("Request")).output("Prepared"),
				goal("finish").inputs(List.of("Prepared")).output("Result").pre(List.of("isReady")));

		assertThat(flow.nodes()).extracting(FlowNode::kind).doesNotContain("DECISION");
		assertThat(edge(flow, "step:prepare", "step:finish").conditions()).containsExactly("isReady");
	}

	/**
	 * The edge from the poster is implied by a longer route that does not carry the
	 * condition, so the condition has to survive as a guard on the consumer.
	 */
	@Test
	void aConditionOnAnImpliedEdgeMovesOntoTheConsumer() {
		WorkflowFlow flow = flowOf(
				action("screen").inputs(List.of("Request")).output("Screening").post(List.of("approved")),
				action("enrich").inputs(List.of("Screening")).output("Enriched"),
				goal("finish").inputs(List.of("Screening", "Enriched")).output("Result").pre(List.of("approved")));

		assertThat(flow.edges())
			.noneMatch(edge -> edge.from().equals("step:screen") && edge.to().equals("step:finish"));
		assertThat(edge(flow, "step:enrich", "step:finish").conditions()).containsExactly("approved");
	}

	// -------------------------------------------------------------------------
	// Forks and joins
	// -------------------------------------------------------------------------

	/** Shaped like {@code ResumeScreeningAgent}: two independent analyses, one goal. */
	@Test
	void independentActionsForkFromTheStartAndJoinBeforeTheirConsumer() {
		WorkflowFlow flow = flowOf(action("analyzeResume").inputs(List.of("Candidate")).output("Resume"),
				action("assessCulture").inputs(List.of("Candidate")).output("Culture"),
				goal("decide").inputs(List.of("Resume", "Culture", "Candidate")).output("Hiring"));

		assertThat(node(flow, "fork:start").kind()).isEqualTo("FORK");
		assertThat(node(flow, "join:decide").kind()).isEqualTo("JOIN");
		assertThat(edge(flow, "start", "fork:start").types()).containsExactly("Candidate");
		assertThat(edge(flow, "fork:start", "step:analyzeResume").types()).isEmpty();
		assertThat(edge(flow, "step:analyzeResume", "join:decide").types()).containsExactly("Resume");
		assertThat(edge(flow, "step:assessCulture", "join:decide").types()).containsExactly("Culture");
		assertThat(edge(flow, "join:decide", "step:decide").paths()).containsExactly(0);
		assertThat(flow.paths()).singleElement()
			.extracting(FlowPath::steps)
			.isEqualTo(List.of("analyzeResume", "assessCulture", "decide"));
	}

	/**
	 * Shaped like {@code DocumentProcessingAgent}: a default producer beside the main
	 * line.
	 */
	@Test
	void aForkedActionIsDrawnOnceEvenWhenSeveralStepsConsumeIt() {
		WorkflowFlow flow = flowOf(action("preprocess").inputs(List.of("Request")).output("Clean"),
				action("defaultHints").inputs(List.of("Request")).output("Hints"),
				action("extract").inputs(List.of("Clean", "Hints")).output("Metadata"),
				action("analyze").inputs(List.of("Clean", "Metadata")).output("Analysis").canRerun(true),
				goal("summarize").inputs(List.of("Clean", "Metadata", "Analysis")).output("Summary"));

		assertThat(flow.edges()).filteredOn(edge -> edge.from().equals("step:preprocess"))
			.extracting(FlowEdge::to)
			.containsExactly("join:extract");
		assertThat(flow.edges()).filteredOn(edge -> !edge.loop() && edge.to().equals("step:summarize"))
			.extracting(FlowEdge::from)
			.containsExactly("step:analyze");
		// canRerun without an upstream consumer of its output is no loop
		assertThat(flow.edges()).noneMatch(FlowEdge::loop);
	}

	/**
	 * A successor every plan takes and one only some take, from the same action: the fork
	 * carries the decision as one of its branches.
	 */
	@Test
	void anAlwaysBranchAndASometimesBranchFromOneActionForkIntoADecision() {
		WorkflowFlow flow = flowOf(action("begin").inputs(List.of("Request")).output("Base"),
				action("mandatory").inputs(List.of("Base")).output("Core"),
				action("extra").inputs(List.of("Base")).output("Bonus"),
				goal("finish").inputs(List.of("Core", "Bonus")).output("Result").optionalInputs(List.of("Bonus")));

		assertThat(node(flow, "fork:begin").kind()).isEqualTo("FORK");
		assertThat(node(flow, "decision:fork:begin").detail()).isEqualTo("PLANNER");
		assertThat(edge(flow, "step:begin", "fork:begin").types()).containsExactly("Base");
		assertThat(edge(flow, "fork:begin", "step:mandatory").paths()).containsExactly(0, 1);
		assertThat(edge(flow, "decision:fork:begin", "step:extra").paths()).containsExactly(1);
		assertThat(flow.paths()).extracting(FlowPath::steps)
			.containsExactly(List.of("begin", "mandatory", "finish"), List.of("begin", "extra", "mandatory", "finish"));
	}

	// -------------------------------------------------------------------------
	// Goals, entry types and what stays outside
	// -------------------------------------------------------------------------

	/**
	 * Shaped like {@code ProductResearchAgent} under the SUPERVISOR planner: the declared
	 * action is not registered, and the synthetic supervisor produces the goal's type.
	 */
	@Test
	void anyActionProducingTheGoalTypeReachesTheGoalAndUnregisteredStepsStayOutside() {
		WorkflowFlow flow = flowOf(
				action("analyzeCompetitors").inputs(List.of("Request", "MarketData"))
					.output("Competitors")
					.pre(List.of("spel:marketData.confidenceScore > 0.6"))
					.registered(false),
				goal("generateReport").inputs(List.of("Request", "MarketData", "Competitors"))
					.output("Report")
					.optionalInputs(List.of("Competitors"))
					.registered(true),
				action("supervisor").inputs(List.of("Request", "MarketData"))
					.output("Report")
					.registered(true)
					.plannerGenerated(true));

		assertThat(flow.entryTypes()).containsExactly("MarketData", "Request");
		assertThat(edge(flow, "step:supervisor", "end:generateReport").types()).containsExactly("Report");
		assertThat(edge(flow, "step:generateReport", "end:generateReport").types()).containsExactly("Report");
		assertThat(node(flow, "decision:start").detail()).isEqualTo("PLANNER");
		assertThat(flow.paths()).extracting(FlowPath::goal, FlowPath::steps)
			.containsExactly(tuple("generateReport", List.of("generateReport")),
					tuple("generateReport", List.of("supervisor")));
		assertThat(detached(flow)).containsExactly(tuple("analyzeCompetitors", "not in plan", "NOT_IN_PLAN"));
		assertThat(flow.edges()).noneMatch(edge -> edge.to().equals("step:analyzeCompetitors"));
	}

	@Test
	void aGoalActionReachingAnotherGoalsTypeIsNotDrawnAsThatGoal() {
		WorkflowFlow flow = flowOf(goal("first").inputs(List.of("Request")).output("Result"),
				goal("second").inputs(List.of("Request")).output("Result"));

		assertThat(flow.edges()).filteredOn(edge -> edge.to().startsWith("end:"))
			.extracting(FlowEdge::from, FlowEdge::to)
			.containsExactlyInAnyOrder(tuple("step:first", "end:first"), tuple("step:second", "end:second"));
	}

	/**
	 * Shaped like the UTILITY planner's synthetic {@code Nirvana} goal, and a dead end.
	 */
	@Test
	void stepsOnNoRouteToAGoalAreDetachedWithTheirReason() {
		WorkflowFlow flow = flowOf(action("classify").inputs(List.of("Ticket")).output("Classification"),
				goal("handle").inputs(List.of("Classification")).output("Resolution"),
				action("describe").inputs(List.of("Ticket")).output("Object"),
				goal("Nirvana").output("void").registered(true).plannerGenerated(true));

		assertThat(flow.paths()).singleElement().extracting(FlowPath::goal).isEqualTo("handle");
		assertThat(detached(flow)).containsExactly(tuple("describe", "no goal path", "NO_GOAL_PATH"),
				tuple("Nirvana", "no goal path", "NO_GOAL_PATH"));
		assertThat(flow.nodes()).filteredOn(node -> "DETACHED".equals(node.kind()))
			.extracting(FlowNode::id)
			.containsExactly("step:describe", "step:Nirvana");
	}

	@Test
	void anAgentWithNoGoalHasNoRoutesAndEveryActionBesideTheChart() {
		WorkflowFlow flow = flowOf(action("gatherMarketData").inputs(List.of("Request")).output("MarketData"));

		assertThat(flow.paths()).isEmpty();
		assertThat(flow.edges()).isEmpty();
		assertThat(detached(flow)).containsExactly(tuple("gatherMarketData", "no goal path", "NO_GOAL_PATH"));
	}

	/**
	 * {@code @Export(startingInputTypes)} says what the caller supplies, so it replaces
	 * the inferred entry types — including for the actions that used to start the flow.
	 */
	@Test
	void declaredStartingInputTypesOverrideTheInferredEntryTypes() {
		WorkflowFlow flow = flowOf(action("draft").inputs(List.of("Request")).output("Draft"),
				goal("publish").inputs(List.of("Draft"))
					.output("Published")
					.exportStartingInputTypes(List.of("Draft")));

		assertThat(flow.entryTypes()).containsExactly("Draft");
		assertThat(flow.paths()).singleElement().extracting(FlowPath::steps).isEqualTo(List.of("publish"));
		assertThat(edge(flow, "start", "step:publish").types()).containsExactly("Draft");
		assertThat(detached(flow)).containsExactly(tuple("draft", "no goal path", "NO_GOAL_PATH"));
	}

	@Test
	void providedInputsAreNeitherEntryTypesNorSomethingToProduce() {
		WorkflowFlow flow = flowOf(goal("bind").inputs(List.of("Draft", "Notes"))
			.output("Result")
			.providedInputs(List.of("Draft"))
			.optionalInputs(List.of("Draft")));

		assertThat(flow.entryTypes()).containsExactly("Notes");
		assertThat(edge(flow, "start", "step:bind").types()).containsExactly("Notes");
		assertThat(flow.paths()).singleElement().extracting(FlowPath::steps).isEqualTo(List.of("bind"));
	}

	@Test
	void conditionsCostFunctionsAndToolsAreNeverPartOfTheChart() {
		WorkflowFlow flow = flowOf(condition("ready"), cost("price"), llmTool("lookup"),
				goal("finish").inputs(List.of("Request")).output("Result"));

		assertThat(flow.nodes()).extracting(FlowNode::step).containsOnly(null, "finish");
	}

	/**
	 * Two routers that can each produce either state reach the same action set by four
	 * assignments; drawn, they are three charts, and the duplicate is not a fourth route.
	 */
	@Test
	void twoProducersOfTheSameTypesReachOneActionSetOnlyOnce() {
		WorkflowFlow flow = flowOf(
				action("routeA").inputs(List.of("Request")).output("Route").possibleOutputs(List.of("Left", "Right")),
				action("routeB").inputs(List.of("Request")).output("Route").possibleOutputs(List.of("Left", "Right")),
				goal("finish").inputs(List.of("Left", "Right")).output("Result"));

		assertThat(flow.paths()).extracting(FlowPath::steps)
			.containsExactly(List.of("routeA", "finish"), List.of("routeB", "finish"),
					List.of("routeA", "routeB", "finish"));
		assertThat(flow.paths()).doesNotHaveDuplicates();
	}

	@Test
	void enumerationStopsAtTheCapAndSaysSo() {
		List<Builder> steps = new ArrayList<>();
		List<String> needs = new ArrayList<>();
		// seven inputs with two producers each admit 2^7 = 128 routes
		IntStream.range(0, 7).forEach(i -> {
			steps.add(action("a" + i).inputs(List.of("Request")).output("T" + i));
			steps.add(action("b" + i).inputs(List.of("Request")).output("T" + i));
			needs.add("T" + i);
		});
		steps.add(goal("finish").inputs(needs).output("Result"));

		WorkflowFlow flow = flowOf(steps.toArray(Builder[]::new));

		assertThat(flow.truncated()).isTrue();
		assertThat(flow.paths()).hasSize(WorkflowFlowBuilder.MAX_PATHS);
	}

	@Test
	void anUnknownRegistrationIsSimulatedLikeARegisteredStep() {
		WorkflowFlow flow = flowOf(goal("finish").inputs(List.of("Request")).output("Result").registered(null));

		assertThat(flow.paths()).hasSize(1);
	}

	// -------------------------------------------------------------------------
	// Helpers
	// -------------------------------------------------------------------------

	private WorkflowFlow flowOf(Builder... steps) {
		List<WorkflowStep> built = new ArrayList<>();
		for (Builder step : steps) {
			built.add(step.build());
		}
		built.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a.name(), b.name()));
		return this.builder.build(AgentWorkflow.builder("agent", "com.example.Agent").steps(built).build());
	}

	private static Builder action(String name) {
		return WorkflowStep.builder(name, "Action", name);
	}

	private static Builder goal(String name) {
		return WorkflowStep.builder(name, "AchievesGoal", name).goal(true);
	}

	private static Builder condition(String name) {
		return WorkflowStep.builder(name, "Condition", name).output("boolean");
	}

	private static Builder cost(String name) {
		return WorkflowStep.builder(name, "Cost", name).output("double");
	}

	private static Builder llmTool(String name) {
		return WorkflowStep.builder(name, "LlmTool", name).llmTool(true).output("String");
	}

	private static FlowNode node(WorkflowFlow flow, String id) {
		return flow.nodes()
			.stream()
			.filter(node -> node.id().equals(id))
			.findFirst()
			.orElseThrow(() -> new AssertionError(
					"no node " + id + " in " + flow.nodes().stream().map(FlowNode::id).toList()));
	}

	private static FlowEdge edge(WorkflowFlow flow, String from, String to) {
		return flow.edges()
			.stream()
			.filter(edge -> edge.from().equals(from) && edge.to().equals(to))
			.findFirst()
			.orElseThrow(() -> new AssertionError("no edge " + from + " -> " + to + " in "
					+ flow.edges().stream().map(edge -> edge.from() + " -> " + edge.to()).toList()));
	}

	private static List<org.assertj.core.groups.Tuple> detached(WorkflowFlow flow) {
		return flow.nodes()
			.stream()
			.filter(node -> "DETACHED".equals(node.kind()))
			.map(node -> tuple(node.step(), node.label(), node.detail()))
			.toList();
	}

}
