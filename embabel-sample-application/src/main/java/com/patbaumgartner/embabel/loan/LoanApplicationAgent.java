package com.patbaumgartner.embabel.loan;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.annotation.Condition;
import com.embabel.agent.api.common.OperationContext;
import com.patbaumgartner.embabel.loan.LoanModels.CreditAnalysis;
import com.patbaumgartner.embabel.loan.LoanModels.LoanDecision;
import com.patbaumgartner.embabel.loan.LoanModels.LoanRequest;
import com.patbaumgartner.embabel.loan.LoanModels.UnderwritingAssessment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loan Application Agent — branching flow with static cost declarations.
 *
 * Demo pattern: branching + static cost= on @Action
 * - Two @Condition annotations split the flow at the credit analysis step
 * - Each @Action declares a static cost= so the planner can weigh alternatives
 * - Two @AchievesGoal actions — one per branch
 * - Shows how cost hints influence planner preferences without dynamic methods
 *
 * Plan: LoanRequest
 * → [analyzeCreditProfile] (cost=2.0) → CreditAnalysis
 * → [CAN_AUTO_DECIDE] → [makeAutoDecision] (cost=1.0) → LoanDecision
 * → [REQUIRES_UNDERWRITING] → [conductUnderwriting] (cost=5.0)
 * → UnderwritingAssessment
 * → [makeUnderwrittenDecision] (cost=2.0) → LoanDecision
 */
@Agent(name = "LoanApplicationAgent", description = "Assesses the credit risk of a loan application and produces an approve, reject or refer decision.", version = "1.0.0")
public class LoanApplicationAgent {

    static final String CAN_AUTO_DECIDE = "canAutoDecide";

    static final String REQUIRES_UNDERWRITING = "requiresUnderwriting";

    private static final Logger log = LoggerFactory.getLogger(LoanApplicationAgent.class);

    /**
     * Step 1: Analyse the applicant's credit profile — DTI ratio, affordability,
     * risk factors.
     */
    @Action(description = "Analyse the applicant's credit profile, compute a debt-to-income ratio and affordability score.", post = {
            CAN_AUTO_DECIDE, REQUIRES_UNDERWRITING }, cost = 2.0)
    public CreditAnalysis analyzeCreditProfile(LoanRequest request, OperationContext context) {
        log.info("Analysing credit profile for applicant: {} amount={} creditScore={}", request.fullName(),
                request.requestedAmount(), request.creditScore());

        var prompt = """
                You are a credit risk analyst at a retail bank.
                Analyse the loan application below and produce a credit risk assessment.

                Applicant: %s
                Requested Amount: %s
                Annual Income: %s
                Employment Status: %s
                Credit Score: %d
                Existing Monthly Debt: %s
                Loan Term: %d months
                Loan Purpose: %s

                Compute and provide:
                1. Debt-to-income ratio (monthly debt obligations including new loan repayment / monthly income)
                2. Affordability score from 0 (cannot afford) to 100 (very comfortable)
                3. Credit risk category: EXCELLENT, GOOD, FAIR, POOR, or VERY_POOR
                4. Key risk factors identified (list of short labels, e.g. high-dti, unstable-employment)
                5. A brief analysis summary
                """.formatted(request.fullName(), request.requestedAmount(), request.annualIncome(),
                request.employmentStatus(), request.creditScore(), request.existingMonthlyDebt(),
                request.loanTermMonths(), request.loanPurpose());

        return context.ai().withDefaultLlm().creating(CreditAnalysis.class).fromPrompt(prompt);
    }

    /**
     * EXCELLENT/GOOD credit with high affordability, or VERY_POOR/POOR with low
     * affordability, can be decided automatically.
     */
    @Condition(name = CAN_AUTO_DECIDE)
    public boolean canAutoDecide(CreditAnalysis analysis) {
        return analysis.creditRiskCategory().equals("EXCELLENT") || analysis.creditRiskCategory().equals("GOOD")
                || analysis.creditRiskCategory().equals("VERY_POOR")
                || (analysis.creditRiskCategory().equals("POOR") && analysis.affordabilityScore() < 30);
    }

    /**
     * FAIR credit or POOR credit with borderline affordability needs manual
     * underwriting.
     */
    @Condition(name = REQUIRES_UNDERWRITING)
    public boolean requiresUnderwriting(CreditAnalysis analysis) {
        return analysis.creditRiskCategory().equals("FAIR")
                || (analysis.creditRiskCategory().equals("POOR") && analysis.affordabilityScore() >= 30);
    }

