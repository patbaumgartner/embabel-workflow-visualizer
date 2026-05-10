package com.patbaumgartner.embabel.moderation;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.annotation.Condition;
import com.embabel.agent.api.common.OperationContext;
import com.patbaumgartner.embabel.moderation.ContentModels.ContentAnalysis;
import com.patbaumgartner.embabel.moderation.ContentModels.ContentRequest;
import com.patbaumgartner.embabel.moderation.ContentModels.ModerationDecision;
import com.patbaumgartner.embabel.moderation.ContentModels.TaggedContent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Content Moderation Agent — converging branches pattern with a single @AchievesGoal.
 *
 * Demo pattern: converging branches → single @AchievesGoal - Two @Condition annotations
 * gate which branch runs - Both branches produce the SAME intermediate type
 * (TaggedContent) - The @AchievesGoal action only needs TaggedContent — it doesn't care
 * which branch produced it. The branches converge before the terminal step.
 *
 * Plan: ContentRequest → [analyzeContent] → ContentAnalysis → [isClear] → [autoTag] →
 * TaggedContent ──┐ → [isFlagged] → [deepReview] → TaggedContent ──┘ [recordDecision]
 * (@AchievesGoal) → ModerationDecision
 */
@Agent(name = "ContentModerationAgent",
		description = "Evaluates user-generated content against platform policies and issues a moderation decision.",
		version = "1.0.0")
public class ContentModerationAgent {

	static final String IS_CLEAR = "isClear";
	static final String IS_FLAGGED = "isFlagged";

	private static final Logger log = LoggerFactory.getLogger(ContentModerationAgent.class);

	/**
	 * Step 1: Analyse content for toxicity and policy violations. Sets both condition
	 * flags so the planner knows which branch to activate.
	 */
	@Action(description = "Analyse user-generated content for toxicity and policy violations.",
			post = { IS_CLEAR, IS_FLAGGED })
	public ContentAnalysis analyzeContent(ContentRequest request, OperationContext context) {
		log.info("Analysing content: {} platform={} type={}", request.contentId(), request.platform(),
				request.contentType());

		var prompt = """
				You are a content moderation system.
				Analyse the following user-generated content against community standards.

				Platform: %s
				Content type: %s
				Content: "%s"

				Provide:
				1. toxicityScore — 0.0 (completely clean) to 1.0 (clearly violates policy)
				2. violatedPolicies — list of policy names violated (empty if none)
				3. clearViolation — true if the content unambiguously violates policy
				4. potentiallyAmbiguous — true if context or intent is unclear
				5. analysisSummary — one sentence explaining the score
				""".formatted(request.platform(), request.contentType(), request.contentText());

		return context.ai().withDefaultLlm().creating(ContentAnalysis.class).fromPrompt(prompt);
	}

	/**
	 * Fast path: clearly safe (score ≤ 0.2) or unambiguous violation (score ≥ 0.8).
	 */
	@Condition(name = IS_CLEAR)
	public boolean isClear(ContentAnalysis analysis) {
		return !analysis.potentiallyAmbiguous() && (analysis.toxicityScore() <= 0.2 || analysis.toxicityScore() >= 0.8);
	}

	/** Slow path: ambiguous content or borderline score requires deeper review. */
	@Condition(name = IS_FLAGGED)
	public boolean isFlagged(ContentAnalysis analysis) {
		return analysis.potentiallyAmbiguous() || (analysis.toxicityScore() > 0.2 && analysis.toxicityScore() < 0.8);
	}

