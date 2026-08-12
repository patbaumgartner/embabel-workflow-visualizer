package com.patbaumgartner.embabel.workflow.visualizer;

import java.util.List;

/**
 * Immutable data model exposed by the workflow visualizer.
 *
 * <p>
 * The catalog mirrors what the Embabel planner sees: agents, the steps declared on them
 * (actions / conditions / goal-achieving actions), the conditions an action requires
 * ({@code pre}) and produces ({@code post}), and the input / output domain types that
 * connect steps in a plan.
 */
public final class WorkflowModels {

	private WorkflowModels() {
	}

	/**
	 * Top-level response: every Embabel agent discovered in the application context.
	 */
	public record WorkflowCatalog(List<AgentWorkflow> agents) {
	}

	/**
	 * A single Embabel agent (or {@code @EmbabelComponent}) and its declared steps.
	 *
	 * @param agentName agent display name ({@code @Agent(name=)})
	 * @param description human-readable description
	 * @param version version string
	 * @param plannerType planner strategy: {@code GOAP}, {@code UTILITY}, {@code HYBRID},
	 * {@code SUPERVISOR}, or {@code COMPONENT} for {@code @EmbabelComponent} beans
	 * @param opaque {@code true} if the agent is opaque (internal steps not exposed to
	 * other agents' planners)
	 * @param className fully-qualified class name
	 * @param steps declared workflow steps
	 * @param provider provider string from {@code @Agent(provider = "...")}; {@code null}
	 * if not set
	 * @param beanName explicit Spring bean name from {@code @Agent(beanName = "...")};
	 * {@code null} if not set
	 * @param scan {@code true} when Embabel scans this type for actions — the annotation
	 * default; {@code false} when {@code scan = false} disables step discovery
	 * @param retryPolicy agent-wide retry policy from
	 * {@code @Agent(actionRetryPolicy = ...)}; {@code null} when left at
	 * {@code ActionRetryPolicy.DEFAULT}
	 * @param retryPolicyExpression agent-wide retry SpEL expression from
	 * {@code @Agent(actionRetryPolicyExpression = "...")}; {@code null} if not set
	 */
	public record AgentWorkflow(String agentName, String description, String version, String plannerType,
			boolean opaque, String className, List<WorkflowStep> steps, String provider, String beanName, boolean scan,
			String retryPolicy, String retryPolicyExpression) {

		/**
		 * Overload retaining the pre-1.5.0-coverage signature; new attributes default to
		 * unset.
		 */
		public AgentWorkflow(String agentName, String description, String version, String plannerType, boolean opaque,
				String className, List<WorkflowStep> steps, String provider) {
			this(agentName, description, version, plannerType, opaque, className, steps, provider, null, true, null,
					null);
		}
	}

	/**
	 * A single {@code key}/{@code value} pair declared via
	 * {@code @LlmTool(metadata = @LlmTool.Meta(key = "...", value = "..."))}.
	 */
	public record ToolMetadata(String key, String value) {
	}

