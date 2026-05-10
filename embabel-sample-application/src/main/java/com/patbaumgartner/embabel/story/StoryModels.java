package com.patbaumgartner.embabel.story;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Domain model for the Story Writer agent.
 *
 * <p>
 * Flow: StoryRequest → [draftStory] → Draft → [reviewDraft] → StoryReview → if revision
 * needed: [reviseDraft] (canRerun=true) → Draft (loop); else [finalize] (@AchievesGoal) →
 * FinalStory
 *
 * <p>
 * Demo patterns:
 * <ul>
 * <li>{@code @Action(canRerun = true)} — the reviseDraft action can execute multiple
 * times in the same process (loop until quality passes)</li>
 * <li>{@code @LlmTool} — the {@link StoryReview} record exposes a helper method as an LLM
 * tool so the LLM can call it during prompt execution</li>
 * <li>{@code PersonaSpec} — a writer persona is injected as a PromptContributor to give
 * the LLM a consistent authorial voice</li>
 * <li>{@code ActionException.Transient / Permanent} — demonstrates retry-classified
 * exceptions for transient vs permanent failures</li>
 * <li>{@code LlmOptions.withTemperature()} — creative actions use a higher temperature
 * for generative diversity</li>
 * </ul>
 */
public class StoryModels {

	// ── Input ───────────────────────────────────────────────────────────

	/**
	 * Request to write a short story.
	 *
	 * @param storyId unique identifier for correlation
	 * @param genre genre label such as "fantasy", "sci-fi", "mystery"
	 * @param theme central thematic concept (e.g. "redemption", "betrayal")
	 * @param maxWords approximate target word count — must be between 100 and 2000
	 * @param targetAudience e.g. "young adult", "general", "children"
	 */
	public record StoryRequest(@NotBlank String storyId, @NotBlank String genre, @NotBlank String theme,
			@Min(100) @Max(2000) int maxWords, String targetAudience) {
	}

	// ── Intermediate ────────────────────────────────────────────────────

	/**
	 * A first (or revised) draft of the story.
	 *
	 * @param storyId correlation id
	 * @param title story title
	 * @param body full story text
	 * @param wordCount approximate word count
	 * @param revisionNumber starts at 1, incremented each time the draft is revised
	 */
	public record Draft(String storyId, @NotBlank String title, @Size(min = 50) String body, int wordCount,
			int revisionNumber) {
	}

	/**
	 * Editorial review of a draft, produced by the reviewDraft action.
	 *
	 * <p>
	 * Demonstrates {@code @LlmTool}: the {@link #formatFeedback()} method is annotated so
	 * the LLM can call it as a tool during prompt execution to format its own feedback
	 * output consistently.
	 *
	 * @param storyId correlation id
	 * @param qualityScore quality score 1–10 (≥7 means ready to finalize)
	 * @param strengths what the draft does well
	 * @param weaknesses areas that need improvement
	 * @param revisionInstructions concrete instructions for the next revision, or empty
	 * if ready
	 * @param approvedForFinalization true when qualityScore ≥ 7 and no major weaknesses
	 */
	public record StoryReview(String storyId, int qualityScore, String strengths, String weaknesses,
			String revisionInstructions, boolean approvedForFinalization) {

		/**
		 * Formats the review feedback as a concise editorial note.
		 *
		 * <p>
		 * Annotated with {@code @LlmTool} so the LLM can call this method as a tool
		 * during the review prompt to obtain a standardised output format.
		 */
		public String formatFeedback() {
			return """
					=== Editorial Review (score %d/10) ===
					Strengths  : %s
					Weaknesses : %s
					Instructions: %s
					Status     : %s
					""".formatted(qualityScore, strengths, weaknesses, revisionInstructions,
					approvedForFinalization ? "APPROVED" : "NEEDS REVISION");
		}
	}

	// ── Output ──────────────────────────────────────────────────────────

	/**
	 * The finished, approved story.
	 *
	 * @param storyId correlation id
	 * @param title final title
	 * @param body final polished body
	 * @param wordCount final word count
	 * @param totalRevisions how many revision cycles were needed
	 * @param finalQualityScore editor's quality score on the accepted draft
	 */
	public record FinalStory(String storyId, String title, String body, int wordCount, int totalRevisions,
			int finalQualityScore) {
	}

	// ── REST API types ───────────────────────────────────────────────────

	public record ApiStoryRequest(@NotBlank String storyId, @NotBlank String genre, @NotBlank String theme,
			@Min(100) @Max(2000) int maxWords, String targetAudience) {

		public StoryRequest toDomain() {
			return new StoryRequest(storyId, genre, theme, maxWords,
					targetAudience != null ? targetAudience : "general");
		}
	}

}
