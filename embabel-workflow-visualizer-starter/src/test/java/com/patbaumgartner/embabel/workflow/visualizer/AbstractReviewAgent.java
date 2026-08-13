package com.patbaumgartner.embabel.workflow.visualizer;

import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Condition;
import com.embabel.agent.api.annotation.Cost;

/**
 * Shared base class contributing inherited steps to {@link InheritedStepsSampleAgent}.
 */
public abstract class AbstractReviewAgent {

	@Action(description = "Inherited from the base class", costMethod = "inheritedCost")
	public String prepareReview() {
		return "prepared";
	}

	@Condition(name = "inheritedReady", cost = 0.125)
	public boolean inheritedReady() {
		return true;
	}

	@Cost(name = "inheritedCost")
	public double inheritedCost() {
		return 0.3;
	}

}
