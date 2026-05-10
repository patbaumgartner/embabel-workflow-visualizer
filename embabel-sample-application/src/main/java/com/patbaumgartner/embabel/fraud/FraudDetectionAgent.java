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
 * Demo pattern: linear flow
 * - no @Condition annotations
 * - readOnly=true on the enrichment step (no side-effects, safe to cache)
 * - single @AchievesGoal at the very end
 *
 * Plan: TransactionRequest
 * → [enrichContext] (readOnly) → TransactionContext
 * → [screenForPatterns] → PatternScreening
 * → [decideFraud] (@AchievesGoal) → FraudDecision
 */
@Agent(name = "FraudDetectionAgent", description = "Screens financial transactions for fraud and produces an approve/block/review decision.", version = "1.0.0")
public class FraudDetectionAgent {

    private static final Logger log = LoggerFactory.getLogger(FraudDetectionAgent.class);

    /**
     * Step 1: Pure data enrichment — no LLM call, no side-effects.
     * Derives boolean risk signals from the raw transaction fields.
     * Marked readOnly=true so the planner knows it is safe to cache/replay.
     */
    @Action(description = "Enrich the transaction with derived risk signals (high-value, cross-border, unusual hour).", readOnly = true)
    public TransactionContext enrichContext(TransactionRequest request) {
        log.info("Enriching transaction context: {} amount={}", request.transactionId(), request.amount());

        boolean highValue = request.amount() != null && request.amount().compareTo(BigDecimal.valueOf(500)) > 0;

        boolean crossBorder = request.location() != null
                && !request.location().toLowerCase().contains("switzerland")
                && !request.location().toLowerCase().contains("ch");

        boolean unusualHour = false;
        if (request.timestamp() != null) {
            try {
                int hour = Instant.parse(request.timestamp()).atZone(ZoneOffset.UTC).getHour();
                unusualHour = hour >= 1 && hour <= 5;
            } catch (Exception ignored) {
            }
        }

        return new TransactionContext(request.transactionId(), request.accountId(), request.amount(),
                request.merchantName(), request.merchantCategory(), request.location(), request.timestamp(),
                highValue, crossBorder, unusualHour);
    }

    /**
     * Step 2: LLM-based pattern analysis using the enriched context.
     */
    @Action(description = "Screen the enriched transaction for known fraud patterns using LLM analysis.")
    public PatternScreening screenForPatterns(TransactionContext ctx, OperationContext context) {
        log.info("Screening patterns for: {} highValue={} crossBorder={} unusualHour={}",
                ctx.transactionId(), ctx.highValueTransaction(), ctx.crossBorderTransaction(), ctx.unusualHour());

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
                        ctx.timestamp() != null ? ctx.timestamp() : "unknown",
                        ctx.highValueTransaction(), ctx.crossBorderTransaction(), ctx.unusualHour());

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

/**
 * Fraud Detection Agent — screens financial transactions for suspicious
 * patterns.
 *
 * Plan: TransactionRequest → screenTransaction → TransactionScreening
 * → [AUTO_DECIDABLE] → makeAutoDecision → FraudDecision
 * → [REQUIRES_INVESTIGATION] → investigateFurther → FraudInvestigation
 * → makeInvestigatedDecision → FraudDecision
 */
@Agent(name = "FraudDetectionAgent", description = "Screens financial transactions for fraud and produces an approve/block/review decision.", version = "1.0.0")
public class FraudDetectionAgent {

    static final String AUTO_DECIDABLE = "autoDecidable";

    static final String REQUIRES_INVESTIGATION = "requiresInvestigation";

    private static final Logger log = LoggerFactory.getLogger(FraudDetectionAgent.class);

    /**
     * Step 1: Screen the transaction against velocity, location and behavioural
     * patterns.
     */
    @Action(description = "Screen a financial transaction for velocity anomalies, location mismatches and known fraud patterns.", post = {
            AUTO_DECIDABLE, REQUIRES_INVESTIGATION })
    public TransactionScreening screenTransaction(TransactionRequest request, OperationContext context) {
        log.info("Screening transaction: {} amount={} merchant={}", request.transactionId(), request.amount(),
                request.merchantName());

        var prompt = """
                You are a financial fraud detection system.
                Analyse the transaction below and assign a numeric risk score from 0.0 (no risk) to 1.0 (definite fraud).

                Transaction:
                - ID: %s
                - Amount: %s
                - Merchant: %s
                - Category: %s
                - Location: %s
                - Timestamp: %s

                Evaluate:
                1. Velocity anomaly — unusually high amount or unusual time of day
                2. Location anomaly — merchant location inconsistent with typical patterns
                3. Pattern anomaly — merchant category or purchase pattern looks unusual

                Return your findings with a numeric riskScore and a brief screeningSummary.
                """
                .formatted(request.transactionId(), request.amount(), request.merchantName(),
                        request.merchantCategory() != null ? request.merchantCategory() : "unknown",
                        request.location() != null ? request.location() : "unknown",
                        request.timestamp() != null ? request.timestamp() : "unknown");

        return context.ai().withDefaultLlm().creating(TransactionScreening.class).fromPrompt(prompt);
    }

