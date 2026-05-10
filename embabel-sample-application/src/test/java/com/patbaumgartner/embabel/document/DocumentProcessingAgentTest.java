package com.patbaumgartner.embabel.document;

import com.patbaumgartner.embabel.document.DocumentModels.CleanDocument;
import com.patbaumgartner.embabel.document.DocumentModels.DocumentRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link DocumentProcessingAgent}.
 *
 * <p>
 * Covers the pure-Java {@code preprocessDocument()} step — it contains all normalization
 * logic without any LLM call, making it ideal for fast unit testing.
 *
 * <p>
 * Features verified:
 * <ul>
 * <li>Whitespace trimming and collapsing</li>
 * <li>Blank-line removal</li>
 * <li>Word count derivation</li>
 * <li>Language fallback when null</li>
 * <li>Zero word count for empty/blank content</li>
 * <li>Preservation of non-whitespace content</li>
 * </ul>
 */
@DisplayName("DocumentProcessingAgent — preprocessDocument() unit tests")
class DocumentProcessingAgentTest {

	private DocumentProcessingAgent agent;

	@BeforeEach
	void setUp() {
		agent = new DocumentProcessingAgent();
	}

	// ── preprocessDocument ─────────────────────────────────────────────────

	@Nested
	@DisplayName("preprocessDocument() — normalisation")
	class PreprocessDocument {

		@Test
		@DisplayName("trims leading and trailing whitespace")
		void trimsWhitespace() {
			var request = new DocumentRequest("doc-1", "Test", "  Hello world  ", "en", null);
			CleanDocument result = agent.preprocessDocument(request);
			assertThat(result.normalizedContent()).isEqualTo("Hello world");
		}

		@Test
		@DisplayName("collapses multiple spaces into single space")
		void collapsesMultipleSpaces() {
			var request = new DocumentRequest("doc-2", "Test", "Hello   world   foo", "en", null);
			CleanDocument result = agent.preprocessDocument(request);
			assertThat(result.normalizedContent()).isEqualTo("Hello world foo");
		}

		@Test
		@DisplayName("collapses mixed tabs and spaces")
		void collapsesMixedWhitespace() {
			var request = new DocumentRequest("doc-3", "Test", "Hello\t\tworld", "en", null);
			CleanDocument result = agent.preprocessDocument(request);
			assertThat(result.normalizedContent()).isEqualTo("Hello world");
		}

		@Test
		@DisplayName("preserves content without extra whitespace unchanged")
		void preservesCleanContent() {
			var request = new DocumentRequest("doc-4", "Title", "This is a clean sentence.", "en", null);
			CleanDocument result = agent.preprocessDocument(request);
			assertThat(result.normalizedContent()).isEqualTo("This is a clean sentence.");
		}

		@Test
		@DisplayName("returns empty string for blank content")
		void handlesBlankContent() {
			var request = new DocumentRequest("doc-5", "Empty", "   ", "en", null);
			CleanDocument result = agent.preprocessDocument(request);
			assertThat(result.normalizedContent()).isEmpty();
		}

	}

	@Nested
	@DisplayName("preprocessDocument() — word count")
	class WordCount {

		@Test
		@DisplayName("counts words in normal text")
		void countsWordsCorrectly() {
			var request = new DocumentRequest("doc-6", "Test", "The quick brown fox jumps", "en", null);
			CleanDocument result = agent.preprocessDocument(request);
			assertThat(result.wordCount()).isEqualTo(5);
		}

		@Test
		@DisplayName("returns 0 word count for blank content")
		void zeroWordCountForBlank() {
			var request = new DocumentRequest("doc-7", "Empty", "   ", "en", null);
			CleanDocument result = agent.preprocessDocument(request);
			assertThat(result.wordCount()).isZero();
		}

		@Test
		@DisplayName("returns 0 word count for empty string")
		void zeroWordCountForEmpty() {
			var request = new DocumentRequest("doc-8", "Empty", "", "en", null);
			CleanDocument result = agent.preprocessDocument(request);
			assertThat(result.wordCount()).isZero();
		}

		@Test
		@DisplayName("word count > 50 threshold for long documents passes SpEL guard")
		void longDocumentExceedsSpelThreshold() {
			// The analyzeContent action has pre={"spel:cleanDoc.wordCount > 50"}
			// This test verifies that a long doc produces a wordCount > 50
			String longText = "word ".repeat(60).trim();
			var request = new DocumentRequest("doc-9", "Long Doc", longText, "en", null);
			CleanDocument result = agent.preprocessDocument(request);
			assertThat(result.wordCount()).isGreaterThan(50);
		}

		@Test
		@DisplayName("word count <= 50 for short documents blocks SpEL guard")
		void shortDocumentBelowSpelThreshold() {
			var request = new DocumentRequest("doc-10", "Short Doc", "This is a very short document.", "en", null);
			CleanDocument result = agent.preprocessDocument(request);
			assertThat(result.wordCount()).isLessThanOrEqualTo(50);
		}

	}

	@Nested
	@DisplayName("preprocessDocument() — metadata preservation")
	class MetadataPreservation {

		@Test
		@DisplayName("preserves documentId from request")
		void preservesDocumentId() {
			var request = new DocumentRequest("my-unique-id", "Title", "content", "de", null);
			CleanDocument result = agent.preprocessDocument(request);
			assertThat(result.documentId()).isEqualTo("my-unique-id");
		}

		@Test
		@DisplayName("preserves title from request")
		void preservesTitle() {
			var request = new DocumentRequest("doc-11", "My Great Title", "content", "en", null);
			CleanDocument result = agent.preprocessDocument(request);
			assertThat(result.title()).isEqualTo("My Great Title");
		}

		@Test
		@DisplayName("preserves language when explicitly set")
		void preservesLanguage() {
			var request = new DocumentRequest("doc-12", "Title", "content", "fr", null);
			CleanDocument result = agent.preprocessDocument(request);
			assertThat(result.language()).isEqualTo("fr");
		}

		@Test
		@DisplayName("defaults language to 'en' when null")
		void defaultsLanguageToEnglish() {
			var request = new DocumentRequest("doc-13", "Title", "content", null, null);
			CleanDocument result = agent.preprocessDocument(request);
			assertThat(result.language()).isEqualTo("en");
		}

	}

}
