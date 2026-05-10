package com.patbaumgartner.embabel.sentiment;

import com.patbaumgartner.embabel.sentiment.SentimentModels.SentimentClassification;
import com.patbaumgartner.embabel.sentiment.SentimentModels.SentimentInsight;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SentimentAnalysisAgent}.
 *
 * <p>
 * Covers pure-Java @Cost methods that determine dynamic planner costs:
 * <ul>
 * <li>{@code computeDeepAnalysisCost()} — higher cost for negative/urgent feedback</li>
 * <li>{@code computeResponseCost()} — higher cost for escalation actions</li>
 * </ul>
 */
@DisplayName("SentimentAnalysisAgent — @Cost method unit tests")
class SentimentAnalysisAgentTest {

	private SentimentAnalysisAgent agent;

	@BeforeEach
	void setUp() {
		agent = new SentimentAnalysisAgent();
	}

	// ── computeDeepAnalysisCost ────────────────────────────────────────────

	@Nested
	@DisplayName("computeDeepAnalysisCost()")
	class ComputeDeepAnalysisCost {

		@Test
		@DisplayName("returns higher cost for NEGATIVE sentiment with low score")
		void higherCostForNegativeSentiment() {
			var neg = new SentimentClassification("fb-1", "NEGATIVE", -0.8, true, "very negative");
			var pos = new SentimentClassification("fb-2", "POSITIVE", 0.8, false, "very positive");
			assertThat(agent.computeDeepAnalysisCost(neg)).isGreaterThan(agent.computeDeepAnalysisCost(pos));
		}

		@Test
		@DisplayName("returns higher cost when urgentIssueDetected is true")
		void higherCostForUrgentIssue() {
			var urgent = new SentimentClassification("fb-3", "NEGATIVE", -0.5, true, "urgent");
			var notUrgent = new SentimentClassification("fb-4", "NEGATIVE", -0.5, false, "not urgent");
			assertThat(agent.computeDeepAnalysisCost(urgent))
				.isGreaterThanOrEqualTo(agent.computeDeepAnalysisCost(notUrgent));
		}

		@Test
		@DisplayName("returns lower cost for POSITIVE sentiment")
		void lowerCostForPositiveSentiment() {
			var pos = new SentimentClassification("fb-5", "POSITIVE", 0.9, false, "excellent");
			double cost = agent.computeDeepAnalysisCost(pos);
			assertThat(cost).isLessThan(5.0); // Should be a reasonable value, not
												// astronomical
		}

		@ParameterizedTest(name = "sentiment={0} score={1} urgent={2}")
		@CsvSource({ "POSITIVE,  0.9,  false", "NEUTRAL,   0.0,  false", "NEGATIVE, -0.5,  false",
				"NEGATIVE, -0.9,  true", "MIXED,     0.1,  true" })
		@DisplayName("always returns a positive cost value")
		void alwaysPositiveCost(String sentiment, double score, boolean urgent) {
			var classification = new SentimentClassification("fb-p", sentiment, score, urgent, "test");
			assertThat(agent.computeDeepAnalysisCost(classification)).isGreaterThan(0);
		}

	}

	// ── computeResponseCost ───────────────────────────────────────────────

	@Nested
	@DisplayName("computeResponseCost()")
	class ComputeResponseCost {

		@Test
		@DisplayName("returns higher cost for escalate action")
		void higherCostForEscalate() {
			var escalate = new SentimentInsight("fb-6", new String[] { "billing" }, "frustrated",
					"escalate to engineering", "needs senior help");
			var close = new SentimentInsight("fb-7", new String[] { "general" }, "happy", "auto-reply", "routine");
			assertThat(agent.computeResponseCost(escalate)).isGreaterThan(agent.computeResponseCost(close));
		}

		@Test
		@DisplayName("returns lower cost for auto-reply action")
		void lowerCostForAutoReply() {
			var autoReply = new SentimentInsight("fb-8", new String[] { "praise" }, "delighted", "auto-reply",
					"just say thanks");
			double cost = agent.computeResponseCost(autoReply);
			assertThat(cost).isGreaterThan(0);
			assertThat(cost).isLessThan(10.0);
		}

		@Test
		@DisplayName("always returns a positive cost value")
		void alwaysPositiveCost() {
			var insight = new SentimentInsight("fb-9", new String[] {}, "neutral", "follow-up", "standard response");
			assertThat(agent.computeResponseCost(insight)).isGreaterThan(0);
		}

	}

}
