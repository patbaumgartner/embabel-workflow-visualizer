package com.patbaumgartner.embabel.document;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Domain model for the Document Processing Agent.
 *
 * <p>
 * Flow: DocumentRequest → [preprocessDocument] (clearBlackboard=true,
 * outputBinding="cleanDoc") → CleanDocument → [extractMetadata] (@Nullable hints,
 * value=0.3) → DocumentMetadata → [analyzeContent] (SpEL pre, Ai injection,
 * canRerun=true) → ContentAnalysis → [summarizeDocument] (@AchievesGoal, export, tags,
 * examples) → DocumentSummary
 *
 * <p>
 * Key demo features visible in this model:
 * <ul>
 * <li>outputBinding — CleanDocument is bound under "cleanDoc" for downstream
 * reference</li>
 * <li>@Nullable — MetadataHints is optional; if absent null is passed</li>
 * <li>clearBlackboard — raw input is discarded after preprocessing</li>
 * </ul>
 */
public class DocumentModels {

	// ── Input ─────────────────────────────────────────────────────────────

	/**
	 * Incoming document processing request — provided by the caller.
	 */
	public record DocumentRequest(String documentId, String title, String rawContent, String language,
			String targetAudience) {
	}

	/**
	 * Optional caller-supplied hints that guide metadata extraction. When absent, the
	 * agent skips guided extraction and uses defaults.
	 */
	public record MetadataHints(String expectedTopics, String domainContext, boolean requireKeywords) {
	}

	// ── Intermediate ──────────────────────────────────────────────────────

	/**
	 * Cleaned and normalized document after preprocessing. Bound on the blackboard under
	 * "cleanDoc" via {@code outputBinding = "cleanDoc"}.
	 */
	public record CleanDocument(String documentId, String title, String normalizedContent, String language,
			int wordCount) {
	}

	/**
	 * Metadata extracted from the document (author signals, topics, keywords).
	 */
	public record DocumentMetadata(String title, String detectedLanguage, String primaryTopic, String[] keywords,
			String estimatedReadingTime) {
	}

	/**
	 * Deep content analysis — themes, tone, structure, and quality signals.
	 */
	public record ContentAnalysis(String documentId, String tone, String[] mainThemes, String structureAssessment,
			double qualityScore, String targetAudienceAlignment) {
	}

	// ── Output ────────────────────────────────────────────────────────────

	/**
	 * Final document summary — the goal of the agent.
	 */
	public record DocumentSummary(String documentId, String title, String summary, String[] keyPoints,
			String recommendedAction, double confidenceScore) {
	}

	// ── REST API types ────────────────────────────────────────────────────

	public record ApiDocumentRequest(@NotBlank String documentId, @NotBlank String title,
			@NotBlank @Size(min = 10, max = 50_000) String rawContent, String language, String targetAudience,
			String expectedTopics, String domainContext, boolean requireKeywords) {
		public DocumentRequest toDomain() {
			return new DocumentRequest(documentId, title, rawContent, language, targetAudience);
		}

		public MetadataHints toHints() {
			if (expectedTopics == null && domainContext == null && !requireKeywords) {
				return null;
			}
			return new MetadataHints(expectedTopics, domainContext, requireKeywords);
		}
	}

}
