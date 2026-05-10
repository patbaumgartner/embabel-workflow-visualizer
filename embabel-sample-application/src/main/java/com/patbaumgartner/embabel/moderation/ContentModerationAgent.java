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
 * Content Moderation Agent — converging branches pattern with a
 * single @AchievesGoal.
 *
 * Demo pattern: converging branches → single @AchievesGoal
 * - Two @Condition annotations gate which branch runs
 * - Both branches produce the SAME intermediate type (TaggedContent)
 * - The @AchievesGoal action only needs TaggedContent — it doesn't care which
 * branch produced it. The branches converge before the terminal step.
 *
 * Plan: ContentRequest
 * → [analyzeContent] → ContentAnalysis
 * → [isClear] → [autoTag] → TaggedContent ──┐
 * → [isFlagged] → [deepReview] → TaggedContent ──┘
 * [recordDecision] (@AchievesGoal)
 * → ModerationDecision
 */
@Agent(name = "ContentModerationAgent", description = "Evaluates user-generated content against platform policies and issues a moderation decision.", version = "1.0.0")
public class ContentModerationAgent {

    static final String IS_CLEAR = "isClear";
    static final String IS_FLAGGED = "isFlagged";

    private static final Logger log = LoggerFactory.getLogger(ContentModerationAgent.class);