	/**
	 * Branch A (fast): auto-tag clearly safe or clearly violating content. Produces
	 * TaggedContent — the convergence type.
	 */
	@Action(description = "Automatically tag content that is clearly clean or clearly violating policy.",
			pre = { IS_CLEAR })
	public TaggedContent autoTag(ContentAnalysis analysis, OperationContext context) {
		log.info("Auto-tagging content: {} toxicity={}", analysis.contentId(), analysis.toxicityScore());

		var prompt = """
				You are an automated content tagger.
				The content has a clear toxicity classification. Assign appropriate moderation tags.

				Content ID: %s
				Toxicity score: %.2f
				Clear violation: %s
				Violated policies: %s
				Analysis: %s

				Provide:
				1. appliedTags — short moderation labels (e.g. safe, hate-speech, spam)
				2. moderationNotes — one sentence for the audit log
				3. recommendedAction — APPROVE or REMOVE
				""".formatted(analysis.contentId(), analysis.toxicityScore(), analysis.clearViolation(),
				String.join(", ", analysis.violatedPolicies()), analysis.analysisSummary());

		TaggedContent raw = context.ai().withDefaultLlm().creating(TaggedContent.class).fromPrompt(prompt);
		return new TaggedContent(raw.contentId(), raw.appliedTags(), raw.moderationNotes(), raw.recommendedAction(),
				true);
	}

	/**
	 * Branch B (slow): deep-review ambiguous or borderline content. Also produces
	 * TaggedContent — the same convergence type as Branch A.
	 */
	@Action(description = "Perform a deeper contextual review of ambiguous or borderline content.",
			pre = { IS_FLAGGED })
	public TaggedContent deepReview(ContentAnalysis analysis, OperationContext context) {
		log.info("Deep-reviewing content: {} toxicity={} ambiguous={}", analysis.contentId(), analysis.toxicityScore(),
				analysis.potentiallyAmbiguous());

		var prompt = """
				You are a senior content moderator performing a contextual review.
				This content requires deeper analysis due to ambiguity or a borderline toxicity score.

				Content ID: %s
				Toxicity score: %.2f
				Ambiguous: %s
				Violated policies: %s
				Analysis: %s

				Consider context, intent, and whether reasonable users would find the content harmful.
				Provide:
				1. appliedTags — short moderation labels including context-specific ones (e.g. dark-humour, hyperbole, borderline)
				2. moderationNotes — 2–3 sentence explanation for the audit log
				3. recommendedAction — APPROVE, REMOVE, or FLAG_FOR_REVIEW
				"""
			.formatted(analysis.contentId(), analysis.toxicityScore(), analysis.potentiallyAmbiguous(),
					String.join(", ", analysis.violatedPolicies()), analysis.analysisSummary());

		TaggedContent raw = context.ai().withDefaultLlm().creating(TaggedContent.class).fromPrompt(prompt);
		return new TaggedContent(raw.contentId(), raw.appliedTags(), raw.moderationNotes(), raw.recommendedAction(),
				false);
	}

	/**
	 * Convergence step — the single @AchievesGoal. Operates on TaggedContent regardless
	 * of which branch produced it. No pre= condition needed — the planner knows to run
	 * this once TaggedContent exists.
	 */
	@AchievesGoal(description = "Produce a moderation decision for this piece of content.")
	@Action(description = "Record the final moderation decision based on the tagged content.")
	public ModerationDecision recordDecision(TaggedContent tagged, OperationContext context) {
		log.info("Recording decision for: {} autoTagged={} recommended={}", tagged.contentId(), tagged.autoTagged(),
				tagged.recommendedAction());

		var prompt = """
				You are a moderation decision recorder.
				Produce the final moderation ruling based on the tagged content below.

				Content ID: %s
				Applied tags: %s
				Moderation notes: %s
				Recommended action: %s
				Auto-tagged (fast path): %s

				Provide:
				1. action — APPROVE, REMOVE, or FLAG_FOR_REVIEW
				2. reason — one sentence for the content author if challenged
				3. appealable — true if the author may contest this decision
				4. appliedPolicies — list of policy names that justify the decision
				""".formatted(tagged.contentId(), String.join(", ", tagged.appliedTags()), tagged.moderationNotes(),
				tagged.recommendedAction(), tagged.autoTagged());

		return context.ai().withDefaultLlm().creating(ModerationDecision.class).fromPrompt(prompt);
	}

}
