package com.patbaumgartner.embabel.workflow.visualizer;

import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;

/**
 * Declares the same agent name as {@link Namesake} while being a different class, so a
 * runtime agent named after {@code Namesake} matches this one only by simple name.
 */
@Agent(name = "Namesake", description = "Different class answering to the same agent name")
public class AliasedNamesakeAgent {

	@Action(description = "Does other work")
	public String otherWork() {
		return "done";
	}

}
