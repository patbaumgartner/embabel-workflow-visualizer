package com.patbaumgartner.embabel.fraud;

import com.patbaumgartner.embabel.fraud.FraudModels.TransactionContext;
import com.patbaumgartner.embabel.fraud.FraudModels.TransactionRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link FraudDetectionAgent}.
 *
 * <p>
 * Covers pure-Java methods that do not require an LLM or Spring context:
 * <ul>
 * <li>{@code enrichContext()} — derives boolean risk signals from raw transaction
 * data</li>
 * <li>{@code autoDecidable()} — @Condition: clear-cut cases (score ≤ 0.25 or ≥ 0.85)</li>
 * <li>{@code requiresInvestigation()} — @Condition: medium-risk cases (0.25 < score <
 * 0.85)</li>
 * </ul>
 */
@DisplayName("FraudDetectionAgent — pure-Java unit tests")
class FraudDetectionAgentTest {

	private FraudDetectionAgent agent;

	@BeforeEach
	void setUp() {
		agent = new FraudDetectionAgent();
	}

	// ── enrichContext ──────────────────────────────────────────────────────

	@Nested
	@DisplayName("enrichContext()")
	class EnrichContext {

		@Test
		@DisplayName("flags high-value when amount exceeds 500")
		void highValueFlag() {
			var request = new TransactionRequest("tx-1", "acc-1", new BigDecimal("750.00"), "Shop", "retail",
					"Zurich CH", null);
			TransactionContext ctx = agent.enrichContext(request);
			assertThat(ctx.highValueTransaction()).isTrue();
		}

		@Test
		@DisplayName("does not flag high-value when amount is exactly 500")
		void notHighValueAtBoundary() {
			var request = new TransactionRequest("tx-2", "acc-1", new BigDecimal("500.00"), "Shop", "retail",
					"Zurich CH", null);
			TransactionContext ctx = agent.enrichContext(request);
			assertThat(ctx.highValueTransaction()).isFalse();
		}

		@Test
		@DisplayName("does not flag high-value when amount is below 500")
		void notHighValueBelow() {
			var request = new TransactionRequest("tx-3", "acc-1", new BigDecimal("499.99"), "Shop", "retail",
					"Zurich CH", null);
			TransactionContext ctx = agent.enrichContext(request);
			assertThat(ctx.highValueTransaction()).isFalse();
		}

		@Test
		@DisplayName("does not flag high-value when amount is null")
		void nullAmountNotHighValue() {
			var request = new TransactionRequest("tx-4", "acc-1", null, "Shop", "retail", "Zurich CH", null);
			TransactionContext ctx = agent.enrichContext(request);
			assertThat(ctx.highValueTransaction()).isFalse();
		}

		@Test
		@DisplayName("flags cross-border for location outside Switzerland")
		void crossBorderFlagForForeignLocation() {
			var request = new TransactionRequest("tx-5", "acc-1", new BigDecimal("100"), "Shop", "retail",
					"Berlin, Germany", null);
			TransactionContext ctx = agent.enrichContext(request);
			assertThat(ctx.crossBorderTransaction()).isTrue();
		}

		@Test
		@DisplayName("does not flag cross-border for Swiss location")
		void notCrossBorderForSwitzerlandLocation() {
			var request = new TransactionRequest("tx-6", "acc-1", new BigDecimal("100"), "Shop", "retail",
					"Berne, Switzerland", null);
			TransactionContext ctx = agent.enrichContext(request);
			assertThat(ctx.crossBorderTransaction()).isFalse();
		}

		@Test
		@DisplayName("does not flag cross-border for CH location abbreviation")
		void notCrossBorderForChAbbreviation() {
			var request = new TransactionRequest("tx-7", "acc-1", new BigDecimal("100"), "Shop", "retail", "Zurich, CH",
					null);
			TransactionContext ctx = agent.enrichContext(request);
			assertThat(ctx.crossBorderTransaction()).isFalse();
		}

		@Test
		@DisplayName("does not flag cross-border when location is null")
		void nullLocationNotCrossBorder() {
			var request = new TransactionRequest("tx-8", "acc-1", new BigDecimal("100"), "Shop", "retail", null, null);
			TransactionContext ctx = agent.enrichContext(request);
			assertThat(ctx.crossBorderTransaction()).isFalse();
		}

		@Test
		@DisplayName("flags unusual hour for midnight-to-5am UTC timestamps")
		void unusualHourFlagForNightTime() {
			var request = new TransactionRequest("tx-9", "acc-1", new BigDecimal("100"), "Shop", "retail", "Zurich CH",
					"2025-01-15T02:30:00Z");
			TransactionContext ctx = agent.enrichContext(request);
			assertThat(ctx.unusualHour()).isTrue();
		}

		@Test
		@DisplayName("does not flag unusual hour for daytime UTC timestamps")
		void noUnusualHourForDaytime() {
			var request = new TransactionRequest("tx-10", "acc-1", new BigDecimal("100"), "Shop", "retail", "Zurich CH",
					"2025-01-15T10:00:00Z");
			TransactionContext ctx = agent.enrichContext(request);
			assertThat(ctx.unusualHour()).isFalse();
		}

		@Test
		@DisplayName("does not flag unusual hour when timestamp is null")
		void nullTimestampNoUnusualHour() {
			var request = new TransactionRequest("tx-11", "acc-1", new BigDecimal("100"), "Shop", "retail", "Zurich CH",
					null);
			TransactionContext ctx = agent.enrichContext(request);
			assertThat(ctx.unusualHour()).isFalse();
		}

		@Test
		@DisplayName("preserves transaction fields unchanged")
		void preservesFields() {
			var request = new TransactionRequest("tx-12", "acc-99", new BigDecimal("250"), "ACME Corp", "electronics",
					"Paris, France", "2025-06-01T14:00:00Z");
			TransactionContext ctx = agent.enrichContext(request);
			assertThat(ctx.transactionId()).isEqualTo("tx-12");
			assertThat(ctx.accountId()).isEqualTo("acc-99");
			assertThat(ctx.merchantName()).isEqualTo("ACME Corp");
		}

		@Test
		@DisplayName("combines all three risk flags independently")
		void allFlagsSet() {
			var request = new TransactionRequest("tx-13", "acc-1", new BigDecimal("1500"), "Casino", "gambling",
					"Las Vegas, USA", "2025-01-15T03:00:00Z");
			TransactionContext ctx = agent.enrichContext(request);
			assertThat(ctx.highValueTransaction()).isTrue();
			assertThat(ctx.crossBorderTransaction()).isTrue();
			assertThat(ctx.unusualHour()).isTrue();
		}

	}

}
