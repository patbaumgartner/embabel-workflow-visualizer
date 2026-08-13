package com.patbaumgartner.embabel.workflow.visualizer;

import com.embabel.agent.api.annotation.Action;

/** Interface contributing a default-method step to {@link InheritedStepsSampleAgent}. */
public interface AuditTrailSteps {

	@Action(description = "Contributed by an interface default method", readOnly = true)
	default String recordAuditTrail() {
		return "audited";
	}

}
