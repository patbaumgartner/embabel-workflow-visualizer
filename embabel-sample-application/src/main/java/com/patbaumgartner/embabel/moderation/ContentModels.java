package com.patbaumgartner.embabel.moderation;

import jakarta.validation.constraints.NotBlank;

/**
 * Domain model for the Content Moderation agent.
 *
 * Flow: ContentRequest → [analyzeContent] → ContentAnalysis [isClear branch] → [autoTag]
 * → TaggedContent ──┐ [isFlagged branch] → [deepReview] → TaggedContent ──┘
 * [recordDecision] (@AchievesGoal) → ModerationDecision
 *
 * Demo pattern: converging branches → single @AchievesGoal - Two @Condition gates split
 * the flow - Both branches produce the SAME output type (TaggedContent) - The
 * single @AchievesGoal action operates on TaggedContent regardless of which branch ran —
 * branches converge before the terminal step
 */
public class ContentModels {

	// ── Input ───────────────────────────────────────────────────────────

	public record ContentRequest(String contentId, String authorId, String platform, String contentType,
			String contentText) {
	}

	// ── Intermediate: analysis ────────────────────────────────────────────

	/**
	 * Result of the initial content analysis. toxicityScore: 0.0 (clean) to 1.0 (highly
	 * toxic).
	 */
	public record ContentAnalysis(String contentId, double toxicityScore, String[] violatedPolicies,
			boolean clearViolation, boolean potentiallyAmbiguous, String analysisSummary) {
	}

	// ── Intermediate: converging branches ────────────────────────────────

	/**
	 * Enriched content produced by BOTH branches — the type the @AchievesGoal action
	 * waits for. autoTagged=true means it came via the fast path; false means it went
	 * through deep review.
	 */
	public record TaggedContent(String contentId, String[] appliedTags, String moderationNotes,
			String recommendedAction, boolean autoTagged) {
	}

	// ── Output ──────────────────────────────────────────────────────────

	/**
	 * Final moderation decision. action: APPROVE, REMOVE, or FLAG_FOR_REVIEW.
	 */
	public record ModerationDecision(String contentId, String action, String reason, boolean appealable,
			String[] appliedPolicies) {
	}

	// ── REST API types ───────────────────────────────────────────────────

	public record ApiContentRequest(@NotBlank String contentId, @NotBlank String authorId, @NotBlank String platform,
			String contentType, @NotBlank String contentText) {
		public ContentRequest toDomain() {
			return new ContentRequest(contentId, authorId, platform, contentType != null ? contentType : "post",
					contentText);
		}
	}

}
