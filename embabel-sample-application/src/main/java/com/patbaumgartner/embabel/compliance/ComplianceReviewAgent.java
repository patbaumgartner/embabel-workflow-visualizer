package com.patbaumgartner.embabel.compliance;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.annotation.Condition;
import com.embabel.agent.api.annotation.Export;
import com.embabel.agent.api.annotation.LlmTool;
import com.embabel.agent.api.common.PlannerType;
import com.embabel.agent.core.ActionRetryPolicy;
import com.patbaumgartner.embabel.compliance.ComplianceModels.ClauseScreening;
import com.patbaumgartner.embabel.compliance.ComplianceModels.ComplianceVerdict;
import com.patbaumgartner.embabel.compliance.ComplianceModels.ReviewRequest;
import com.patbaumgartner.embabel.compliance.ComplianceModels.RiskAssessment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Compliance Review Agent — HYBRID planner with retry policies and a restricted MCP
 * export.
 *
 * <p>
 * <b>Features demonstrated:</b>
 * <ul>
 * <li>{@code planner = PlannerType.HYBRID} — combines goal-directed (GOAP) planning with
 * utility ranking, so the planner both works backwards from the goal and weighs the
 * relative value of the actions that can satisfy it.</li>
 * <li>{@code beanName = "complianceReviewer"} — registers the agent under an explicit
 * Spring bean name rather than the decapitalised class name.</li>
 * <li>{@code actionRetryPolicy} / {@code actionRetryPolicyExpression} at the
 * <em>agent</em> level — every action inherits this retry behaviour unless it declares
 * its own. The expression names a QoS key resolved under
 * {@code embabel.agent.platform.action-qos.*} in {@code application.properties}, not a
 * literal expression.</li>
 * <li>{@code @Action(actionRetryPolicy = FIRE_ONCE)} — {@link #assessRisk} overrides the
 * agent default so a failed risk assessment is never silently retried.</li>
 * <li>{@code @Condition(cost = ...)} — {@link #hasFlaggedClauses} declares an evaluation
 * cost, letting the planner prefer cheaper conditions when several could gate the same
 * action.</li>
 * <li>{@code @Export(remote = true, local = false, startingInputTypes = ...)} — the goal
 * is published as an MCP tool for remote callers but withheld from local in-process
 * callers, and declares which input type may start it.</li>
 * <li>{@code @LlmTool(name = ..., metadata = ...)} — an explicitly named tool carrying
 * key/value metadata for tool catalogues.</li>
 * </ul>
 */
@Agent(name = "ComplianceReviewAgent",
		description = "Reviews a contract document for regulatory compliance risk and issues a verdict.",
		version = "1.0.0", planner = PlannerType.HYBRID, beanName = "complianceReviewer",
		actionRetryPolicy = ActionRetryPolicy.DEFAULT, actionRetryPolicyExpression = "compliance-review")
public class ComplianceReviewAgent {

	private static final Logger log = LoggerFactory.getLogger(ComplianceReviewAgent.class);

	private static final String HAS_FLAGGED_CLAUSES = "hasFlaggedClauses";

	private static final String IS_CLEAN = "isClean";

	private static final List<String> RISK_TERMS = List.of("indemnity", "unlimited liability", "auto-renew",
			"exclusive", "non-compete");

	// ── Step 1: Screen clauses (pure Java, no LLM) ────────────────────────

	/**
	 * Declares both branch conditions in {@code post} so the planner knows this action
	 * establishes them and can plan through the branch that follows.
	 */
	@Action(description = "Extract and flag clauses containing high-risk contractual terms.", readOnly = true,
			cost = 0.1, post = { HAS_FLAGGED_CLAUSES, IS_CLEAN })
	public ClauseScreening screenClauses(ReviewRequest request) {
		log.info("Screening clauses for reviewId='{}' jurisdiction='{}'", request.reviewId(), request.jurisdiction());

		var text = request.documentText().toLowerCase(Locale.ROOT);
		var flagged = new ArrayList<String>();
		for (var term : RISK_TERMS) {
			if (text.contains(term)) {
				flagged.add(term);
			}
		}
		var clauseCount = request.documentText().split("\\.").length;
		return new ClauseScreening(request.reviewId(), List.copyOf(flagged), clauseCount);
	}

	/**
	 * Gates the deeper risk assessment. The declared {@code cost} tells the planner how
	 * expensive this condition is to evaluate relative to other conditions.
	 */
	@Condition(name = HAS_FLAGGED_CLAUSES, cost = 0.05)
	public boolean hasFlaggedClauses(ClauseScreening screening) {
		return !screening.flaggedClauses().isEmpty();
	}

	/**
	 * Complementary branch so a plan to the goal always exists, mirroring the two-branch
	 * pattern used by the KYC and moderation agents.
	 */
	@Condition(name = IS_CLEAN, cost = 0.05)
	public boolean isClean(ClauseScreening screening) {
		return screening.flaggedClauses().isEmpty();
	}

	// ── Step 2a: Assess risk for flagged documents (fire-once) ────────────

	/**
	 * Scores regulatory risk from the flagged clauses.
	 *
	 * <p>
	 * Declares {@code actionRetryPolicy = FIRE_ONCE} so that a failure here aborts the
	 * plan instead of being retried under the agent-wide retry policy — re-running a
	 * scoring step could otherwise double-count findings.
	 */
	@Action(description = "Score the regulatory risk implied by the flagged clauses.", pre = { HAS_FLAGGED_CLAUSES },
			actionRetryPolicy = ActionRetryPolicy.FIRE_ONCE)
	public RiskAssessment assessRisk(ClauseScreening screening) {
		log.info("Assessing risk for reviewId='{}' flagged={}", screening.reviewId(), screening.flaggedClauses());

		var riskScore = Math.min(1.0, screening.flaggedClauses().size() * 0.25);
		return new RiskAssessment(screening.reviewId(), riskScore, screening.flaggedClauses().get(0));
	}

	// ── Step 2b: Clean documents converge on the same type ────────────────

	@Action(description = "Record a nil risk assessment for documents with no flagged clauses.", pre = { IS_CLEAN },
			cost = 0.05)
	public RiskAssessment assessClean(ClauseScreening screening) {
		log.info("No flagged clauses for reviewId='{}'", screening.reviewId());
		return new RiskAssessment(screening.reviewId(), 0.0, "none");
	}

	// ── Step 3: Issue verdict (@AchievesGoal, MCP-exported) ───────────────

	@AchievesGoal(description = "Produce a compliance verdict for the reviewed document.", value = 0.95,
			tags = { "compliance", "risk", "legal" },
			examples = { "Review this vendor contract for EU compliance risk" },
			export = @Export(remote = true, name = "complianceVerdict", startingInputTypes = { ReviewRequest.class }))
	@Action(description = "Issue the final compliance verdict from the risk assessment.")
	public ComplianceVerdict issueVerdict(RiskAssessment assessment) {
		log.info("Issuing verdict for reviewId='{}' riskScore={}", assessment.reviewId(), assessment.riskScore());

		var decision = assessment.riskScore() >= 0.75 ? "ESCALATE"
				: assessment.riskScore() >= 0.25 ? "REMEDIATE" : "CLEAR";
		var rationale = "Primary concern '%s' scored %.2f.".formatted(assessment.primaryConcern(),
				assessment.riskScore());
		return new ComplianceVerdict(assessment.reviewId(), decision, assessment.riskScore(), rationale);
	}

	// ── LLM-callable tool ─────────────────────────────────────────────────

	@LlmTool(description = "Look up the retention period in years mandated for a given jurisdiction.",
			name = "retentionPeriodLookup", category = "compliance",
			metadata = { @LlmTool.Meta(key = "owner", value = "legal-ops"),
					@LlmTool.Meta(key = "stability", value = "stable") })
	public int retentionPeriodYears(String jurisdiction) {
		return switch (jurisdiction.toUpperCase(Locale.ROOT)) {
			case "EU", "DE", "CH" -> 10;
			case "US" -> 7;
			default -> 5;
		};
	}

}
