package com.patbaumgartner.embabel.workflow.visualizer;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.annotation.Condition;

/**
 * Test fixture: agent with multiple {@code @AchievesGoal} methods (different
 * preconditions reach the same goal type), conditions, and a step bearing both
 * {@code @Action} and {@code @AchievesGoal} on a single method.
 */
@Agent(name = "MultiGoalAgent", description = "Agent with multiple goal paths and conditions", version = "2.0.0")
public class MultiGoalSampleAgent {

	public static final String FAST_PATH = "fastPath";

	public static final String SLOW_PATH = "slowPath";

	@Action(description = "Inspect the request to choose a path", post = { FAST_PATH, SLOW_PATH })
	public Inspection inspect(Request request) {
		return new Inspection();
	}

	@Condition(name = "isFastPath")
	public boolean isFastPath() {
		return true;
	}

	@Action(pre = { FAST_PATH }, description = "Run the fast path")
	@AchievesGoal(description = "Fast path completion")
	public Result completeFast(Inspection inspection) {
		return new Result();
	}

	@Action(pre = { SLOW_PATH }, description = "Run the slow path")
	@AchievesGoal(description = "Slow path completion")
	public Result completeSlow(Inspection inspection) {
		return new Result();
	}

	public record Request() {
	}

	public record Inspection() {
	}

	public record Result() {
	}

}
