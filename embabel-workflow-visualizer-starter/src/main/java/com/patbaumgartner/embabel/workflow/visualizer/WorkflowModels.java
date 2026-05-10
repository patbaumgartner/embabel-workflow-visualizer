package com.patbaumgartner.embabel.workflow.visualizer;

import java.util.List;

/**
 * Immutable data model exposed by the workflow visualizer.
 *
 * <p>
 * The catalog mirrors what the Embabel planner sees: agents, the steps declared
 * on them
 * (actions / conditions / goal-achieving actions), the conditions an action
 * requires
 * ({@code pre}) and produces ({@code post}), and the input / output domain
 * types that
 * connect steps in a plan.
 */
public final class WorkflowModels {

	private WorkflowModels() {
	}

	/**
	 * Top-level response: every Embabel agent discovered in the application
	 * context.
	 */
	public record WorkflowCatalog(List<AgentWorkflow> agents) {
	}

	/** A single Embabel agent and its declared steps. */
	public record AgentWorkflow(String agentName, String description, String version, String className,
			List<WorkflowStep> steps) {
	}

	/**
	 * A step within an agent: an action, condition, goal, or cost function.
	 *
	 * @param name        display name (annotation {@code name} or method name)
	 * @param type        annotation simple name (e.g. {@code Action},
	 *                    {@code Condition},
	 *                    {@code AchievesGoal})
	 * @param description annotation {@code description}, if any
	 * @param method      underlying Java method name
	 * @param pre         conditions required before this step can run
	 * @param post        conditions produced after this step runs
	 * @param inputs      input parameter type names (excluding framework types like
	 *                    {@code OperationContext})
	 * @param output      return type simple name (or {@code "void"})
	 * @param goal        {@code true} if this step also carries
	 *                    {@code @AchievesGoal}
	 */
	public record WorkflowStep(String name, String type, String description, String method, List<String> pre,
			List<String> post, List<String> inputs, String output, boolean goal) {
	}

}