    /**
     * Step 2 (underwriting branch): Conduct a deeper underwriting review for
     * borderline
     * cases.
     */
    @Action(description = "Conduct a manual underwriting assessment for borderline loan applications.", pre = {
            REQUIRES_UNDERWRITING }, cost = 5.0)
    public UnderwritingAssessment conductUnderwriting(CreditAnalysis analysis, OperationContext context) {
        log.info("Conducting underwriting for applicant: {} riskCategory={}", analysis.applicantId(),
                analysis.creditRiskCategory());

        var prompt = """
                You are a senior bank underwriter conducting a detailed review of a borderline loan application.

                Credit Analysis:
                - Applicant ID: %s
                - Debt-to-Income Ratio: %.2f
                - Affordability Score: %d/100
                - Credit Risk Category: %s
                - Risk Factors: %s
                - Summary: %s

                Provide:
                1. Employment stability notes — assess how stable and sustainable the income source is
                2. Repayment capacity notes — can the applicant realistically service this debt?
                3. Mitigating factors that support approval (list of short labels)
                4. Concerns that support rejection (list of short labels)
                5. Whether this application is approvable with conditions (true/false)
                """.formatted(analysis.applicantId(), analysis.debtToIncomeRatio(), analysis.affordabilityScore(),
                analysis.creditRiskCategory(), String.join(", ", analysis.riskFactors()),
                analysis.analysisSummary());

        return context.ai().withDefaultLlm().creating(UnderwritingAssessment.class).fromPrompt(prompt);
    }

    /**
     * Step 2a (auto branch): Issue an automatic lending decision for clear-cut
     * applications.
     */
    @AchievesGoal(description = "Produce a lending decision for this loan application.")
    @Action(description = "Make an automatic lending decision for clearly creditworthy or clearly unqualified applicants.", pre = {
            CAN_AUTO_DECIDE }, cost = 1.0)
    public LoanDecision makeAutoDecision(CreditAnalysis analysis, LoanRequest request, OperationContext context) {
        log.info("Auto-deciding loan for: {} riskCategory={}", analysis.applicantId(), analysis.creditRiskCategory());

        var prompt = """
                You are a loan decision engine.
                Issue a lending decision based on the credit analysis below.

                Applicant: %s
                Requested Amount: %s
                Loan Term: %d months

                Credit Analysis:
                - Affordability Score: %d/100
                - Credit Risk Category: %s
                - Risk Factors: %s
                - Summary: %s

                Decision options: APPROVED (EXCELLENT/GOOD), REJECTED (VERY_POOR or POOR with low affordability)
                For APPROVED decisions: suggest an approved amount (may be less than requested) and an interest rate.
                For REJECTED decisions: set approvedAmount and interestRate to null.
                List any conditions attached to the decision (empty list if none).
                Provide a clear justification.
                """.formatted(request.fullName(), request.requestedAmount(), request.loanTermMonths(),
                analysis.affordabilityScore(), analysis.creditRiskCategory(),
                String.join(", ", analysis.riskFactors()), analysis.analysisSummary());

        return context.ai().withDefaultLlm().creating(LoanDecision.class).fromPrompt(prompt);
    }

    /**
     * Step 3 (underwriting branch): Issue a nuanced decision following the
     * underwriting
     * review.
     */
    @AchievesGoal(description = "Produce a lending decision for this loan application.")
    @Action(description = "Make a lending decision for a borderline application after a full underwriting assessment.", pre = {
            REQUIRES_UNDERWRITING }, cost = 2.0)
    public LoanDecision makeUnderwrittenDecision(CreditAnalysis analysis, UnderwritingAssessment underwriting,
            LoanRequest request, OperationContext context) {
        log.info("Making underwritten decision for: {} approvableWithConditions={}",
                analysis.applicantId(), underwriting.approvableWithConditions());

        var prompt = """
                You are a loan decision engine issuing a ruling after full underwriting.

                Applicant: %s
                Requested Amount: %s
                Loan Term: %d months

                Credit Analysis:
                - Affordability Score: %d/100
                - Credit Risk Category: %s
                - Summary: %s

                Underwriting Assessment:
                - Employment Stability: %s
                - Repayment Capacity: %s
                - Mitigating Factors: %s
                - Concerns: %s
                - Approvable With Conditions: %s

                Decision options: APPROVED, REJECTED, REFERRED
                For APPROVED decisions: suggest an approved amount and interest rate (may differ from requested).
                For REJECTED/REFERRED: set approvedAmount and interestRate to null.
                List conditions if any.
                Provide a clear justification.
                """.formatted(request.fullName(), request.requestedAmount(), request.loanTermMonths(),
                analysis.affordabilityScore(), analysis.creditRiskCategory(), analysis.analysisSummary(),
                underwriting.employmentStabilityNotes(), underwriting.repaymentCapacityNotes(),
                String.join(", ", underwriting.mitigatingFactors()), String.join(", ", underwriting.concerns()),
                underwriting.approvableWithConditions());

        return context.ai().withDefaultLlm().creating(LoanDecision.class).fromPrompt(prompt);
    }

}
