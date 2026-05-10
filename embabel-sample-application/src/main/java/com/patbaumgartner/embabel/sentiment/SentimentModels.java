package com.patbaumgartner.embabel.sentiment;

import jakarta.validation.constraints.NotBlank;

/**
 * Domain model for the Sentiment Analysis agent.
 *
 * Flow: FeedbackRequest → [quickClassify] → SentimentClassification
 * → [deepAnalyze] → SentimentInsight
 * → [respondToCustomer] (@AchievesGoal) → FeedbackResponse
 *
 * Demo pattern: @Cost method — the deepAnalyze action declares a costMethod so
 * the
 * planner can weigh the expense of the LLM call against its value.
 * The cost varies dynamically based on the sentiment score.
 */
public class SentimentModels {

    // ── Input ───────────────────────────────────────────────────────────

    public record FeedbackRequest(String feedbackId, String customerName, String productName, String feedbackText,
            String channel) {
    }

    // ── Intermediate ────────────────────────────────────────────────────

    /**
     * Result of the fast, cheap first-pass classification.
     * sentiment: POSITIVE, NEGATIVE, NEUTRAL, MIXED.
     * score: -1.0 (very negative) to +1.0 (very positive).
     * urgentIssueDetected: true when customer signals immediate impact.
     */
    public record SentimentClassification(String feedbackId, String sentiment, double score,
            boolean urgentIssueDetected, String classificationRationale) {
    }

    /**
     * Result of the deeper, more expensive analysis step.
     * Includes root-cause topics, emotional tone, and actionable insights.
     */
    public record SentimentInsight(String feedbackId, String[] rootCauseTopics, String emotionalTone,
            String suggestedAction, String internalNotes) {
    }

    // ── Output ──────────────────────────────────────────────────────────

    /**
     * Final customer-facing response plus recommended action for the support team.
     */
    public record FeedbackResponse(String feedbackId, String customerMessage, String recommendedAction,
            boolean escalated, String teamNotes) {
    }

    // ── REST API types ───────────────────────────────────────────────────

    public record ApiFeedbackRequest(@NotBlank String feedbackId, @NotBlank String customerName,
            @NotBlank String productName, @NotBlank String feedbackText, String channel) {
        public FeedbackRequest toDomain() {
            return new FeedbackRequest(feedbackId, customerName, productName, feedbackText,
                    channel != null ? channel : "web");
        }
    }

}
