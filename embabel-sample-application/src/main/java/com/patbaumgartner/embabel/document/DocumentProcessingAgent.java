package com.patbaumgartner.embabel.document;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.annotation.Export;
import com.embabel.agent.api.common.Ai;
import com.patbaumgartner.embabel.document.DocumentModels.CleanDocument;
import com.patbaumgartner.embabel.document.DocumentModels.ContentAnalysis;
import com.patbaumgartner.embabel.document.DocumentModels.DocumentMetadata;
import com.patbaumgartner.embabel.document.DocumentModels.DocumentRequest;
import com.patbaumgartner.embabel.document.DocumentModels.DocumentSummary;
import com.patbaumgartner.embabel.document.DocumentModels.MetadataHints;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Document Processing Agent — covers Embabel annotation features not demonstrated
 * elsewhere.
 *
 * <p>
 * <b>New features demonstrated:</b>
 * <ul>
 * <li>{@code @Condition hasSufficientContent} — typed condition gate that guards
 * {@code analyzeContent} from running on trivially short documents (wordCount &gt;
 * 50)</li>
 * <li>{@code @Nullable} parameter — MetadataHints is optional; null is passed when not
 * present on the blackboard</li>
 * <li>{@code value = 0.3} on an intermediate action — signals relative value for
 * utility-style ranking</li>
 * <li>{@code canRerun = true} — allows content analysis to be re-run without restarting
 * the whole pipeline</li>
 * <li>{@link Ai} injected directly — lighter alternative to OperationContext when only
 * LLM capabilities are needed</li>
 * <li>{@code @AchievesGoal} with {@code value}, {@code tags}, {@code examples}, and
 * {@code export} — full goal annotation with MCP export</li>
 * </ul>
 *
 * <p>
 * <b>Plan:</b>
 *
 * <pre>
 * DocumentRequest
 *   → [preprocessDocument]            → CleanDocument
 *   → [provideDefaultMetadataHints]    → MetadataHints (GOAP only runs this when
 *                                         MetadataHints is not already on the
 *                                         blackboard; skipped when caller provides hints)
 *   → [extractMetadata]   (value=0.3) → DocumentMetadata
 *   → [analyzeContent]    (canRerun)  → ContentAnalysis
 *   → [summarizeDocument] (@AchievesGoal + @Export) → DocumentSummary
 * </pre>
 */
@Agent(name = "DocumentProcessingAgent",
		description = "Preprocesses, analyses, and summarises a document. Demonstrates default-producer action for optional inputs, Ai injection, value, canRerun, and full @AchievesGoal options.",
		version = "1.0.0")
public class DocumentProcessingAgent {

	private static final Logger log = LoggerFactory.getLogger(DocumentProcessingAgent.class);

	// ── Step 1 ────────────────────────────────────────────────────────────

	/**
	 * Normalises and cleans the raw document content.
	 */
	@Action(description = "Normalise and clean the raw document content.")
	public CleanDocument preprocessDocument(DocumentRequest request) {
		log.info("Preprocessing document: {} ({})", request.documentId(), request.title());

		String normalised = request.rawContent().trim().replaceAll("\\s+", " ").replaceAll("(?m)^\\s*$\\n", "");

		int wordCount = normalised.isEmpty() ? 0 : normalised.split("\\s+").length;

		String language = request.language() != null ? request.language() : "en";

		return new CleanDocument(request.documentId(), request.title(), normalised, language, wordCount);
	}

	// ── Step 2 ────────────────────────────────────────────────────────────

	/**
	 * Provides default MetadataHints when the caller did not supply any.
	 *
	 * <p>
	 * GOAP automatically includes this action in the plan when {@code it:MetadataHints}
	 * is absent from the blackboard. When the caller has already placed a
	 * {@link MetadataHints} object on the blackboard, GOAP skips this action (its
	 * auto-generated precondition {@code it:MetadataHints = FALSE} is not satisfied).
	 * This is the idiomatic Embabel pattern for optional inputs: provide a
	 * default-producer action rather than using {@code @Nullable}.
	 */
	@Action(description = "Provide default metadata extraction hints when the caller did not supply any.")
	public MetadataHints provideDefaultMetadataHints(DocumentRequest request) {
		log.info("No MetadataHints provided for document {}; using defaults.", request.documentId());
		return new MetadataHints(null, null, false);
	}

	// ── Step 3 ────────────────────────────────────────────────────────────

	/**
	 * Extracts structured metadata from the cleaned document.
	 *
	 * <p>
	 * By the time this action runs, {@link MetadataHints} is guaranteed to be on the
	 * blackboard — either supplied by the caller or produced by
	 * {@link #provideDefaultMetadataHints}. GOAP resolved the optional-input problem by
	 * choosing the right precursor action.
	 *
	 * <p>
	 * {@code value = 0.3} — annotates this step's relative value for the planner.
	 * Utility-style planners use net value (value − cost) to rank steps; GOAP records it
	 * as informational metadata visible in the workflow visualizer.
	 */
	@Action(description = "Extract metadata (topics, keywords, reading time) from the cleaned document. Guided by MetadataHints.",
			value = 0.3)
	public DocumentMetadata extractMetadata(CleanDocument cleanDoc, MetadataHints hints, Ai ai) {
		log.info("Extracting metadata for: {} wordCount={}", cleanDoc.documentId(), cleanDoc.wordCount());

		String hintContext = hints != null ? """
				Caller-supplied hints:
				- Expected topics: %s
				- Domain context:  %s
				- Require keywords: %s
				""".formatted(hints.expectedTopics(), hints.domainContext(), hints.requireKeywords())
				: "No caller hints provided — use your best judgement.";

		var prompt = """
				You are a document metadata extractor.

				Analyse the document below and return structured metadata.

				Document title: %s
				Language: %s
				Word count: %d

				%s

				Content (first 2000 chars):
				%s

				Extract:
				1. detectedLanguage — ISO 639-1 code (e.g. "en", "de")
				2. primaryTopic — one short phrase
				3. keywords — array of 5–10 relevant keywords
				4. estimatedReadingTime — e.g. "3 min"
				""".formatted(cleanDoc.title(), cleanDoc.language(), cleanDoc.wordCount(), hintContext,
				cleanDoc.normalizedContent().substring(0, Math.min(2000, cleanDoc.normalizedContent().length())));

		return ai.withDefaultLlm().creating(DocumentMetadata.class).fromPrompt(prompt);
	}