    /**
     * Risk score ≤ 0.25 → clean; ≥ 0.85 → clear fraud. Both can be auto-decided.
     */
    @Condition(name = AUTO_DECIDABLE)
    public boolean autoDecidable(TransactionScreening screening) {
        return screening.riskScore() <= 0.25 || screening.riskScore() >= 0.85;
    }

    /** Medium risk (0.25 < score < 0.85) → deeper investigation needed. */
    @Condition(name = REQUIRES_INVESTIGATION)
    public boolean requiresInvestigation(TransactionScreening screening) {
        return screening.riskScore() > 0.25 && screening.riskScore() < 0.85;
    }

    /**
     * Step 2 (investigation branch): Deep-dive analysis before final ruling.
     */
    @Action(description = "Perform a deeper investigation of a medium-risk transaction before making a final decision.", pre = {
            REQUIRES_INVESTIGATION })
    public FraudInvestigation investigateFurther(TransactionScreening screening, OperationContext context) {
        log.info("Investigating transaction: {} riskScore={}", screening.transactionId(), screening.riskScore());

        var prompt = """
                You are a senior fraud analyst conducting a detailed investigation.

                Initial Screening Results:
                - Transaction ID: %s
                - Velocity Anomaly: %s
                - Location Anomaly: %s
                - Pattern Anomaly: %s
                - Risk Score: %.2f
                - Summary: %s

                Provide:
                1. Detailed investigation notes with possible legitimate explanations
                2. Any additional risk factors that increase or decrease suspicion
                3. A definitive conclusion: is this transaction fraudulent? (true/false)
                """.formatted(screening.transactionId(), screening.velocityAnomaly(), screening.locationAnomaly(),
                screening.patternAnomaly(), screening.riskScore(), screening.screeningSummary());

        return context.ai().withDefaultLlm().creating(FraudInvestigation.class).fromPrompt(prompt);
    }

    /**
     * Step 2a (auto branch): Produce a final decision for clear-cut cases.
     */
    @AchievesGoal(description = "Produce a fraud decision for this transaction.")
    @Action(description = "Make an automatic fraud decision for clear-cut low- or high-risk transactions.", pre = {
            AUTO_DECIDABLE })
    public FraudDecision makeAutoDecision(TransactionScreening screening, OperationContext context) {
        log.info("Auto-deciding transaction: {} riskScore={}", screening.transactionId(), screening.riskScore());

        var prompt = """
                You are a fraud decision engine.
                Based on the screening results below, issue a final fraud decision.

                Transaction ID: %s
                Risk Score: %.2f
                Velocity Anomaly: %s
                Location Anomaly: %s
                Pattern Anomaly: %s
                Screening Summary: %s

                Decision options: APPROVE (score ≤ 0.25), BLOCK (score ≥ 0.85)
                Provide a concise reason for your decision.
                """.formatted(screening.transactionId(), screening.riskScore(), screening.velocityAnomaly(),
                screening.locationAnomaly(), screening.patternAnomaly(), screening.screeningSummary());

        return context.ai().withDefaultLlm().creating(FraudDecision.class).fromPrompt(prompt);
    }

    /**
     * Step 3 (investigation branch): Produce a final decision after investigation.
     */
    @AchievesGoal(description = "Produce a fraud decision for this transaction.")
    @Action(description = "Make a final fraud decision after a deeper investigation of a medium-risk transaction.", pre = {
            REQUIRES_INVESTIGATION })
    public FraudDecision makeInvestigatedDecision(TransactionScreening screening, FraudInvestigation investigation,
            OperationContext context) {
        log.info("Making post-investigation decision for: {} definitive={}", screening.transactionId(),
                investigation.definitivelyFraudulent());

        var prompt = """
                You are a fraud decision engine reviewing a case after investigation.
                Issue a final fraud decision.

                Transaction ID: %s
                Risk Score: %.2f
                Screening Summary: %s

                Investigation Notes: %s
                Additional Risk Factors: %s
                Definitively Fraudulent: %s

                Decision options: APPROVE, BLOCK, MANUAL_REVIEW
                Provide a concise reason for your decision.
                """.formatted(screening.transactionId(), screening.riskScore(), screening.screeningSummary(),
                investigation.investigationNotes(), investigation.additionalRiskFactors(),
                investigation.definitivelyFraudulent());

        return context.ai().withDefaultLlm().creating(FraudDecision.class).fromPrompt(prompt);
    }

}