	/**
	 * A step within an agent: an action, condition, goal, cost function, or LLM tool.
	 *
	 * @param name display name (annotation {@code name} or method name)
	 * @param type annotation simple name (e.g. {@code Action}, {@code Condition},
	 * {@code AchievesGoal}, {@code LlmTool})
	 * @param description annotation {@code description}, if any
	 * @param method underlying Java method name
	 * @param pre conditions required before this step can run
	 * @param post conditions produced after this step runs
	 * @param inputs input parameter type names (excluding framework types like
	 * {@code OperationContext})
	 * @param output return type simple name (or {@code "void"})
	 * @param goal {@code true} if this step also carries {@code @AchievesGoal}
	 * @param costMethod name of the {@code @Cost} method referenced via
	 * {@code @Action(costMethod = ...)}; {@code null} if not set
	 * @param valueMethod name of the {@code @Cost} method referenced via
	 * {@code @Action(valueMethod = ...)}; {@code null} if not set
	 * @param cost static cost declared via {@code @Action(cost = ...)}; {@code null} when
	 * left at the default ({@code 0.0})
	 * @param value static value declared via {@code @Action(value = ...)}; {@code null}
	 * when left at the default ({@code 0.0})
	 * @param goalValue goal value declared via {@code @AchievesGoal(value = ...)};
	 * {@code null} when left at the default ({@code 0.0})
	 * @param possibleOutputs when a method's declared return type is {@code Object}, this
	 * list holds the concrete types it may actually return (inferred from {@code @State}
	 * inner-record component types on the same agent class); {@code null} otherwise
	 * @param canRerun {@code true} when {@code @Action(canRerun = true)} — the planner
	 * may schedule this step more than once
	 * @param readOnly {@code true} when {@code @Action(readOnly = true)} — step does not
	 * mutate the blackboard
	 * @param outputBinding explicit blackboard binding name set via
	 * {@code @Action(outputBinding = "...")}; {@code null} if not set
	 * @param clearBlackboard {@code true} when {@code @Action(clearBlackboard = true)}
	 * @param tags tags from {@code @AchievesGoal(tags = {...})}
	 * @param examples example prompts from {@code @AchievesGoal(examples = {...})}
	 * @param llmTool {@code true} if the method carries {@code @LlmTool}, making it
	 * directly callable by the LLM
	 * @param llmToolDescription description from {@code @LlmTool(description = "...")}
	 * @param exportedRemote {@code true} when the goal is published as a remote MCP tool
	 * via {@code @AchievesGoal(export = @Export(remote = true, ...))}
	 * @param exportName explicit export name from {@code @Export(name = "...")};
	 * {@code null} if not set
	 * @param trigger simple type name of the event that triggers this action via
	 * {@code @Action(trigger = SomeEvent.class)}; {@code null} when the action is not
	 * event-triggered
	 * @param retryPolicy retry policy SpEL expression declared via
	 * {@code @Action(actionRetryPolicyExpression = "...")}; {@code null} if not set
	 * @param llmToolReturnDirect {@code true} when {@code @LlmTool(returnDirect = true)},
	 * so the tool result is returned directly without further LLM processing
	 * @param llmToolCategory category declared via {@code @LlmTool(category = "...")};
	 * {@code null} if not set
	 * @param actionRetryPolicy retry policy constant declared via
	 * {@code @Action(actionRetryPolicy = ...)} (e.g. {@code FIRE_ONCE}); {@code null}
	 * when left at {@code ActionRetryPolicy.DEFAULT}
	 * @param conditionCost evaluation cost declared via {@code @Condition(cost = ...)};
	 * {@code null} when left at the default ({@code 0.0})
	 * @param exportedLocal {@code true} when the goal is exposed to local callers via
	 * {@code @Export(local = true)} — the Embabel default
	 * @param exportStartingInputTypes simple type names declared via
	 * {@code @Export(startingInputTypes = {...})}, describing which inputs may start this
	 * exported goal
	 * @param llmToolName explicit tool name from {@code @LlmTool(name = "...")};
	 * {@code null} if not set
	 * @param llmToolMetadata key/value metadata declared via {@code @LlmTool(metadata =
	 * {@literal @}Meta(...))}
	 * @param providedInputs simple type names of parameters annotated {@code @Provided} —
	 * supplied by the platform rather than produced by another action
	 * @param nameMatchInputs parameters annotated {@code @RequireNameMatch}, rendered as
	 * {@code Type} or {@code Type:boundName} when an explicit binding name is given
	 */
	public record WorkflowStep(String name, String type, String description, String method, List<String> pre,
			List<String> post, List<String> inputs, String output, boolean goal, String costMethod, String valueMethod,
			Double cost, Double value, Double goalValue, List<String> possibleOutputs, boolean canRerun,
			boolean readOnly, String outputBinding, boolean clearBlackboard, List<String> tags, List<String> examples,
			boolean llmTool, String llmToolDescription, boolean exportedRemote, String exportName, String trigger,
			String retryPolicy, boolean llmToolReturnDirect, String llmToolCategory, String actionRetryPolicy,
			Double conditionCost, boolean exportedLocal, List<String> exportStartingInputTypes, String llmToolName,
			List<ToolMetadata> llmToolMetadata, List<String> providedInputs, List<String> nameMatchInputs) {

		/**
		 * Overload retaining the pre-1.5.0-coverage signature; new attributes default to
		 * unset.
		 */
		public WorkflowStep(String name, String type, String description, String method, List<String> pre,
				List<String> post, List<String> inputs, String output, boolean goal, String costMethod,
				String valueMethod, Double cost, Double value, Double goalValue, List<String> possibleOutputs,
				boolean canRerun, boolean readOnly, String outputBinding, boolean clearBlackboard, List<String> tags,
				List<String> examples, boolean llmTool, String llmToolDescription, boolean exportedRemote,
				String exportName, String trigger, String retryPolicy, boolean llmToolReturnDirect,
				String llmToolCategory) {
			this(name, type, description, method, pre, post, inputs, output, goal, costMethod, valueMethod, cost, value,
					goalValue, possibleOutputs, canRerun, readOnly, outputBinding, clearBlackboard, tags, examples,
					llmTool, llmToolDescription, exportedRemote, exportName, trigger, retryPolicy, llmToolReturnDirect,
					llmToolCategory, null, null, false, List.of(), null, List.of(), List.of(), List.of());
		}
	}

}