	// ── Step 3 ────────────────────────────────────────────────────────────

	/**
	 * Performs deep content analysis — tone, themes, structure, quality.
	 *
	 * <p>
	 * Skips LLM analysis for documents with 50 words or fewer — short text does not have
	 * enough content for meaningful tone/theme scoring. Returns a lightweight stub
	 * {@link ContentAnalysis} for those documents.
	 *
	 * <p>
	 * {@code canRerun = true}: Allows this step to be re-executed if the planner decides
	 * to re-run it (e.g., after new data is placed on the blackboard). Without this flag,
	 * an action is treated as a one-shot step and will not be replanned once it has run.
	 */
	@Action(description = "Deep content analysis: tone, themes, structure, quality score.", canRerun = true)
	public ContentAnalysis analyzeContent(CleanDocument cleanDoc, DocumentMetadata metadata, Ai ai) {
		log.info("Analysing content: {} topic={}", cleanDoc.documentId(), metadata.primaryTopic());

		// Short documents do not have enough content for meaningful analysis.
		if (cleanDoc.wordCount() <= 50) {
			log.info("Document too short for deep analysis (wordCount={}), returning stub", cleanDoc.wordCount());
			return new ContentAnalysis(cleanDoc.documentId(), "informative", new String[] { metadata.primaryTopic() },
					"Document is too short for structural assessment.", 0.5, "Not assessed — document too short.");
		}

		var prompt = """
				You are a document quality analyst.

				Perform a deep analysis of the document below.

				Title: %s
				Primary topic: %s
				Keywords: %s
				Word count: %d

				Content:
				%s

				Return:
				1. tone — e.g. "formal", "casual", "persuasive", "informative"
				2. mainThemes — array of 3–5 themes
				3. structureAssessment — short sentence on document structure quality
				4. qualityScore — float 0.0 (poor) to 1.0 (excellent)
				5. targetAudienceAlignment — how well the content suits the target audience
				""".formatted(cleanDoc.title(), metadata.primaryTopic(),
				String.join(", ", metadata.keywords() != null ? metadata.keywords() : new String[] {}),
				cleanDoc.wordCount(),
				cleanDoc.normalizedContent().substring(0, Math.min(3000, cleanDoc.normalizedContent().length())));

		return ai.withDefaultLlm().creating(ContentAnalysis.class).fromPrompt(prompt);
	}

	// ── Step 4 — Goal ─────────────────────────────────────────────────────

	/**
	 * Produces the final document summary — the agent's goal.
	 *
	 * <p>
	 * {@code @AchievesGoal} with all optional attributes:
	 * <ul>
	 * <li>{@code value = 0.9} — high value signals this goal is very desirable for
	 * Utility/Autonomy-based goal selection</li>
	 * <li>{@code tags} — labels used for goal cataloguing and filtering</li>
	 * <li>{@code examples} — natural language examples of inputs that trigger this goal,
	 * used by Autonomy's LLM-based goal ranking</li>
	 * <li>{@code export = @Export(remote=true, ...)} — publishes this goal as an MCP tool
	 * so external agents/tools can invoke it remotely</li>
	 * </ul>
	 */
	@AchievesGoal(
			description = "Produce a concise, structured summary of the document including key points and a recommended action.",
			value = 0.9, tags = { "document", "summarization", "nlp" },
			examples = { "Summarise my quarterly report", "Process and analyse this technical document",
					"Extract key points from the attached whitepaper" },
			export = @Export(remote = true, name = "summarizeDocument", startingInputTypes = { DocumentRequest.class }))
	@Action(description = "Generate the final document summary with key points and recommended action.")
	public DocumentSummary summarizeDocument(CleanDocument cleanDoc, DocumentMetadata metadata,
			ContentAnalysis analysis, Ai ai) {
		log.info("Summarising: {} qualityScore={}", cleanDoc.documentId(), analysis.qualityScore());

		var prompt = """
				You are a document summarisation system.

				Produce a concise, actionable summary of the document.

				Title: %s
				Primary topic: %s
				Tone: %s
				Main themes: %s
				Structure: %s
				Quality score: %.2f
				Target audience alignment: %s

				Content (first 4000 chars):
				%s

				Return:
				1. summary — 2–4 paragraph executive summary
				2. keyPoints — array of 3–7 bullet points
				3. recommendedAction — one sentence action recommendation
				4. confidenceScore — float 0.0–1.0 for summary confidence
				""".formatted(cleanDoc.title(), metadata.primaryTopic(), analysis.tone(),
				analysis.mainThemes() != null ? String.join(", ", analysis.mainThemes()) : "",
				analysis.structureAssessment(), analysis.qualityScore(), analysis.targetAudienceAlignment(),
				cleanDoc.normalizedContent().substring(0, Math.min(4000, cleanDoc.normalizedContent().length())));

		return ai.withDefaultLlm().creating(DocumentSummary.class).fromPrompt(prompt);
	}

}
