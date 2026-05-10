package com.patbaumgartner.embabel.research;

import jakarta.validation.constraints.NotBlank;

/**
 * Domain model for the Product Research agent.
 *
 * <p>
 * Flow: ResearchRequest → [gatherMarketData] → MarketData → (spel precondition:
 * confidence > 0.6) → [analyzeCompetitors] → CompetitorAnalysis → [generateReport]
 * (@AchievesGoal) → ResearchReport
 *
 * <p>
 * Demo patterns:
 * <ul>
 * <li>{@code PlannerType.SUPERVISOR} — the LLM itself decides which action to run
 * next</li>
 * <li>SpEL preconditions — {@code pre = {"spel:marketData.confidenceScore > 0.6"}} gates
 * deeper analysis on data quality</li>
 * <li>{@code @Action(outputBinding = "marketData")} — names the produced object on the
 * blackboard explicitly rather than relying on type-based binding</li>
 * <li>{@code @EmbabelComponent} shared utilities — {@link ResearchUtils} is a shared
 * component that exposes reusable @Action steps without being an agent itself</li>
 * </ul>
 */
public class ResearchModels {

	// ── Input ───────────────────────────────────────────────────────────

	/**
	 * A request to research a product category.
	 *
	 * @param requestId unique correlation id
	 * @param productCategory the category to research (e.g. "electric vehicles",
	 * "smartwatches")
	 * @param targetMarket regional market focus (e.g. "US", "EU", "APAC")
	 * @param researchDepth "quick" | "standard" | "deep"
	 */
	public record ResearchRequest(@NotBlank String requestId, @NotBlank String productCategory, String targetMarket,
			String researchDepth) {
	}

	// ── Intermediate ────────────────────────────────────────────────────

	/**
	 * High-level market data snapshot produced by the first action.
	 *
	 * <p>
	 * The {@code confidenceScore} field is referenced in the SpEL precondition on
	 * {@code analyzeCompetitors}: {@code spel:marketData.confidenceScore > 0.6}. Only
	 * when this threshold is met will the planner run the deeper competitive analysis.
	 *
	 * @param requestId correlation id
	 * @param marketSizeUsd estimated market size in USD billions
	 * @param annualGrowthRate estimated CAGR percentage
	 * @param keyTrends list of key market trends
	 * @param confidenceScore 0.0–1.0 — how reliable the data is (high = run deeper
	 * analysis)
	 * @param dataSummary short narrative description
	 */
	public record MarketData(String requestId, double marketSizeUsd, double annualGrowthRate, String[] keyTrends,
			double confidenceScore, String dataSummary) {
	}

	/**
	 * Competitive landscape analysis — only run when market data confidence is high
	 * enough.
	 *
	 * @param requestId correlation id
	 * @param topCompetitors comma-separated list of top players
	 * @param marketLeader dominant market leader (may be blank if fragmented)
	 * @param competitiveIntensity "low" | "medium" | "high"
	 * @param whitespaceOpportunities areas not yet captured by existing competitors
	 */
	public record CompetitorAnalysis(String requestId, String topCompetitors, String marketLeader,
			String competitiveIntensity, String whitespaceOpportunities) {
	}

	// ── Output ──────────────────────────────────────────────────────────

	/**
	 * Final consolidated research report.
	 *
	 * @param requestId correlation id
	 * @param productCategory category researched
	 * @param executiveSummary 2–3 sentence summary for executives
	 * @param marketOverview merged market narrative
	 * @param competitiveLandscape merged competitor narrative (may be N/A if confidence
	 * was too low)
	 * @param recommendations actionable next steps
	 * @param confidenceScore final overall confidence (inherited from MarketData)
	 */
	public record ResearchReport(String requestId, String productCategory, String executiveSummary,
			String marketOverview, String competitiveLandscape, String recommendations, double confidenceScore) {
	}

	// ── REST API types ───────────────────────────────────────────────────

	public record ApiResearchRequest(@NotBlank String requestId, @NotBlank String productCategory, String targetMarket,
			String researchDepth) {

		public ResearchRequest toDomain() {
			return new ResearchRequest(requestId, productCategory, targetMarket != null ? targetMarket : "global",
					researchDepth != null ? researchDepth : "standard");
		}
	}

}
