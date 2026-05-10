package com.patbaumgartner.embabel.sentiment;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.annotation.Cost;
import com.embabel.agent.api.common.OperationContext;
import com.patbaumgartner.embabel.sentiment.SentimentModels.FeedbackRequest;
import com.patbaumgartner.embabel.sentiment.SentimentModels.FeedbackResponse;
import com.patbaumgartner.embabel.sentiment.SentimentModels.SentimentClassification;
import com.patbaumgartner.embabel.sentiment.SentimentModels.SentimentInsight;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sentiment Analysis Agent — linear pipeline with @Cost method.
 *
 * Demo pattern: @Cost annotation - @Cost(name=...) marks a method as a dynamic cost
 * calculator - costMethod= on @Action references that method by name - The planner uses
 * computed costs to weigh action selection - static cost= on the cheap first step shows
 * the simpler variant - single @AchievesGoal — pure linear flow, no branching
 *
 * Plan: FeedbackRequest → [quickClassify] (cost=1.0) → SentimentClassification →
 * [deepAnalyze] (costMethod=...) → SentimentInsight → [respondToCustomer] (@AchievesGoal)
 * → FeedbackResponse
 */
@Agent(name = "SentimentAnalysisAgent",
		description = "Analyses customer feedback sentiment and routes it to an auto-response or an escalation path.",
		version = "1.0.0")
public class SentimentAnalysisAgent {

	private static final Logger log = LoggerFactory.getLogger(SentimentAnalysisAgent.class);

	// ── Cost methods ─────────────────────────────────────────────────────

	/**
	 * Dynamic cost for the deep-analysis step. Negative or urgent feedback is worth
	 * spending more on — the cost is intentionally higher so the planner knows this is an
	 * expensive call. Neutral/positive feedback uses a lower cost.
	 */
	@Cost(name = "deepAnalysisCost")
	public double computeDeepAnalysisCost(SentimentClassification classification) {
		if (classification.urgentIssueDetected() || classification.score() < -0.5) {
			return 5.0;
		}
		return 2.0;
	}

	/**
	 * Dynamic cost for producing the final customer response. Escalations require a
	 * carefully crafted message — higher cost.
	 */
	@Cost(name = "responseCost")
	public double computeResponseCost(SentimentInsight insight) {
		return insight.suggestedAction() != null && insight.suggestedAction().toLowerCase().contains("escalat") ? 4.0
				: 1.5;
	}

	// ── Pipeline steps ────────────────────────────────────────────────────

	/**
	 * Step 1: Fast, cheap sentiment classification. Uses static cost=1.0 — the simplest
	 * way to declare action cost.
	 */
	@Action(description = "Quickly classify the sentiment of the customer feedback (POSITIVE/NEGATIVE/NEUTRAL/MIXED).",
			cost = 1.0)
	public SentimentClassification quickClassify(FeedbackRequest request, OperationContext context) {
		log.info("Classifying feedback: {} channel={}", request.feedbackId(), request.channel());

		var prompt = """
				You are a customer feedback classifier.
				Analyse the feedback below and classify its sentiment quickly.

				Customer: %s
				Product: %s
				Channel: %s
				Feedback: "%s"

				Provide:
				1. sentiment: POSITIVE, NEGATIVE, NEUTRAL, or MIXED
				2. score: -1.0 (very negative) to +1.0 (very positive)
				3. urgentIssueDetected: true if the customer signals immediate operational impact, data loss, or legal action
				4. classificationRationale: one sentence explaining the score
				"""
			.formatted(request.customerName(), request.productName(), request.channel(), request.feedbackText());

		return context.ai().withDefaultLlm().creating(SentimentClassification.class).fromPrompt(prompt);
	}

	/**
	 * Step 2: Deep analysis — references the @Cost method via costMethod=. The planner
	 * evaluates computeDeepAnalysisCost() at planning time to decide whether the action
	 * is worth its expense.
	 */
	@Action(description = "Perform a deep analysis of the feedback to identify root causes, emotional tone, and actionable insights.",
			costMethod = "deepAnalysisCost")
	public SentimentInsight deepAnalyze(SentimentClassification classification, FeedbackRequest request,
			OperationContext context) {
		log.info("Deep-analysing feedback: {} sentiment={} score={} urgent={}", classification.feedbackId(),
				classification.sentiment(), classification.score(), classification.urgentIssueDetected());

		var prompt = """
				You are a senior customer experience analyst.
				Perform a deep analysis of the feedback below to support crafting the best possible response.

				Customer: %s
				Product: %s
				Sentiment: %s (score %.2f)
				Urgent issue detected: %s
				Initial rationale: %s
				Feedback: "%s"

				Provide:
				1. rootCauseTopics — up to 5 short topic labels (e.g. performance, billing, onboarding)
				2. emotionalTone — e.g. frustrated, disappointed, delighted, anxious
				3. suggestedAction — e.g. "auto-reply", "escalate to support", "escalate to engineering", "follow-up call"
				4. internalNotes — a brief note for the support team
				"""
			.formatted(request.customerName(), request.productName(), classification.sentiment(),
					classification.score(), classification.urgentIssueDetected(),
					classification.classificationRationale(), request.feedbackText());

		return context.ai().withDefaultLlm().creating(SentimentInsight.class).fromPrompt(prompt);
	}

	/**
	 * Step 3: Produce the final customer-facing response. The single @AchievesGoal in
	 * this agent — no branching, always reached. References a @Cost method to reflect the
	 * varying effort of different response types.
	 */
	@AchievesGoal(description = "Produce a customer response and recommended action for this feedback.")
	@Action(description = "Generate a customer-facing response and recommended support action based on the full analysis.",
			costMethod = "responseCost")
	public FeedbackResponse respondToCustomer(SentimentInsight insight, SentimentClassification classification,
			FeedbackRequest request, OperationContext context) {
		log.info("Generating response for: {} suggestedAction={}", insight.feedbackId(), insight.suggestedAction());

		boolean escalate = insight.suggestedAction() != null
				&& insight.suggestedAction().toLowerCase().contains("escalat");

		var prompt = """
				You are a customer experience specialist writing a response to customer feedback.

				Customer: %s
				Product: %s
				Sentiment: %s (score %.2f)
				Urgent: %s
				Root causes: %s
				Emotional tone: %s
				Suggested action: %s
				Internal notes: %s
				Original feedback: "%s"

				Provide:
				1. customerMessage — a warm, professional reply to the customer (2–4 sentences)
				2. recommendedAction — a short label for the CRM system (e.g. CLOSE, FOLLOW_UP, ESCALATE_TIER2)
				3. escalated — true/false (should this be escalated to a human?)
				4. teamNotes — a one-sentence note visible only to the support team
				""".formatted(request.customerName(), request.productName(), classification.sentiment(),
				classification.score(), classification.urgentIssueDetected(),
				String.join(", ", insight.rootCauseTopics()), insight.emotionalTone(), insight.suggestedAction(),
				insight.internalNotes(), request.feedbackText());

		return context.ai().withDefaultLlm().creating(FeedbackResponse.class).fromPrompt(prompt);
	}

}
