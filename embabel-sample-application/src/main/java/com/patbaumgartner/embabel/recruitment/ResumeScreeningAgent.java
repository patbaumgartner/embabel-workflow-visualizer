package com.patbaumgartner.embabel.recruitment;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.common.OperationContext;
import com.patbaumgartner.embabel.recruitment.RecruitmentModels.CandidateRequest;
import com.patbaumgartner.embabel.recruitment.RecruitmentModels.CultureFitScore;
import com.patbaumgartner.embabel.recruitment.RecruitmentModels.HiringDecision;
import com.patbaumgartner.embabel.recruitment.RecruitmentModels.ResumeAnalysis;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resume Screening Agent — fan-in workflow: two independent analyses converge into a
 * single hiring decision.
 *
 * Demo pattern: fan-in - No @Condition annotations - analyzeResume and assessCultureFit
 * both start from CandidateRequest - They are independent — the planner can run them in
 * either order (or in parallel) - The single @AchievesGoal action requires BOTH results
 * before it can run
 *
 * Plan: CandidateRequest ──→ [analyzeResume] → ResumeAnalysis ──┐ └──→ [assessCultureFit]
 * → CultureFitScore ──┘ [makeHiringDecision] (@AchievesGoal) → HiringDecision
 */
@Agent(name = "ResumeScreeningAgent",
		description = "Screens candidate resumes against a job position and produces a hiring recommendation.",
		version = "1.0.0")
public class ResumeScreeningAgent {

	private static final Logger log = LoggerFactory.getLogger(ResumeScreeningAgent.class);

	/**
	 * Branch A: Analyse the resume for technical skills, experience and relevance.
	 * Independent of Branch B — no shared intermediate type dependency.
	 */
	@Action(description = "Analyse the candidate's resume for technical skills, years of experience and relevance to the role.")
	public ResumeAnalysis analyzeResume(CandidateRequest request, OperationContext context) {
		log.info("Analysing resume for: {} position={}", request.fullName(), request.position());

		var prompt = """
				You are a technical recruiter reviewing a resume.

				Candidate: %s
				Position: %s
				Department: %s
				Resume:
				%s

				Provide:
				1. coreSkills — up to 8 key skills identified
				2. yearsOfExperience — estimated years in relevant roles
				3. relevanceScore — 0 (no fit) to 100 (perfect fit)
				4. gaps — skills or experience the candidate is missing for this role
				5. analysisSummary — 2–3 sentence summary of the technical assessment
				""".formatted(request.fullName(), request.position(), request.department(), request.resumeText());

		return context.ai().withDefaultLlm().creating(ResumeAnalysis.class).fromPrompt(prompt);
	}

	/**
	 * Branch B: Assess culture fit from the cover letter and stated values. Independent
	 * of Branch A — only needs CandidateRequest.
	 */
	@Action(description = "Assess the candidate's culture fit and values alignment from the cover letter.")
	public CultureFitScore assessCultureFit(CandidateRequest request, OperationContext context) {
		log.info("Assessing culture fit for: {} position={}", request.fullName(), request.position());

		var prompt = """
				You are a culture-fit assessor reviewing a candidate's cover letter.

				Candidate: %s
				Position: %s
				Department: %s
				Cover Letter:
				%s

				Provide:
				1. fitScore — 0 (poor cultural fit) to 100 (excellent cultural fit)
				2. alignedValues — up to 5 values or behaviours that align well with a high-performing team
				3. concerns — up to 3 potential cultural concerns or red flags
				4. fitSummary — 2–3 sentence summary of the culture-fit assessment
				""".formatted(request.fullName(), request.position(), request.department(),
				request.coverLetter().isBlank() ? "(no cover letter provided)" : request.coverLetter());

		return context.ai().withDefaultLlm().creating(CultureFitScore.class).fromPrompt(prompt);
	}

	/**
	 * Fan-in step: synthesises both independent analyses into a single decision. Requires
	 * ResumeAnalysis AND CultureFitScore — the planner ensures both precede this action
	 * even though neither branch depended on the other.
	 */
	@AchievesGoal(description = "Produce a hiring recommendation for this candidate.")
	@Action(description = "Combine the resume analysis and culture-fit score to produce a final hiring recommendation.")
	public HiringDecision makeHiringDecision(ResumeAnalysis resume, CultureFitScore culture, CandidateRequest request,
			OperationContext context) {
		log.info("Making hiring decision for: {} relevance={} fitScore={}", resume.fullName(), resume.relevanceScore(),
				culture.fitScore());

		var prompt = """
				You are a hiring manager making a final recommendation.

				Candidate: %s
				Position: %s

				Technical Assessment:
				- Core skills: %s
				- Years of experience: %d
				- Relevance score: %d/100
				- Gaps: %s
				- Summary: %s

				Culture-Fit Assessment:
				- Fit score: %d/100
				- Aligned values: %s
				- Concerns: %s
				- Summary: %s

				Recommendation options: INTERVIEW, HOLD, REJECT
				Provide:
				1. recommendation
				2. feedback — 2–3 sentences of candidate feedback (suitable to share)
				3. nextSteps — brief description of the next action for the recruiter
				""".formatted(request.fullName(), request.position(), String.join(", ", resume.coreSkills()),
				resume.yearsOfExperience(), resume.relevanceScore(), String.join(", ", resume.gaps()),
				resume.analysisSummary(), culture.fitScore(), String.join(", ", culture.alignedValues()),
				String.join(", ", culture.concerns()), culture.fitSummary());

		return context.ai().withDefaultLlm().creating(HiringDecision.class).fromPrompt(prompt);
	}

}
