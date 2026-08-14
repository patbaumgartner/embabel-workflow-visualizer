package com.patbaumgartner.embabel.workflow.visualizer;

import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;

/**
 * Takes its agent name from its class, so a runtime agent named after this class matches
 * it both exactly and by simple name.
 *
 * <p>
 * {@link AliasedNamesakeAgent} answers to the same name without being the same class,
 * which is the collision {@code RuntimeWorkflowReconciler} has to resolve in this one's
 * favour.
 */
@Agent(description = "Agent whose name comes from its class")
public class Namesake {

	@Action(description = "Does the work")
	public String work() {
		return "done";
	}

}
