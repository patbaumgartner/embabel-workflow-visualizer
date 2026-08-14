package com.patbaumgartner.embabel.workflow.visualizer;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.annotation.Condition;
import com.embabel.agent.api.annotation.Export;
import com.embabel.agent.api.annotation.LlmTool;
import com.embabel.agent.api.annotation.Provided;
import com.embabel.agent.api.annotation.RequireNameMatch;
import com.embabel.agent.api.common.PlannerType;
import com.embabel.agent.core.ActionRetryPolicy;

/**
 * Test fixture covering the Embabel annotation attributes that carry no dedicated
 * assertion in {@link RichActionSampleAgent}.
 *
 * <p>
 * Covers the {@code HYBRID} planner, agent-level {@code beanName} and retry policy,
 * {@code @Action(actionRetryPolicy)}, {@code @Condition(cost)}, {@code @Export} with
 * {@code local = false} and {@code startingInputTypes}, {@code @LlmTool} {@code name} and
 * {@code metadata}, and the {@code @Provided} / {@code @RequireNameMatch} parameter
 * annotations.
 */
@Agent(name = "full-coverage-agent", description = "Agent exercising the remaining annotation attributes",
		version = "4.0.0", planner = PlannerType.HYBRID, beanName = "fullCoverageBean",
		actionRetryPolicy = ActionRetryPolicy.FIRE_ONCE, actionRetryPolicyExpression = "agentMaxAttempts=2")
public class FullCoverageSampleAgent {

	public record Draft(String text) {
	}

	@Condition(name = "isReady", cost = 0.25)
	public boolean isReady() {
		return true;
	}

	@Action(description = "Action pinned to a single attempt", actionRetryPolicy = ActionRetryPolicy.FIRE_ONCE)
	public String fireOnce() {
		return "once";
	}

	@Action(description = "Action with platform-provided and name-bound parameters")
	public String bindParameters(@Provided Draft draft, @RequireNameMatch("editorNotes") String notes) {
		return draft.text() + notes;
	}

	@Action
	@AchievesGoal(description = "Goal exported to MCP but hidden from local callers", export = @Export(remote = true,
			local = false, name = "hiddenLocally", startingInputTypes = { Draft.class }))
	public String achieveExportedGoal() {
		return "exported";
	}

	@LlmTool(description = "Tool carrying an explicit name and metadata", name = "explicitToolName", metadata = {
			@LlmTool.Meta(key = "owner", value = "platform"), @LlmTool.Meta(key = "stability", value = "beta") })
	public String annotatedTool(String input) {
		return input;
	}

	/**
	 * Two step annotations and no {@code @Action} to break the tie, which is the case
	 * where the reported type used to depend on the order reflection happened to hand the
	 * annotations back in.
	 */
	@AchievesGoal(description = "Goal that is also callable as a tool")
	@LlmTool(description = "Tool description that must not win")
	public String goalReachableAsATool() {
		return "both";
	}

}
