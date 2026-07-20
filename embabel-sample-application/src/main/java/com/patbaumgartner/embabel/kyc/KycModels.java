package com.patbaumgartner.embabel.kyc;

import jakarta.validation.constraints.NotBlank;

/**
 * Domain model for KYC verification agent.
 *
 * <p>
 * Flow:
 *
 * <pre>
 * KycRequest → [screenCustomer] → KycScreening → [branch via conditions] → KycAssessment
 * </pre>
 *
 * <p>
 * The planner uses these types to build the execution plan automatically. Domain objects
 * ARE the routing mechanism — no string-based state machine needed.
 */
public class KycModels {

	// ── Input ───────────────────────────────────────────────────────────

	/**
	 * Incoming KYC verification request from the API. Contains customer data to be
	 * screened and assessed.
	 */
	public record KycRequest(String customerId, String fullName, String dateOfBirth, String nationality,
			String occupation, String sourceOfFunds) {
	}

	// ── Intermediate ────────────────────────────────────────────────────

	/**
	 * Result of the initial customer screening. The LLM checks the customer data against
	 * known risk indicators: PEP status, sanctions, adverse media, jurisdiction risk.
	 */
	public record KycScreening(String fullName, String nationality, boolean pepMatch, boolean sanctionsMatch,
			boolean adverseMediaMatch, String screeningSummary) {
	}

	/**
	 * Additional due diligence notes for cases that need a human-oriented review path
	 * before final assessment.
	 */
	public record EnhancedDueDiligenceReview(String fullName, String reviewSummary, String recommendedControls,
			boolean complianceEscalationRequired) {
	}

	// ── Output ──────────────────────────────────────────────────────────

	/**
	 * Final risk assessment — the goal of the agent. Combines screening results with an
	 * overall risk score and recommendation.
	 */
	public record KycAssessment(String fullName, String riskLevel, String recommendation, String justification) {
	}

	// ── REST API types ──────────────────────────────────────────────────

	/**
	 * API-level request record with Bean Validation. Separate from the domain KycRequest
	 * to keep validation at the boundary.
	 */
	public record ApiKycRequest(@NotBlank String customerId, @NotBlank String fullName, @NotBlank String dateOfBirth,
			@NotBlank String nationality, String occupation, String sourceOfFunds) {
		public KycRequest toDomain() {
			return new KycRequest(customerId, fullName, dateOfBirth, nationality, occupation, sourceOfFunds);
		}
	}

}
