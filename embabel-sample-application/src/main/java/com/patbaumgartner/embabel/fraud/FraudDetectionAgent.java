package com.patbaumgartner.embabel.fraud;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.common.OperationContext;
import com.patbaumgartner.embabel.fraud.FraudModels.FraudDecision;
import com.patbaumgartner.embabel.fraud.FraudModels.PatternScreening;
import com.patbaumgartner.embabel.fraud.FraudModels.TransactionContext;
import com.patbaumgartner.embabel.fraud.FraudModels.TransactionRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * Fraud Detection Agent — pure linear pipeline, no conditions, no branching.
 *
 * Demo pattern: linear flow - no @Condition annotations - readOnly=true on the enrichment
 * step (no side-effects, safe to cache) - single @AchievesGoal at the very end
 *
 * Plan: TransactionRequest → [enrichContext] (readOnly) → TransactionContext →
 * [screenForPatterns] → PatternScreening → [decideFraud] (@AchievesGoal) → FraudDecision
 */
@Agent(name = "FraudDetectionAgent",
		description = "Screens financial transactions for fraud and produces an approve/block/review decision.",
		version = "1.0.0")
public class FraudDetectionAgent {

	private static final Logger log = LoggerFactory.getLogger(FraudDetectionAgent.class);

	/**
	 * Step 1: Pure data enrichment — no LLM call, no side-effects. Derives boolean risk
	 * signals from the raw transaction fields. Marked readOnly=true so the planner knows
	 * it is safe to cache/replay.
	 */
	@Action(description = "Enrich the transaction with derived risk signals (high-value, cross-border, unusual hour).",
			readOnly = true)
	public TransactionContext enrichContext(TransactionRequest request) {
		log.info("Enriching transaction context: {} amount={}", request.transactionId(), request.amount());

		boolean highValue = request.amount() != null && request.amount().compareTo(BigDecimal.valueOf(500)) > 0;

		boolean crossBorder = request.location() != null && !request.location().toLowerCase().contains("switzerland")
				&& !request.location().toLowerCase().contains("ch");

		boolean unusualHour = false;
		if (request.timestamp() != null) {
			try {
				int hour = Instant.parse(request.timestamp()).atZone(ZoneOffset.UTC).getHour();
				unusualHour = hour >= 1 && hour <= 5;
			}
			catch (Exception ignored) {
			}
		}

		return new TransactionContext(request.transactionId(), request.accountId(), request.amount(),
				request.merchantName(), request.merchantCategory(), request.location(), request.timestamp(), highValue,
				crossBorder, unusualHour);
	}

	/**
	 * Step 2: LLM-based pattern analysis using the enriched context.
	 */
	@Action(description = "Screen the enriched transaction for known fraud patterns using LLM analysis.")
	public PatternScreening screenForPatterns(TransactionContext ctx, OperationContext context) {
		log.info("Screening patterns for: {} highValue={} crossBorder={} unusualHour={}", ctx.transactionId(),
				ctx.highValueTransaction(), ctx.crossBorderTransaction(), ctx.unusualHour());

		var prompt = """
				You are a financial fraud detection system.
				Analyse the enriched transaction below and assign a numeric risk score from 0.0 (clean) to 1.0 (definite fraud).

				Transaction:
				- ID: %s
				- Amount: %s
				- Merchant: %s (%s)
				- Location: %s
				- Timestamp: %s

				Derived signals:
				- High-value transaction (>500): %s
				- Cross-border transaction: %s
				- Unusual hour (01:00–05:00 UTC): %s

				Return:
				1. riskScore (0.0–1.0)
				2. detectedPatterns — list of short labels for patterns found (e.g. high-value, cross-border, unusual-hour, known-fraud-merchant)
				3. riskRationale — one sentence explaining the score
				"""
			.formatted(ctx.transactionId(), ctx.amount(), ctx.merchantName(),
					ctx.merchantCategory() != null ? ctx.merchantCategory() : "unknown",
					ctx.location() != null ? ctx.location() : "unknown",
					ctx.timestamp() != null ? ctx.timestamp() : "unknown", ctx.highValueTransaction(),
					ctx.crossBorderTransaction(), ctx.unusualHour());

		return context.ai().withDefaultLlm().creating(PatternScreening.class).fromPrompt(prompt);
	}

	/**
	 * Step 3: Final fraud decision — the only @AchievesGoal in this agent.
	 */
	@AchievesGoal(description = "Produce a fraud decision for this transaction.")
	@Action(description = "Issue a final fraud decision based on the pattern screening result.")
	public FraudDecision decideFraud(PatternScreening screening, OperationContext context) {
		log.info("Deciding fraud for: {} riskScore={}", screening.transactionId(), screening.riskScore());

		var prompt = """
				You are a fraud decision engine.
				Issue a final fraud decision based on the pattern screening below.

				Transaction ID: %s
				Risk Score: %.2f
				Detected Patterns: %s
				Risk Rationale: %s

				Rules:
				- score ≤ 0.25 → APPROVE
				- score ≥ 0.75 → BLOCK
				- 0.25 < score < 0.75 → MANUAL_REVIEW

				Provide the decision and a concise reason.
				""".formatted(screening.transactionId(), screening.riskScore(),
				String.join(", ", screening.detectedPatterns()), screening.riskRationale());

		return context.ai().withDefaultLlm().creating(FraudDecision.class).fromPrompt(prompt);
	}

}
