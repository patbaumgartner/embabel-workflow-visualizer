package com.patbaumgartner.embabel.fraud;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * Domain model for the Fraud Detection agent.
 *
 * Flow: TransactionRequest → [enrichContext] → TransactionContext → [screenForPatterns] →
 * PatternScreening → [decideFraud] → FraudDecision
 *
 * Demo pattern: pure linear pipeline — no conditions, no branching, readOnly enrichment
 * step, single @AchievesGoal at the end.
 */
public class FraudModels {

	// ── Input ───────────────────────────────────────────────────────────

	public record TransactionRequest(String transactionId, String accountId, BigDecimal amount, String merchantName,
			String merchantCategory, String location, String timestamp) {
	}

	// ── Intermediate ────────────────────────────────────────────────────

	/**
	 * Enriched view of the transaction: derived risk signals computed from the raw input
	 * without calling an LLM. Produced by a readOnly action.
	 */
	public record TransactionContext(String transactionId, String accountId, BigDecimal amount, String merchantName,
			String merchantCategory, String location, String timestamp, boolean highValueTransaction,
			boolean crossBorderTransaction, boolean unusualHour) {
	}

	/**
	 * Result of the LLM-based fraud pattern analysis. riskScore: 0.0 (clean) to 1.0
	 * (definite fraud).
	 */
	public record PatternScreening(String transactionId, double riskScore, String[] detectedPatterns,
			String riskRationale) {
	}

	// ── Output ──────────────────────────────────────────────────────────

	/**
	 * Final fraud decision. decision: APPROVE, BLOCK, or MANUAL_REVIEW.
	 */
	public record FraudDecision(String transactionId, String decision, double riskScore, String reason) {
	}

	// ── REST API types ───────────────────────────────────────────────────

	public record ApiTransactionRequest(@NotBlank String transactionId, @NotBlank String accountId,
			@NotNull BigDecimal amount, @NotBlank String merchantName, String merchantCategory, String location,
			String timestamp) {
		public TransactionRequest toDomain() {
			return new TransactionRequest(transactionId, accountId, amount, merchantName, merchantCategory, location,
					timestamp);
		}
	}

}
