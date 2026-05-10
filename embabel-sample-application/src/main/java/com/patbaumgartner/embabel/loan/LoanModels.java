package com.patbaumgartner.embabel.loan;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * Domain model for the Loan Application agent.
 *
 * Flow: LoanRequest → [analyzeCreditProfile] → CreditAnalysis →
 * [branch via conditions] → LoanDecision
 *
 * CAN_AUTO_DECIDE branch: credit score strongly good or bad → makeAutoDecision
 * REQUIRES_UNDERWRITING branch: borderline case → conductUnderwriting →
 * makeUnderwrittenDecision
 */
public class LoanModels {

    // ── Input ───────────────────────────────────────────────────────────

    public record LoanRequest(String applicantId, String fullName, BigDecimal requestedAmount, BigDecimal annualIncome,
            String employmentStatus, int creditScore, BigDecimal existingMonthlyDebt, int loanTermMonths,
            String loanPurpose) {
    }

    // ── Intermediate ────────────────────────────────────────────────────

    /**
     * Result of the automated credit profile analysis. debtToIncomeRatio: monthly
     * debt /
     * monthly income. affordabilityScore: 0–100 (higher = more affordable).
     */
    public record CreditAnalysis(String applicantId, double debtToIncomeRatio, int affordabilityScore,
            String creditRiskCategory, String[] riskFactors, String analysisSummary) {
    }

    /**
     * Result of a deeper underwriting review for borderline applications.
     */
    public record UnderwritingAssessment(String applicantId, String employmentStabilityNotes,
            String repaymentCapacityNotes, String[] mitigatingFactors, String[] concerns,
            boolean approvableWithConditions) {
    }

    // ── Output ──────────────────────────────────────────────────────────

    /**
     * Final loan decision. decision: APPROVED, REJECTED, or REFERRED.
     * approvedAmount and
     * interestRate are null when rejected.
     */
    public record LoanDecision(String applicantId, String fullName, String decision, BigDecimal approvedAmount,
            String interestRate, String[] conditions, String justification) {
    }

    // ── REST API types ───────────────────────────────────────────────────

    public record ApiLoanRequest(@NotBlank String applicantId, @NotBlank String fullName,
            @NotNull BigDecimal requestedAmount, @NotNull BigDecimal annualIncome, @NotBlank String employmentStatus,
            @Min(300) int creditScore, @NotNull BigDecimal existingMonthlyDebt, @Min(6) int loanTermMonths,
            String loanPurpose) {
        public LoanRequest toDomain() {
            return new LoanRequest(applicantId, fullName, requestedAmount, annualIncome, employmentStatus, creditScore,
                    existingMonthlyDebt, loanTermMonths, loanPurpose != null ? loanPurpose : "general");
        }
    }

}
