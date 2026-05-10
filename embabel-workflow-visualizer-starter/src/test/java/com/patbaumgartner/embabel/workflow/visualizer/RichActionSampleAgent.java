package com.patbaumgartner.embabel.workflow.visualizer;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.annotation.Cost;
import com.embabel.agent.api.annotation.LlmTool;

/**
 * Test fixture: an agent that exercises every optional attribute of the Embabel step
 * annotations.
 *
 * <p>
 * Covers:
 * <ul>
 * <li>{@code @Agent} with explicit {@code version} and {@code opaque = true}</li>
 * <li>{@code @Action} with {@code canRerun}, {@code readOnly}, {@code outputBinding},
 * {@code costMethod}, and {@code valueMethod}</li>
 * <li>{@code @Cost} methods referenced by the action</li>
 * <li>{@code @Action} + {@code @AchievesGoal} on the same method with {@code tags} and
 * {@code examples}</li>
 * <li>{@code @LlmTool} with a {@code description}</li>
 * </ul>
 */
@Agent(name = "rich-agent", description = "Rich annotation agent", version = "3.0.0", opaque = true)
public class RichActionSampleAgent {

	@Action(description = "An action with all optional attributes set", pre = { "precondition" },
			post = { "postcondition" }, canRerun = true, readOnly = true, outputBinding = "myOutput",
			costMethod = "calcCost", valueMethod = "calcValue")
	public String processData() {
		return "result";
	}

	@Cost(name = "calcCost")
	public double calcCost() {
		return 0.5;
	}

	@Cost(name = "calcValue")
	public double calcValue() {
		return 0.8;
	}

	@Action
	@AchievesGoal(description = "Rich goal", tags = { "tag1", "tag2" }, examples = { "example 1", "example 2" })
	public String achieveRichGoal() {
		return "done";
	}

	@LlmTool(description = "A helpful LLM tool")
	public String helpTool(String input) {
		return input;
	}

}
