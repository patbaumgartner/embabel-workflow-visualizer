package com.patbaumgartner.embabel.fraud;

import com.embabel.agent.api.invocation.AgentInvocation;
import com.embabel.agent.test.integration.EmbabelMockitoIntegrationTest;
import com.patbaumgartner.embabel.fraud.FraudModels.FraudDecision;
import com.patbaumgartner.embabel.fraud.FraudModels.PatternScreening;
import com.patbaumgartner.embabel.fraud.FraudModels.TransactionRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for {@link FraudDetectionAgent}.
 *
 * <p>
 * Extends {@link EmbabelMockitoIntegrationTest} which provides a mocked
 * {@code AgentPlatform} that intercepts LLM calls. Real LLM calls are replaced with
 * stubbed responses via {@code whenCreateObject()}.
 *
 * <p>
 * Tests the full agent flow:
 *
 * <pre>
 *   TransactionRequest → enrichContext (pure Java) → PatternScreening (mocked LLM)
 *     → decideFraud (mocked LLM) → FraudDecision
 * </pre>
 */
@SpringBootTest
@Import(FraudDetectionAgent.class)
@DisplayName("FraudDetectionAgent — integration test (mocked LLM)")
class FraudDetectionAgentIntegrationTest extends EmbabelMockitoIntegrationTest {

	@Test
	@DisplayName("approves a clean low-risk transaction end-to-end")
	void approvesLowRiskTransaction() {
		// Stub the PatternScreening LLM call — return a low-risk screening result
		whenCreateObject(prompt -> prompt.contains("transactionId") || prompt.contains("Analyse"),
				PatternScreening.class)
			.thenReturn(new PatternScreening("tx-int-1", 0.10, new String[] { "none" }, "Low risk"));

		// Stub the FraudDecision LLM call — return an APPROVE decision
		whenCreateObject(prompt -> prompt.contains("fraud decision") || prompt.contains("Decision"),
				FraudDecision.class)
			.thenReturn(new FraudDecision("tx-int-1", "APPROVE", 0.10, "Low risk transaction"));

		var request = new TransactionRequest("tx-int-1", "acc-1", new BigDecimal("50.00"), "Local Shop", "retail",
				"Berne, CH", "2025-06-01T10:00:00Z");

		FraudDecision decision = AgentInvocation.create(agentPlatform, FraudDecision.class).invoke(request);

		assertThat(decision).isNotNull();
		assertThat(decision.decision()).isEqualTo("APPROVE");
		assertThat(decision.transactionId()).isEqualTo("tx-int-1");
	}

	@Test
	@DisplayName("blocks a high-risk transaction end-to-end")
	void blocksHighRiskTransaction() {
		whenCreateObject(prompt -> true, PatternScreening.class).thenReturn(new PatternScreening("tx-int-2", 0.95,
				new String[] { "high-value", "cross-border", "unusual-hour" }, "Definite fraud"));

		whenCreateObject(prompt -> true, FraudDecision.class)
			.thenReturn(new FraudDecision("tx-int-2", "BLOCK", 0.95, "Multiple fraud indicators"));

		var request = new TransactionRequest("tx-int-2", "acc-2", new BigDecimal("5000.00"), "Unknown Merchant",
				"crypto", "Lagos, NG", "2025-06-01T03:30:00Z");

		FraudDecision decision = AgentInvocation.create(agentPlatform, FraudDecision.class).invoke(request);

		assertThat(decision).isNotNull();
		assertThat(decision.decision()).isEqualTo("BLOCK");
	}

}
