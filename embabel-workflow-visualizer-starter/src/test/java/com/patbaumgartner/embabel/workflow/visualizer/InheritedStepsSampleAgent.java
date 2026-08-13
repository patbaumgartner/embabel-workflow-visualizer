package com.patbaumgartner.embabel.workflow.visualizer;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;

/**
 * Test fixture: an agent that inherits steps rather than declaring them all itself.
 *
 * <p>
 * Embabel's {@code AgentMetadataReader} registers annotated methods found anywhere in the
 * type hierarchy (superclasses and interface default methods), so the catalog has to
 * report them too — otherwise the visualizer shows a smaller workflow than the planner
 * actually executes.
 */
@Agent(name = "inherited-agent", description = "Agent whose steps come from a base class and an interface")
public class InheritedStepsSampleAgent extends AbstractReviewAgent implements AuditTrailSteps {

	@Action(description = "Declared directly on the agent")
	@AchievesGoal(description = "Review complete")
	public String finishReview() {
		return "done";
	}

	/**
	 * Overrides an inherited action <em>without</em> repeating the annotation. Embabel
	 * still registers the step (the declaration is on the supertype) and invokes this
	 * override, so the catalog must report it exactly once.
	 */
	@Override
	public String prepareReview() {
		return "prepared by the subclass";
	}

}
