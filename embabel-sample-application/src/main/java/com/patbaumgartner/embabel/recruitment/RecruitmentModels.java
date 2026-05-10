package com.patbaumgartner.embabel.recruitment;

import jakarta.validation.constraints.NotBlank;

/**
 * Domain model for the Resume Screening agent.
 *
 * Flow: CandidateRequest ──→ [analyzeResume] → ResumeAnalysis ──┐ └──→ [assessCultureFit]
 * → CultureFitScore ──┘ → [makeHiringDecision] (@AchievesGoal) → HiringDecision
 *
 * Demo pattern: fan-in — two independent analysis steps both start from the same input,
 * run independently, and converge into a single @AchievesGoal action. No @Condition
 * annotations needed.
 */
public class RecruitmentModels {

	// ── Input ───────────────────────────────────────────────────────────

	public record CandidateRequest(String candidateId, String fullName, String position, String department,
			String resumeText, String coverLetter) {
	}

	// ── Intermediate (independent — fan-out) ─────────────────────────────

	/**
	 * Technical resume analysis: skills, experience, relevance. Produced independently of
	 * CultureFitScore.
	 */
	public record ResumeAnalysis(String candidateId, String fullName, String[] coreSkills, int yearsOfExperience,
			int relevanceScore, String[] gaps, String analysisSummary) {
	}

	/**
	 * Culture-fit assessment derived directly from the cover letter and stated values.
	 * Produced independently of ResumeAnalysis.
	 */
	public record CultureFitScore(String candidateId, int fitScore, String[] alignedValues, String[] concerns,
			String fitSummary) {
	}

	// ── Output (fan-in) ──────────────────────────────────────────────────

	/**
	 * Final hiring decision. recommendation: INTERVIEW, HOLD, or REJECT. Synthesises both
	 * ResumeAnalysis and CultureFitScore.
	 */
	public record HiringDecision(String candidateId, String fullName, String recommendation, String feedback,
			String nextSteps) {
	}

	// ── REST API types ───────────────────────────────────────────────────

	public record ApiCandidateRequest(@NotBlank String candidateId, @NotBlank String fullName,
			@NotBlank String position, String department, @NotBlank String resumeText, String coverLetter) {
		public CandidateRequest toDomain() {
			return new CandidateRequest(candidateId, fullName, position,
					department != null ? department : "unspecified", resumeText,
					coverLetter != null ? coverLetter : "");
		}
	}

}
