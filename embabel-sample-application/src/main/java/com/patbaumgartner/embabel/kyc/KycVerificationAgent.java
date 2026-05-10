package com.patbaumgartner.embabel.kyc;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.annotation.Condition;
import com.embabel.agent.api.common.OperationContext;
import com.patbaumgartner.embabel.kyc.KycModels.EnhancedDueDiligenceReview;
import com.patbaumgartner.embabel.kyc.KycModels.KycAssessment;
import com.patbaumgartner.embabel.kyc.KycModels.KycRequest;
import com.patbaumgartner.embabel.kyc.KycModels.KycScreening;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * KYC Verification Agent — condition-driven planning with Embabel.
 *
 * Plan: KycRequest → screenCustomer → KycScreening → assessDirectRisk → KycAssessment OR
 * → collectEnhancedDueDiligence → EnhancedDueDiligenceReview →
 * assessRiskWithEnhancedDueDiligence → KycAssessment
 *
 * Key demo points: - Prompt minimization keeps internal IDs out of LLM context - Domain
 * objects ARE the routing mechanism — the planner connects them automatically -
 *
 * @Condition gates the enhanced due diligence branch - All actions use the LLM — this is
 * a "think → decide" pattern
 */
@Agent(name = "KycVerificationAgent",
		description = "Screens a customer against risk indicators and produces a risk assessment.", version = "1.0.0")
public class KycVerificationAgent {

	static final String REQUIRES_ENHANCED_DUE_DILIGENCE = "requiresEnhancedDueDiligence";
	static final String CAN_ASSESS_DIRECTLY = "canAssessDirectly";

	private static final Logger log = LoggerFactory.getLogger(KycVerificationAgent.class);

	/**
	 * Step 1: Screen the customer against PEP lists, sanctions, and adverse media.
	 *
	 * The prompt deliberately excludes customerId. The LLM sees business data, but not
	 * the database key.
	 */
	@Action(description = "Screen customer data against PEP lists, sanctions databases, and adverse media.",
			post = { REQUIRES_ENHANCED_DUE_DILIGENCE, CAN_ASSESS_DIRECTLY })
	public KycScreening screenCustomer(KycRequest request, OperationContext context) {
		log.info("Screening customer: {}", request.fullName());

		var prompt = """
				You are a KYC compliance screening system.
				Analyze the following customer data and check against known risk indicators.

				Customer:
				- Full Name: %s
				- Date of Birth: %s
				- Nationality: %s
				- Occupation: %s
				- Source of Funds: %s

				Determine:
				1. Whether the name matches any known Politically Exposed Persons (PEP)
				2. Whether the name or nationality triggers sanctions screening alerts
				3. Whether there is adverse media associated with this person

				Provide a screening summary explaining your findings.
				""".formatted(request.fullName(), request.dateOfBirth(), request.nationality(),
				request.occupation() != null ? request.occupation() : "not provided",
				request.sourceOfFunds() != null ? request.sourceOfFunds() : "not provided");

		return context.ai().withDefaultLlm().creating(KycScreening.class).fromPrompt(prompt);
	}

	/**
	 * Branch condition: PEP and adverse media cases require additional due diligence
	 * unless a sanctions hit already makes the outcome clear.
	 */
	@Condition(name = REQUIRES_ENHANCED_DUE_DILIGENCE)
	public boolean requiresEnhancedDueDiligence(KycScreening screening) {
		return !screening.sanctionsMatch() && (screening.pepMatch() || screening.adverseMediaMatch());
	}

	/**
	 * Branch condition: sanction hits and clean cases can be assessed directly.
	 */
	@Condition(name = CAN_ASSESS_DIRECTLY)
	public boolean canAssessDirectly(KycScreening screening) {
		return screening.sanctionsMatch() || (!screening.pepMatch() && !screening.adverseMediaMatch());
	}

	/**
	 * Step 2a: Collect extra due diligence notes for non-trivial cases.
	 */
	@Action(description = "Collect enhanced due diligence notes for PEP or adverse-media cases.",
			pre = { REQUIRES_ENHANCED_DUE_DILIGENCE })
	public EnhancedDueDiligenceReview collectEnhancedDueDiligence(KycScreening screening, OperationContext context) {
		log.info("Collecting enhanced due diligence for: {}", screening.fullName());

		var prompt = """
				You are a senior KYC compliance analyst performing enhanced due diligence.
				Review the screening result below and produce additional review notes.

				Screening Results:
				- Full Name: %s
				- Nationality: %s
				- PEP Match: %s
				- Sanctions Match: %s
				- Adverse Media Match: %s
				- Screening Summary: %s

				Provide:
				1. A concise enhanced due diligence summary
				2. Recommended controls or follow-up checks
				3. Whether manual compliance escalation is required
				""".formatted(screening.fullName(), screening.nationality(), screening.pepMatch(),
				screening.sanctionsMatch(), screening.adverseMediaMatch(), screening.screeningSummary());

		return context.ai().withDefaultLlm().creating(EnhancedDueDiligenceReview.class).fromPrompt(prompt);
	}

