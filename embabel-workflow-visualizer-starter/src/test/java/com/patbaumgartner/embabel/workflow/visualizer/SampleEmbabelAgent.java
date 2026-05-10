package com.patbaumgartner.embabel.workflow.visualizer;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.annotation.Condition;

@Agent(name = "demo-agent", description = "Demo test agent")
public class SampleEmbabelAgent {

	@Action(description = "Create a draft plan")
	public String draftPlan() {
		return "ok";
	}

	@Condition(name = "hasInput")
	public boolean hasInput() {
		return true;
	}

	@AchievesGoal(description = "Marks goal completion")
	public String completeGoal() {
		return "done";
	}

}
