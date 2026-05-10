package com.patbaumgartner.embabel.research;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.common.OperationContext;
import com.embabel.agent.api.common.PlannerType;
import com.patbaumgartner.embabel.research.ResearchModels.CompetitorAnalysis;
import com.patbaumgartner.embabel.research.ResearchModels.MarketData;
import com.patbaumgartner.embabel.research.ResearchModels.ResearchReport;
import com.patbaumgartner.embabel.research.ResearchModels.ResearchRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Product Research Agent — LLM-driven (SUPERVISOR) planner with SpEL preconditions.
 *
 * <p>
 * <b>New features demonstrated:</b>
 * <ul>
 * <li>{@code planner = PlannerType.SUPERVISOR} — instead of GOAP or Utility AI, the
 * SUPERVISOR planner delegates planning decisions to the LLM itself. The LLM is shown the
 * available actions and the current blackboard state and chooses which action to execute
 * next.</li>
 * <li>SpEL precondition — {@code pre = {"spel:marketData.confidenceScore > 0.6"}} on
 * {@link #analyzeCompetitors} prevents the deeper (more expensive) analysis step from
 * running unless the market data is sufficiently confident. The SpEL expression is
 * evaluated against the blackboard at planning time.</li>
 * <li>{@code outputBinding = "marketData"} — declared in the shared {@link ResearchUtils}
 * {@code @EmbabelComponent}; the named binding ensures the market data object is
 * retrievable under that key regardless of type ambiguity.</li>
 * <li>{@code @EmbabelComponent} — {@link ResearchUtils} contributes the
 * {@code gatherMarketData} action without being an agent itself, demonstrating
 * cross-component action sharing.</li>
 * </ul>
 */
@Agent(name = "ProductResearchAgent",
		description = "Researches a product category using market data and competitive analysis, guided by an LLM supervisor.",
		version = "1.0.0", planner = PlannerType.SUPERVISOR)
public class ProductResearchAgent {

	private static final Logger log = LoggerFactory.getLogger(ProductResearchAgent.class);

	// ── Step 2: Competitive analysis (SpEL-gated) ─────────────────────────

	/**
	 * Performs a competitive landscape analysis on the product category.
	 *
	 * <p>
	 * <b>SpEL precondition</b>: {@code "spel:marketData.confidenceScore > 0.6"} — the
	 * GOAP/SUPERVISOR planner evaluates this Spring Expression Language expression
	 * against the current blackboard state before scheduling this action. If the market
	 * data's confidence score is ≤ 0.6 (low-quality data), the action is skipped and the
	 * report will note that competitive analysis was not performed.
	 *
	 * <p>
	 * The SpEL expression references the named binding {@code "marketData"} (set via
	 * {@code outputBinding} in {@link ResearchUtils#gatherMarketData}) and accesses its
	 * {@code confidenceScore} field.
	 */
	@Action(description = "Analyze the competitive landscape for the product category — only runs when market data confidence is sufficient.",
			pre = { "spel:marketData.confidenceScore > 0.6" })
	public CompetitorAnalysis analyzeCompetitors(ResearchRequest request, MarketData marketData,
			OperationContext context) {
		log.info("Analyzing competitors for category='{}' (marketData.confidence={})", request.productCategory(),
				String.format("%.2f", marketData.confidenceScore()));

		var prompt = """
				You are a competitive intelligence analyst. Analyze the competitive landscape for:
				Product Category: %s
				Target Market: %s

				Market Context:
				- Market Size: $%.1fB
				- Growth Rate: %.1f%% CAGR
				- Key Trends: %s

				Provide a competitive analysis as JSON:
				{
				  "requestId": "%s",
				  "topCompetitors": "comma-separated list of top 5 market players",
				  "marketLeader": "dominant leader or 'fragmented' if no clear leader",
				  "competitiveIntensity": "low|medium|high",
				  "whitespaceOpportunities": "2-3 sentences on under-served segments"
				}
				""".formatted(request.productCategory(), request.targetMarket(), marketData.marketSizeUsd(),
				marketData.annualGrowthRate(), String.join(", ", marketData.keyTrends()), request.requestId());

		return context.ai().withDefaultLlm().creating(CompetitorAnalysis.class).fromPrompt(prompt);
	}

	// ── Step 3: Generate report (@AchievesGoal) ───────────────────────────

	/**
	 * Assembles the final research report from market data and optional competitive
	 * analysis.
	 *
	 * <p>
	 * The {@link CompetitorAnalysis} parameter is
	 * {@code @org.jetbrains.annotations.Nullable} — when the SpEL precondition on
	 * {@link #analyzeCompetitors} was not met (low confidence data), no competitor
	 * analysis will be on the blackboard. The planner can still achieve the goal by
	 * calling this action with {@code null} for that parameter, and the report will
	 * clearly note the omission.
	 */
	@AchievesGoal(
			description = "Produce a complete product research report covering market size, growth, and competitive landscape.",
			tags = { "research", "market", "strategy" },
			examples = { "Research the electric vehicle market in the US", "Analyze the smartwatch market in APAC" })
	@Action(description = "Generate the final consolidated research report from available market and competitive data.")
	public ResearchReport generateReport(ResearchRequest request, MarketData marketData,
			@org.jetbrains.annotations.Nullable CompetitorAnalysis competitorAnalysis, OperationContext context) {
		log.info("Generating research report for requestId='{}'", request.requestId());

		var competitiveSection = competitorAnalysis != null
				? "Top players: %s. Leader: %s. Intensity: %s. Opportunities: %s".formatted(
						competitorAnalysis.topCompetitors(), competitorAnalysis.marketLeader(),
						competitorAnalysis.competitiveIntensity(), competitorAnalysis.whitespaceOpportunities())
				: "Competitive analysis was not performed (market data confidence too low for reliable competitor mapping).";

		var prompt = """
				You are a senior market research analyst. Write a concise executive research report.

				Product Category: %s
				Target Market: %s
				Research Depth: %s

				Market Data:
				- Market Size: $%.1fB USD
				- Annual Growth Rate: %.1f%% CAGR
				- Key Trends: %s
				- Summary: %s

				Competitive Landscape:
				%s

				Generate a JSON report with:
				{
				  "requestId": "%s",
				  "productCategory": "%s",
				  "executiveSummary": "2-3 sentence executive summary",
				  "marketOverview": "paragraph describing the market",
				  "competitiveLandscape": "paragraph describing the competitive situation",
				  "recommendations": "3-5 bullet-point action recommendations",
				  "confidenceScore": %.2f
				}
				""".formatted(request.productCategory(), request.targetMarket(), request.researchDepth(),
				marketData.marketSizeUsd(), marketData.annualGrowthRate(), String.join(", ", marketData.keyTrends()),
				marketData.dataSummary(), competitiveSection, request.requestId(), request.productCategory(),
				marketData.confidenceScore());

		return context.ai().withDefaultLlm().creating(ResearchReport.class).fromPrompt(prompt);
	}

}
