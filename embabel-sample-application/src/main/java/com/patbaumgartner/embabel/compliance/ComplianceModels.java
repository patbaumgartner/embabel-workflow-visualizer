package com.patbaumgartner.embabel.compliance;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * Domain model for the Compliance Review agent.
 *
 * Flow: ReviewRequest → [screenClauses] → ClauseScreening → [assessRisk] → RiskAssessment
 * → [issueVerdict] → ComplianceVerdict
 *
 * Demo pattern: HYBRID planner combining goal-directed and utility-ranked planning, with
 * a cost-weighted {@code @Condition}, a fire-once action, and an MCP-exported goal that
 * is deliberately hidden from local callers.
 */
public class ComplianceModels {

	// ── Input ───────────────────────────────────────────────────────────

	public record ReviewRequest(String reviewId, String documentText, String jurisdiction) {
	}

	// ── Intermediate ────────────────────────────────────────────────────

	/**
	 * Clauses extracted from the document without calling an LLM.
	 */
	public record ClauseScreening(String reviewId, List<String> flaggedClauses, int clauseCount) {
	}

	/**
	 * Weighted regulatory risk. riskScore: 0.0 (compliant) to 1.0 (severe breach).
	 */
	public record RiskAssessment(String reviewId, double riskScore, String primaryConcern) {
	}

	// ── Output ──────────────────────────────────────────────────────────

	/**
	 * Final compliance verdict. decision: CLEAR, REMEDIATE, or ESCALATE.
	 */
	public record ComplianceVerdict(String reviewId, String decision, double riskScore, String rationale) {
	}

	// ── REST API types ───────────────────────────────────────────────────

	public record ApiReviewRequest(@NotBlank String reviewId, @NotBlank String documentText,
			@NotBlank String jurisdiction) {
		public ReviewRequest toDomain() {
			return new ReviewRequest(reviewId, documentText, jurisdiction);
		}
	}

}