    /**
     * Step 1: Analyse content for toxicity and policy violations.
     * Sets both condition flags so the planner knows which branch to activate.
     */
    @Action(description = "Analyse user-generated content for toxicity and policy violations.", post = { IS_CLEAR,
            IS_FLAGGED })
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
     * Branch A (fast): auto-tag clearly safe or clearly violating content.
     * Produces TaggedContent — the convergence type.
     */
    @Action(description = "Automatically tag content that is clearly clean or clearly violating policy.", pre = {
            IS_CLEAR })
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
        return new TaggedContent(raw.contentId(), raw.appliedTags(), raw.moderationNotes(),
                raw.recommendedAction(), true);
    }

    /**
     * Branch B (slow): deep-review ambiguous or borderline content.
     * Also produces TaggedContent — the same convergence type as Branch A.
     */
    @Action(description = "Perform a deeper contextual review of ambiguous or borderline content.", pre = {
            IS_FLAGGED })
    public TaggedContent deepReview(ContentAnalysis analysis, OperationContext context) {
        log.info("Deep-reviewing content: {} toxicity={} ambiguous={}",
                analysis.contentId(), analysis.toxicityScore(), analysis.potentiallyAmbiguous());

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
        return new TaggedContent(raw.contentId(), raw.appliedTags(), raw.moderationNotes(),
                raw.recommendedAction(), false);
    }

    /**
     * Convergence step — the single @AchievesGoal.
     * Operates on TaggedContent regardless of which branch produced it.
     * No pre= condition needed — the planner knows to run this once TaggedContent
     * exists.
     */
    @AchievesGoal(description = "Produce a moderation decision for this piece of content.")
    @Action(description = "Record the final moderation decision based on the tagged content.")
    public ModerationDecision recordDecision(TaggedContent tagged, OperationContext context) {
        log.info("Recording decision for: {} autoTagged={} recommended={}",
                tagged.contentId(), tagged.autoTagged(), tagged.recommendedAction());

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
                """.formatted(tagged.contentId(), String.join(", ", tagged.appliedTags()),
                tagged.moderationNotes(), tagged.recommendedAction(), tagged.autoTagged());

        return context.ai().withDefaultLlm().creating(ModerationDecision.class).fromPrompt(prompt);
    }

}

/**
 * Content Moderation Agent — evaluates user-generated content against platform
 * policies.
 *
 * Plan: ContentRequest → analyzeContent → ContentAnalysis
 * → [CAN_AUTO_MODERATE] → makeAutoDecision → ModerationDecision
 * → [REQUIRES_HUMAN_REVIEW] → prepareReviewSummary → ReviewSummary
 * → makeReviewedDecision → ModerationDecision
 */
@Agent(name = "ContentModerationAgent", description = "Evaluates user-generated content against platform policies and issues a moderation decision.", version = "1.0.0")
public class ContentModerationAgent {

    static final String CAN_AUTO_MODERATE = "canAutoModerate";

    static final String REQUIRES_HUMAN_REVIEW = "requiresHumanReview";

    private static final Logger log = LoggerFactory.getLogger(ContentModerationAgent.class);

    /**
     * Step 1: Analyse toxicity, policy violations and determine how clear-cut the
     * case
     * is.
     */
    @Action(description = "Analyse user-generated content for toxicity, hate speech, and policy violations.", post = {
            CAN_AUTO_MODERATE, REQUIRES_HUMAN_REVIEW })
    public ContentAnalysis analyzeContent(ContentRequest request, OperationContext context) {
        log.info("Analysing content: {} on platform={} type={}", request.contentId(), request.platform(),
                request.contentType());

        var prompt = """
                You are a content moderation AI for a social platform.
                Analyse the content below and score it for policy compliance.

                Content ID: %s
                Platform: %s
                Content Type: %s

                Content:
                "%s"

                Evaluate:
                1. A toxicity score from 0.0 (completely safe) to 1.0 (extremely harmful)
                2. Which platform policies are violated (list), e.g. hate-speech, harassment, spam, misinformation, nudity, violence — empty list if none
                3. Whether this is a clear violation (unambiguous harmful content — true/false)
                4. Whether the content is potentially ambiguous (satire, dark humour, edge cases — true/false)
                5. A brief analysis summary

                Return your findings in the required format.
                """
                .formatted(request.contentId(), request.platform(), request.contentType(), request.contentText());

        return context.ai().withDefaultLlm().creating(ContentAnalysis.class).fromPrompt(prompt);
    }

    /**
     * Clear violations (score ≥ 0.8) or clearly clean content (score ≤ 0.2) with no
     * ambiguity can be handled automatically.
     */
    @Condition(name = CAN_AUTO_MODERATE)
    public boolean canAutoModerate(ContentAnalysis analysis) {
        return !analysis.potentiallyAmbiguous()
                && (analysis.toxicityScore() >= 0.8 || analysis.toxicityScore() <= 0.2);
    }

    /** Ambiguous content or medium toxicity scores need a human review summary. */
    @Condition(name = REQUIRES_HUMAN_REVIEW)
    public boolean requiresHumanReview(ContentAnalysis analysis) {
        return analysis.potentiallyAmbiguous()
                || (analysis.toxicityScore() > 0.2 && analysis.toxicityScore() < 0.8);
    }

    /**
     * Step 2 (human-review branch): Prepare a detailed review summary for the human
     * moderator.
     */
    @Action(description = "Prepare a detailed contextual review summary for a human moderator to assess ambiguous content.", pre = {
            REQUIRES_HUMAN_REVIEW })
    public ReviewSummary prepareReviewSummary(ContentAnalysis analysis, OperationContext context) {
        log.info("Preparing review summary for content: {} toxicity={}", analysis.contentId(),
                analysis.toxicityScore());

        var prompt = """
                You are a senior content policy specialist preparing a review summary for a human moderator.

                Initial Analysis:
                - Content ID: %s
                - Toxicity Score: %.2f
                - Violated Policies: %s
                - Clear Violation: %s
                - Potentially Ambiguous: %s
                - Summary: %s

                Provide:
                1. A contextual analysis — why is this content ambiguous or borderline?
                2. Mitigating factors (e.g. satire, educational context, lack of direct threat — list of labels)
                3. Aggravating factors (e.g. targeting a protected group, calls to action — list of labels)
                4. Clear, concise guidance for the human moderator on what to weigh
                """.formatted(analysis.contentId(), analysis.toxicityScore(),
                String.join(", ", analysis.violatedPolicies()), analysis.clearViolation(),
                analysis.potentiallyAmbiguous(), analysis.analysisSummary());

        return context.ai().withDefaultLlm().creating(ReviewSummary.class).fromPrompt(prompt);
    }

    /**
     * Step 2a (auto branch): Issue an automatic moderation decision for clear
     * cases.
     */
    @AchievesGoal(description = "Produce a moderation decision for this piece of content.")
    @Action(description = "Issue an automatic moderation decision for clearly safe or clearly violating content.", pre = {
            CAN_AUTO_MODERATE })
    public ModerationDecision makeAutoDecision(ContentAnalysis analysis, OperationContext context) {
        log.info("Auto-moderating content: {} toxicity={}", analysis.contentId(), analysis.toxicityScore());

        var prompt = """
                You are a moderation decision engine.
                Issue a final moderation decision for the content below.

                Content ID: %s
                Toxicity Score: %.2f
                Violated Policies: %s
                Clear Violation: %s
                Analysis Summary: %s

                Action options: APPROVE (score ≤ 0.2, no violations), REMOVE (score ≥ 0.8 or clear violation)
                State whether the author may appeal this decision (true/false).
                Provide a concise reason and list the applied policies.
                """.formatted(analysis.contentId(), analysis.toxicityScore(),
                String.join(", ", analysis.violatedPolicies()), analysis.clearViolation(),
                analysis.analysisSummary());

        return context.ai().withDefaultLlm().creating(ModerationDecision.class).fromPrompt(prompt);
    }

    /**
     * Step 3 (human-review branch): Issue a decision enriched with contextual
     * review
     * findings.
     */
    @AchievesGoal(description = "Produce a moderation decision for this piece of content.")
    @Action(description = "Issue a moderation decision for ambiguous content after a detailed contextual review.", pre = {
            REQUIRES_HUMAN_REVIEW })
    public ModerationDecision makeReviewedDecision(ContentAnalysis analysis, ReviewSummary review,
            OperationContext context) {
        log.info("Making reviewed moderation decision for content: {}", analysis.contentId());

        var prompt = """
                You are a moderation decision engine issuing a ruling after detailed review.

                Initial Analysis:
                - Content ID: %s
                - Toxicity Score: %.2f
                - Violated Policies: %s
                - Summary: %s

                Review Findings:
                - Contextual Analysis: %s
                - Mitigating Factors: %s
                - Aggravating Factors: %s
                - Moderator Guidance: %s

                Action options: APPROVE, REMOVE, FLAG_FOR_REVIEW
                State whether the author may appeal (true/false).
                Provide a clear reason and list the applied policies.
                """.formatted(analysis.contentId(), analysis.toxicityScore(),
                String.join(", ", analysis.violatedPolicies()), analysis.analysisSummary(),
                review.contextualAnalysis(), String.join(", ", review.mitigatingFactors()),
                String.join(", ", review.aggravatingFactors()), review.moderatorGuidance());

        return context.ai().withDefaultLlm().creating(ModerationDecision.class).fromPrompt(prompt);
    }

}