	/**
	 * Step 2b: Assess overall risk for direct cases.
	 */
	@AchievesGoal(description = "Produce a risk assessment for a KYC verification request.")
	@Action(description = "Assess overall KYC risk for direct cases and produce a recommendation.",
			pre = { CAN_ASSESS_DIRECTLY })
	public KycAssessment assessDirectRisk(KycScreening screening, OperationContext context) {
		log.info("Assessing direct risk for: {} (PEP={}, Sanctions={}, AdverseMedia={})", screening.fullName(),
				screening.pepMatch(), screening.sanctionsMatch(), screening.adverseMediaMatch());

		var prompt = """
				You are a KYC risk assessment engine for clear-cut cases.
				Based on the screening results below, determine the overall risk level and recommendation.

				Screening Results:
				- Full Name: %s
				- Nationality: %s
				- PEP Match: %s
				- Sanctions Match: %s
				- Adverse Media Match: %s
				- Screening Summary: %s

				Risk levels: LOW, MEDIUM, HIGH, CRITICAL
				Recommendations: APPROVE, ENHANCED_DUE_DILIGENCE, REJECT, ESCALATE_TO_COMPLIANCE

				Rules:
				- Sanctions match → CRITICAL risk, REJECT
				- PEP match → HIGH risk, ENHANCED_DUE_DILIGENCE
				- Adverse media only → MEDIUM risk, ENHANCED_DUE_DILIGENCE
				- No flags → LOW risk, APPROVE

				Provide a clear justification for your decision.
				""".formatted(screening.fullName(), screening.nationality(), screening.pepMatch(),
				screening.sanctionsMatch(), screening.adverseMediaMatch(), screening.screeningSummary());

		return context.ai().withDefaultLlm().creating(KycAssessment.class).fromPrompt(prompt);
	}

	/**
	 * Step 3: Assess final risk after enhanced due diligence has produced extra context.
	 */
	@AchievesGoal(description = "Produce a risk assessment for a KYC verification request.")
	@Action(description = "Assess overall KYC risk after enhanced due diligence.",
			pre = { REQUIRES_ENHANCED_DUE_DILIGENCE })
	public KycAssessment assessRiskWithEnhancedDueDiligence(KycScreening screening, EnhancedDueDiligenceReview review,
			OperationContext context) {
		log.info("Assessing post-review risk for: {} (EscalationRequired={})", screening.fullName(),
				review.complianceEscalationRequired());

		var prompt = """
				You are a KYC risk assessment engine reviewing a case after enhanced due diligence.
				Determine the final risk level and recommendation.

				Screening Results:
				- Full Name: %s
				- Nationality: %s
				- PEP Match: %s
				- Sanctions Match: %s
				- Adverse Media Match: %s
				- Screening Summary: %s

				Enhanced Due Diligence:
				- Review Summary: %s
				- Recommended Controls: %s
				- Compliance Escalation Required: %s

				Risk levels: LOW, MEDIUM, HIGH, CRITICAL
				Recommendations: APPROVE, ENHANCED_DUE_DILIGENCE, REJECT, ESCALATE_TO_COMPLIANCE

				Rules:
				- Compliance escalation required → HIGH risk, ESCALATE_TO_COMPLIANCE
				- PEP match → HIGH risk, ENHANCED_DUE_DILIGENCE unless escalation is required
				- Adverse media only → MEDIUM risk, ENHANCED_DUE_DILIGENCE
				- Sanctions match should never reach this path; if present, return CRITICAL risk, REJECT

				Provide a clear justification for your decision.
				""".formatted(screening.fullName(), screening.nationality(), screening.pepMatch(),
				screening.sanctionsMatch(), screening.adverseMediaMatch(), screening.screeningSummary(),
				review.reviewSummary(), review.recommendedControls(), review.complianceEscalationRequired());

		return context.ai().withDefaultLlm().creating(KycAssessment.class).fromPrompt(prompt);
	}

}
