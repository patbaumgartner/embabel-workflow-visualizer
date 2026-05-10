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
 * Resume Screening Agent — fan-in workflow: two independent analyses converge
 * into a single hiring decision.
 *
 * Demo pattern: fan-in
 * - No @Condition annotations
 * - analyzeResume and assessCultureFit both start from CandidateRequest
 * - They are independent — the planner can run them in either order (or in
 * parallel)
 * - The single @AchievesGoal action requires BOTH results before it can run
 *
 * Plan: CandidateRequest ──→ [analyzeResume] → ResumeAnalysis ──┐
 * └──→ [assessCultureFit] → CultureFitScore ──┘
 * [makeHiringDecision] (@AchievesGoal)
 * → HiringDecision
 */
@Agent(name = "ResumeScreeningAgent", description = "Screens candidate resumes against a job position and produces a hiring recommendation.", version = "1.0.0")
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
     * Branch B: Assess culture fit from the cover letter and stated values.
     * Independent of Branch A — only needs CandidateRequest.
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
     * Fan-in step: synthesises both independent analyses into a single decision.
     * Requires ResumeAnalysis AND CultureFitScore — the planner ensures both
     * precede this action even though neither branch depended on the other.
     */
    @AchievesGoal(description = "Produce a hiring recommendation for this candidate.")
    @Action(description = "Combine the resume analysis and culture-fit score to produce a final hiring recommendation.")
    public HiringDecision makeHiringDecision(ResumeAnalysis resume, CultureFitScore culture,
            CandidateRequest request, OperationContext context) {
        log.info("Making hiring decision for: {} relevance={} fitScore={}",
                resume.fullName(), resume.relevanceScore(), culture.fitScore());

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
                """.formatted(request.fullName(), request.position(),
                String.join(", ", resume.coreSkills()), resume.yearsOfExperience(),
                resume.relevanceScore(), String.join(", ", resume.gaps()), resume.analysisSummary(),
                culture.fitScore(), String.join(", ", culture.alignedValues()),
                String.join(", ", culture.concerns()), culture.fitSummary());

        return context.ai().withDefaultLlm().creating(HiringDecision.class).fromPrompt(prompt);
    }

}

/**
 * Resume Screening Agent — evaluates job candidates and recommends a hiring
 * path.
 *
 * Plan: CandidateRequest → analyzeResume → ResumeAnalysis
 * → [QUALIFIES_FOR_INTERVIEW | DOES_NOT_QUALIFY] → makeDirectDecision →
 * HiringDecision
 * → [REQUIRES_DEEPER_REVIEW] → conductDeeperReview → DeeperReview
 * → makeReviewedDecision → HiringDecision
 */
@Agent(name = "ResumeScreeningAgent", description = "Screens candidate resumes against a job position and produces a hiring recommendation.", version = "1.0.0")
public class ResumeScreeningAgent {

    static final String DIRECT_DECISION = "directDecision";

    static final String REQUIRES_DEEPER_REVIEW = "requiresDeeperReview";

    private static final Logger log = LoggerFactory.getLogger(ResumeScreeningAgent.class);

    /**
     * Step 1: Extract skills, experience and compute a relevance score against the
     * position.
     */
    @Action(description = "Analyse a candidate's resume and compute a relevance score for the target position.", post = {
            DIRECT_DECISION, REQUIRES_DEEPER_REVIEW })
    public ResumeAnalysis analyzeResume(CandidateRequest request, OperationContext context) {
        log.info("Analysing resume for candidate: {} applying for: {}", request.fullName(), request.position());

        var prompt = """
                You are an experienced technical recruiter and HR specialist.
                Analyse the resume below for the target position and score the candidate.

                Position: %s
                Department: %s
                Candidate: %s

                Resume:
                %s

                Cover Letter:
                %s

                Provide:
                1. Core skills identified (list of short labels)
                2. Estimated years of relevant experience
                3. A relevance score from 0 to 100 (how well the candidate fits the position)
                4. Identified skill or experience gaps (list of short labels)
                5. A brief analysis summary
                """.formatted(request.position(), request.department(), request.fullName(), request.resumeText(),
                request.coverLetter().isBlank() ? "not provided" : request.coverLetter());

        return context.ai().withDefaultLlm().creating(ResumeAnalysis.class).fromPrompt(prompt);
    }

    /**
     * Strong candidates (score ≥ 70) or clear rejections (score < 40) can be
     * decided
     * without further review.
     */
    @Condition(name = DIRECT_DECISION)
    public boolean directDecision(ResumeAnalysis analysis) {
        return analysis.relevanceScore() >= 70 || analysis.relevanceScore() < 40;
    }

    /** Borderline candidates (40–69) need a deeper competency review. */
    @Condition(name = REQUIRES_DEEPER_REVIEW)
    public boolean requiresDeeperReview(ResumeAnalysis analysis) {
        return analysis.relevanceScore() >= 40 && analysis.relevanceScore() < 70;
    }

    /**
     * Step 2 (review branch): Perform a detailed competency assessment for
     * borderline
     * candidates.
     */
    @Action(description = "Conduct a deeper competency and culture-fit review for borderline candidates.", pre = {
            REQUIRES_DEEPER_REVIEW })
    public DeeperReview conductDeeperReview(ResumeAnalysis analysis, OperationContext context) {
        log.info("Conducting deeper review for: {} score={}", analysis.fullName(), analysis.relevanceScore());

        var prompt = """
                You are a senior hiring manager conducting a deeper review of a borderline candidate.

                Initial Analysis:
                - Candidate: %s
                - Core Skills: %s
                - Years of Experience: %d
                - Relevance Score: %d/100
                - Skill Gaps: %s
                - Summary: %s

                Provide:
                1. A competency assessment — do the identified skills transfer well despite gaps?
                2. Culture fit notes — does the profile suggest good teamwork, initiative, adaptability?
                3. Red flags (list of short labels, or empty)
                4. Strengths (list of short labels)
                5. Final recommendation: should this candidate be invited to interview? (true/false)
                """.formatted(analysis.fullName(), String.join(", ", analysis.coreSkills()),
                analysis.yearsOfExperience(), analysis.relevanceScore(), String.join(", ", analysis.gaps()),
                analysis.analysisSummary());

        return context.ai().withDefaultLlm().creating(DeeperReview.class).fromPrompt(prompt);
    }

    /**
     * Step 2a (direct branch): Issue a clear INTERVIEW or REJECT decision for
     * high/low-scoring candidates.
     */
    @AchievesGoal(description = "Produce a hiring recommendation for this candidate.")
    @Action(description = "Make a direct hiring decision for clearly qualified or clearly unqualified candidates.", pre = {
            DIRECT_DECISION })
    public HiringDecision makeDirectDecision(ResumeAnalysis analysis, OperationContext context) {
        log.info("Making direct hiring decision for: {} score={}", analysis.fullName(), analysis.relevanceScore());

        var prompt = """
                You are a hiring decision engine.
                Based on the resume analysis below, issue a hiring recommendation.

                Candidate: %s
                Core Skills: %s
                Years of Experience: %d
                Relevance Score: %d/100
                Skill Gaps: %s
                Summary: %s

                Recommendation options:
                - INTERVIEW (score ≥ 70)
                - REJECT (score < 40)

                Provide candidate-facing feedback (2–3 sentences) and clear next steps.
                """.formatted(analysis.fullName(), String.join(", ", analysis.coreSkills()),
                analysis.yearsOfExperience(), analysis.relevanceScore(), String.join(", ", analysis.gaps()),
                analysis.analysisSummary());

        return context.ai().withDefaultLlm().creating(HiringDecision.class).fromPrompt(prompt);
    }

    /**
     * Step 3 (review branch): Issue a nuanced decision after the deeper review.
     */
    @AchievesGoal(description = "Produce a hiring recommendation for this candidate.")
    @Action(description = "Make a hiring decision for a borderline candidate after a deeper competency review.", pre = {
            REQUIRES_DEEPER_REVIEW })
    public HiringDecision makeReviewedDecision(ResumeAnalysis analysis, DeeperReview review,
            OperationContext context) {
        log.info("Making reviewed hiring decision for: {} recommendForInterview={}", analysis.fullName(),
                review.recommendForInterview());

        var prompt = """
                You are a hiring decision engine reviewing a borderline candidate after deeper assessment.

                Initial Analysis:
                - Candidate: %s
                - Relevance Score: %d/100
                - Summary: %s

                Deeper Review:
                - Competency Assessment: %s
                - Culture Fit Notes: %s
                - Red Flags: %s
                - Strengths: %s
                - Recommend For Interview: %s

                Recommendation options: INTERVIEW, HOLD, REJECT
                Provide candidate-facing feedback (2–3 sentences) and clear next steps.
                """.formatted(analysis.fullName(), analysis.relevanceScore(), analysis.analysisSummary(),
                review.competencyAssessment(), review.cultureFitNotes(), String.join(", ", review.redFlags()),
                String.join(", ", review.strengths()), review.recommendForInterview());

        return context.ai().withDefaultLlm().creating(HiringDecision.class).fromPrompt(prompt);
    }

}
