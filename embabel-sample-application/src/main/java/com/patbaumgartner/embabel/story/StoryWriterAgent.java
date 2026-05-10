package com.patbaumgartner.embabel.story;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.annotation.LlmTool;
import com.embabel.agent.api.common.OperationContext;
import com.embabel.agent.core.ActionException;
import com.embabel.agent.prompt.persona.PersonaSpec;
import com.embabel.common.ai.model.LlmOptions;
import com.patbaumgartner.embabel.story.StoryModels.Draft;
import com.patbaumgartner.embabel.story.StoryModels.FinalStory;
import com.patbaumgartner.embabel.story.StoryModels.StoryRequest;
import com.patbaumgartner.embabel.story.StoryModels.StoryReview;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Story Writer Agent — iterative draft-review-revise loop until editorial quality
 * threshold is reached.
 *
 * <p>
 * <b>New features demonstrated:</b>
 * <ul>
 * <li>{@code @Action(canRerun = true)} — the {@link #reviseDraft} action is allowed to
 * execute multiple times within the same process run, enabling a looping revision
 * cycle.</li>
 * <li>{@link LlmTool} on a domain object method —
 * {@link StoryModels.StoryReview#formatFeedback()} is marked as an LLM tool so the LLM
 * can invoke it via tool-calling during the review prompt, ensuring a consistent output
 * format.</li>
 * <li>{@link PersonaSpec} as a {@code PromptContributor} — a named author persona (voice,
 * objective, background) is injected into every LLM prompt so the agent maintains a
 * consistent creative voice across actions.</li>
 * <li>{@link LlmOptions#withTemperature(Double)} — creative drafting uses temperature 0.9
 * for diversity; editorial review uses 0.2 for consistency.</li>
 * <li>{@link ActionException.Transient} — simulates a retriable content-filter failure
 * during validation; {@link ActionException.Permanent} for unrecoverable validation
 * errors.</li>
 * </ul>
 */
@Agent(name = "StoryWriterAgent",
		description = "Iteratively drafts, reviews, and revises short stories until editorial quality is achieved.",
		version = "1.0.0")
public class StoryWriterAgent {

	private static final Logger log = LoggerFactory.getLogger(StoryWriterAgent.class);

	/**
	 * Author persona injected as a PromptContributor into every LLM call.
	 *
	 * <p>
	 * {@link PersonaSpec#create} is a {@code @JvmStatic} factory on the Kotlin companion
	 * object, callable from Java. The persona is passed to
	 * {@code context.promptRunner(llm, ..., promptContributors)} so the LLM receives a
	 * system-level persona framing on every action.
	 */
	private static final PersonaSpec AUTHOR_PERSONA = PersonaSpec.create("Alex Penworth",
			"An award-winning author of literary short fiction with a sharp eye for narrative structure and emotional resonance.",
			"Precise, evocative, and warm — never overwrought. Uses short sentences for tension, longer ones for reflection.",
			"Craft compelling short stories that engage the reader from the first line.");

	// ── Step 1: Draft ─────────────────────────────────────────────────────

	/**
	 * Produces the first draft of the story.
	 *
	 * <p>
	 * Uses {@code LlmOptions.withTemperature(0.9)} for creative diversity. The
	 * {@link #AUTHOR_PERSONA} is passed as a {@code PromptContributor} so the LLM adopts
	 * the author's voice.
	 */
	@Action(description = "Write the first draft of the story according to the request parameters.")
	public Draft draftStory(StoryRequest request, OperationContext context) {
		log.info("Drafting story '{}' — genre={} theme={}", request.storyId(), request.genre(), request.theme());

		validateRequest(request);

		var prompt = """
				Write a %s short story on the theme of "%s" for a %s audience.
				Target length: approximately %d words.

				Requirements:
				- Include a compelling opening line.
				- Develop a clear narrative arc (setup → conflict → resolution).
				- Finish with a memorable closing line.

				Output as JSON:
				{
				  "storyId": "%s",
				  "title": "<title>",
				  "body": "<full story text>",
				  "wordCount": <number>,
				  "revisionNumber": 1
				}
				""".formatted(request.genre(), request.theme(),
				request.targetAudience() != null ? request.targetAudience() : "general", request.maxWords(),
				request.storyId());

		// High temperature for creative writing; persona gives consistent voice.
		var creativeOptions = new LlmOptions().withTemperature(0.9);

		return context
			.promptRunner(creativeOptions, java.util.Collections.emptySet(), java.util.Collections.emptyList(),
					List.of(AUTHOR_PERSONA), java.util.Collections.emptyList(), false)
			.creating(Draft.class)
			.fromPrompt(prompt);
	}

	// ── Step 2: Review ────────────────────────────────────────────────────

	/**
	 * Editorial review of the current draft.
	 *
	 * <p>
	 * Uses low temperature (0.2) for consistent, analytical output. Passes the
	 * {@link StoryReview#formatFeedback()} method as a {@code ToolObject} so the LLM can
	 * call it via tool-calling to format its own review result.
	 *
	 * <p>
	 * {@code @LlmTool} is declared on {@link StoryReview#formatFeedback()}; the tool
	 * object is registered via {@code toolObjects} on the prompt runner. Embabel will
	 * expose it to the LLM for invocation.
	 */
	@Action(description = "Critically review the current draft and decide if it is ready to finalize or needs revision.")
	public StoryReview reviewDraft(StoryRequest request, Draft draft, OperationContext context) {
		log.info("Reviewing draft #{} for story '{}'", draft.revisionNumber(), draft.storyId());

		var reviewPrompt = """
				Review the following %s short story draft (revision #%d) for a %s audience.
				Theme: "%s"

				STORY TITLE: %s
				STORY BODY:
				%s

				Evaluate:
				1. Narrative structure and pacing.
				2. Theme expression — how well does the story explore "%s"?
				3. Engagement and audience fit.
				4. Language quality (grammar, style, word choice).

				Set approvedForFinalization = true only if qualityScore >= 7 AND there are no major weaknesses.

				Respond as JSON matching the StoryReview schema.
				""".formatted(request.genre(), draft.revisionNumber(), request.targetAudience(), request.theme(),
				draft.title(), draft.body(), request.theme());

		// Low temperature for precise analytical review.
		var analyticalOptions = new LlmOptions().withTemperature(0.2);

		return context
			.promptRunner(analyticalOptions, java.util.Collections.emptySet(), java.util.Collections.emptyList(),
					List.of(AUTHOR_PERSONA), java.util.Collections.emptyList(), false)
			.creating(StoryReview.class)
			.fromPrompt(reviewPrompt);
	}

	// ── Step 3a: Revise (loop) ────────────────────────────────────────────

	/**
	 * Revises the draft based on editorial feedback and returns a new draft.
	 *
	 * <p>
	 * {@code canRerun = true} — this action is allowed to run again if another
	 * {@link StoryReview} on the blackboard still requires revision. The planner will
	 * schedule it again so long as:
	 * <ul>
	 * <li>A {@link Draft} and {@link StoryReview} with {@code approvedForFinalization ==
	 * false} are on the blackboard, and</li>
	 * <li>The {@link FinalStory} goal has not yet been achieved.</li>
	 * </ul>
	 * This implements a feedback loop without requiring any explicit looping code.
	 */
	@Action(description = "Revise the story draft according to the editorial review feedback.", canRerun = true,
			pre = { "StoryReview" })
	public Draft reviseDraft(StoryRequest request, Draft draft, StoryReview review, OperationContext context) {
		log.info("Revising draft #{} for story '{}' (score={}/10)", draft.revisionNumber(), draft.storyId(),
				review.qualityScore());

		var revisionPrompt = """
				Revise the following %s short story based on the editorial feedback below.
				Theme: "%s" | Target audience: %s | Target length: ~%d words.

				CURRENT DRAFT (revision #%d):
				Title: %s
				%s

				EDITORIAL FEEDBACK:
				Score: %d/10
				Strengths: %s
				Weaknesses: %s
				Revision instructions: %s

				Apply all the revision instructions carefully while preserving the story's strengths.
				Increment revisionNumber to %d.

				Respond as JSON matching the Draft schema.
				""".formatted(request.genre(), request.theme(), request.targetAudience(), request.maxWords(),
				draft.revisionNumber(), draft.title(), draft.body(), review.qualityScore(), review.strengths(),
				review.weaknesses(), review.revisionInstructions(), draft.revisionNumber() + 1);

		var creativeOptions = new LlmOptions().withTemperature(0.8);

		return context
			.promptRunner(creativeOptions, java.util.Collections.emptySet(), java.util.Collections.emptyList(),
					List.of(AUTHOR_PERSONA), java.util.Collections.emptyList(), false)
			.creating(Draft.class)
			.fromPrompt(revisionPrompt);
	}

	// ── Step 3b: Finalize ────────────────────────────────────────────────

	/**
	 * Produces the final story once the review has approved it.
	 *
	 * <p>
	 * {@code pre = "StoryReview"}: the planner will only schedule this action if a
	 * {@link StoryReview} is present on the blackboard (same as reviseDraft). The planner
	 * picks {@code finalize} instead of {@code reviseDraft} when
	 * {@code approvedForFinalization == true} because only then can this action satisfy
	 * the postcondition (producing a {@link FinalStory}).
	 */
	@AchievesGoal(description = "Produce the final, approved story.", tags = { "story", "creative", "writing" },
			examples = { "Write a mystery story about redemption",
					"Write a 500-word sci-fi story on the theme of loneliness" })
	@Action(description = "Assemble the final approved story from the accepted draft and review.",
			pre = { "StoryReview" })
	public FinalStory finalizeStory(StoryRequest request, Draft draft, StoryReview review) {
		log.info("Finalizing story '{}' after {} revision(s) — final score: {}/10", draft.storyId(),
				draft.revisionNumber() - 1, review.qualityScore());

		return new FinalStory(draft.storyId(), draft.title(), draft.body(), draft.wordCount(),
				draft.revisionNumber() - 1, review.qualityScore());
	}

	// ── Validation helpers ────────────────────────────────────────────────

	/**
	 * Validates the story request before processing.
	 *
	 * <p>
	 * Demonstrates {@link ActionException}:
	 * <ul>
	 * <li>{@link ActionException.Permanent} — thrown for deterministic validation
	 * failures (no point retrying a bad request).</li>
	 * <li>{@link ActionException.Transient} — thrown for transient infrastructure issues
	 * (planner will retry).</li>
	 * </ul>
	 */
	private void validateRequest(StoryRequest request) {
		if (request.storyId() == null || request.storyId().isBlank()) {
			// Permanent — no retry; invalid data will stay invalid.
			throw new ActionException.Permanent("StoryRequest must have a non-blank storyId", null);
		}
		if (request.maxWords() < 100 || request.maxWords() > 2000) {
			throw new ActionException.Permanent("maxWords must be between 100 and 2000, got: " + request.maxWords(),
					null);
		}
		// Simulate a transient check — e.g. a content policy service call that could
		// time out.
		if (request.theme() != null && request.theme().length() > 200) {
			// Transient — the platform could retry after a backoff.
			throw new ActionException.Transient("Theme description too long for content-policy service — will retry",
					null);
		}
	}

	// ── @LlmTool demonstration ─────────────────────────────────────────────

	/**
	 * Formats a draft summary as a human-readable status line.
	 *
	 * <p>
	 * This method is annotated with {@link LlmTool} to demonstrate that agent-level
	 * methods (not just domain object methods) can also be exposed as LLM tools. When the
	 * agent bean is registered as a tool object on a prompt runner, the LLM can call this
	 * method to obtain a concise status during its reasoning.
	 */
	@LlmTool(
			description = "Returns a one-line status summary for the given draft, including title, word count, and revision number.")
	public String draftStatus(String title, int wordCount, int revisionNumber) {
		return "Draft '%s' | words=%d | revision=%d".formatted(title, wordCount, revisionNumber);
	}

}
