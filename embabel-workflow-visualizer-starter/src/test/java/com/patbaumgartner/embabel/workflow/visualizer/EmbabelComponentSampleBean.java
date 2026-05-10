package com.patbaumgartner.embabel.workflow.visualizer;

import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.EmbabelComponent;

/**
 * Test fixture: an {@code @EmbabelComponent} bean (not a full {@code @Agent}).
 *
 * <p>
 * Expected catalog behavior:
 * <ul>
 * <li>{@code plannerType} = {@code "COMPONENT"}</li>
 * <li>{@code agentName} falls back to the simple class name</li>
 * <li>{@code opaque} = {@code false} (not applicable for components)</li>
 * </ul>
 */
@EmbabelComponent
public class EmbabelComponentSampleBean {

	@Action(description = "Component action")
	public String doWork() {
		return "done";
	}

}
