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
 *
 * <p>
 * These records are serialized directly to JSON by the actuator endpoint and the REST
 * API, so their component names form a public contract. Build them through the supplied
 * builders rather than the canonical constructors — the step model carries too many
 * optional attributes for positional construction to stay readable or safe.
 */
public final class WorkflowModels {

	private WorkflowModels() {
	}

	/**
	 * Top-level response: every Embabel agent discovered in the application context.
	 */
	public record WorkflowCatalog(List<AgentWorkflow> agents) {

		public WorkflowCatalog {
			agents = copyOf(agents);
		}
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

		public AgentWorkflow {
			steps = copyOf(steps);
		}

		/**
		 * @deprecated since 1.1.0, retained for binary compatibility with 1.0.x. Use
		 * {@link #builder(String, String)}.
		 */
		@Deprecated(since = "1.1.0", forRemoval = true)
		public AgentWorkflow(String agentName, String description, String version, String plannerType, boolean opaque,
				String className, List<WorkflowStep> steps, String provider) {
			this(agentName, description, version, plannerType, opaque, className, steps, provider, null, true, null,
					null);
		}

		public static Builder builder(String agentName, String className) {
			return new Builder(agentName, className);
		}

		/** Fluent builder; every attribute other than name and class is optional. */
		public static final class Builder {

			private final String agentName;

			private final String className;

			private String description = "";

			private String version;

			private String plannerType;

			private boolean opaque;

			private List<WorkflowStep> steps = List.of();

			private String provider;

			private String beanName;

			private boolean scan = true;

			private String retryPolicy;

			private String retryPolicyExpression;

			private Builder(String agentName, String className) {
				this.agentName = agentName;
				this.className = className;
			}

			public Builder description(String description) {
				this.description = description;
				return this;
			}

			public Builder version(String version) {
				this.version = version;
				return this;
			}

			public Builder plannerType(String plannerType) {
				this.plannerType = plannerType;
				return this;
			}

			public Builder opaque(boolean opaque) {
				this.opaque = opaque;
				return this;
			}

			public Builder steps(List<WorkflowStep> steps) {
				this.steps = steps;
				return this;
			}

			public Builder provider(String provider) {
				this.provider = provider;
				return this;
			}

			public Builder beanName(String beanName) {
				this.beanName = beanName;
				return this;
			}

			public Builder scan(boolean scan) {
				this.scan = scan;
				return this;
			}

			public Builder retryPolicy(String retryPolicy) {
				this.retryPolicy = retryPolicy;
				return this;
			}

			public Builder retryPolicyExpression(String retryPolicyExpression) {
				this.retryPolicyExpression = retryPolicyExpression;
				return this;
			}

			public AgentWorkflow build() {
				return new AgentWorkflow(this.agentName, this.description, this.version, this.plannerType, this.opaque,
						this.className, this.steps, this.provider, this.beanName, this.scan, this.retryPolicy,
						this.retryPolicyExpression);
			}

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
	 * left at the default ({@code 0.0}, meaning "free")
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
	 * {@code @Action(outputBinding = "...")}; {@code null} when left at the framework
	 * default ({@code "it"})
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

		public WorkflowStep {
			pre = copyOf(pre);
			post = copyOf(post);
			inputs = copyOf(inputs);
			tags = copyOf(tags);
			examples = copyOf(examples);
			exportStartingInputTypes = copyOf(exportStartingInputTypes);
			llmToolMetadata = copyOf(llmToolMetadata);
			providedInputs = copyOf(providedInputs);
			nameMatchInputs = copyOf(nameMatchInputs);
			// possibleOutputs stays nullable: null means "the return type is exact"
			possibleOutputs = possibleOutputs == null ? null : List.copyOf(possibleOutputs);
		}

		/**
		 * @deprecated since 1.1.0, retained for binary compatibility with 1.0.x. Use
		 * {@link #builder(String, String, String)}, whose defaults also correct this
		 * overload's {@code exportedLocal = false}: Embabel's {@code @Export(local)}
		 * defaults to {@code true}.
		 */
		@Deprecated(since = "1.1.0", forRemoval = true)
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

		public static Builder builder(String name, String type, String method) {
			return new Builder(name, type, method);
		}

		/**
		 * Fluent builder for {@link WorkflowStep}.
		 *
		 * <p>
		 * Only the three attributes every step has — display name, annotation type, and
		 * backing method — are required. Everything else describes an optional annotation
		 * attribute and defaults to "not declared".
		 */
		public static final class Builder {

			private final String name;

			private final String type;

			private final String method;

			private String description = "";

			private List<String> pre = List.of();

			private List<String> post = List.of();

			private List<String> inputs = List.of();

			private String output = "void";

			private boolean goal;

			private String costMethod;

			private String valueMethod;

			private Double cost;

			private Double value;

			private Double goalValue;

			private List<String> possibleOutputs;

			private boolean canRerun;

			private boolean readOnly;

			private String outputBinding;

			private boolean clearBlackboard;

			private List<String> tags = List.of();

			private List<String> examples = List.of();

			private boolean llmTool;

			private String llmToolDescription;

			private boolean exportedRemote;

			private String exportName;

			private String trigger;

			private String retryPolicy;

			private boolean llmToolReturnDirect;

			private String llmToolCategory;

			private String actionRetryPolicy;

			private Double conditionCost;

			private boolean exportedLocal = true;

			private List<String> exportStartingInputTypes = List.of();

			private String llmToolName;

			private List<ToolMetadata> llmToolMetadata = List.of();

			private List<String> providedInputs = List.of();

			private List<String> nameMatchInputs = List.of();

			private Builder(String name, String type, String method) {
				this.name = name;
				this.type = type;
				this.method = method;
			}

			public Builder description(String description) {
				this.description = description;
				return this;
			}

			public Builder pre(List<String> pre) {
				this.pre = pre;
				return this;
			}

			public Builder post(List<String> post) {
				this.post = post;
				return this;
			}

			public Builder inputs(List<String> inputs) {
				this.inputs = inputs;
				return this;
			}

			public Builder output(String output) {
				this.output = output;
				return this;
			}

			public Builder goal(boolean goal) {
				this.goal = goal;
				return this;
			}

			public Builder costMethod(String costMethod) {
				this.costMethod = costMethod;
				return this;
			}

			public Builder valueMethod(String valueMethod) {
				this.valueMethod = valueMethod;
				return this;
			}

			public Builder cost(Double cost) {
				this.cost = cost;
				return this;
			}

			public Builder value(Double value) {
				this.value = value;
				return this;
			}

			public Builder goalValue(Double goalValue) {
				this.goalValue = goalValue;
				return this;
			}

			public Builder possibleOutputs(List<String> possibleOutputs) {
				this.possibleOutputs = possibleOutputs;
				return this;
			}

			public Builder canRerun(boolean canRerun) {
				this.canRerun = canRerun;
				return this;
			}

			public Builder readOnly(boolean readOnly) {
				this.readOnly = readOnly;
				return this;
			}

			public Builder outputBinding(String outputBinding) {
				this.outputBinding = outputBinding;
				return this;
			}

			public Builder clearBlackboard(boolean clearBlackboard) {
				this.clearBlackboard = clearBlackboard;
				return this;
			}

			public Builder tags(List<String> tags) {
				this.tags = tags;
				return this;
			}

			public Builder examples(List<String> examples) {
				this.examples = examples;
				return this;
			}

			public Builder llmTool(boolean llmTool) {
				this.llmTool = llmTool;
				return this;
			}

			public Builder llmToolDescription(String llmToolDescription) {
				this.llmToolDescription = llmToolDescription;
				return this;
			}

			public Builder exportedRemote(boolean exportedRemote) {
				this.exportedRemote = exportedRemote;
				return this;
			}

			public Builder exportName(String exportName) {
				this.exportName = exportName;
				return this;
			}

			public Builder trigger(String trigger) {
				this.trigger = trigger;
				return this;
			}

			public Builder retryPolicy(String retryPolicy) {
				this.retryPolicy = retryPolicy;
				return this;
			}

			public Builder llmToolReturnDirect(boolean llmToolReturnDirect) {
				this.llmToolReturnDirect = llmToolReturnDirect;
				return this;
			}

			public Builder llmToolCategory(String llmToolCategory) {
				this.llmToolCategory = llmToolCategory;
				return this;
			}

			public Builder actionRetryPolicy(String actionRetryPolicy) {
				this.actionRetryPolicy = actionRetryPolicy;
				return this;
			}

			public Builder conditionCost(Double conditionCost) {
				this.conditionCost = conditionCost;
				return this;
			}

			public Builder exportedLocal(boolean exportedLocal) {
				this.exportedLocal = exportedLocal;
				return this;
			}

			public Builder exportStartingInputTypes(List<String> exportStartingInputTypes) {
				this.exportStartingInputTypes = exportStartingInputTypes;
				return this;
			}

			public Builder llmToolName(String llmToolName) {
				this.llmToolName = llmToolName;
				return this;
			}

			public Builder llmToolMetadata(List<ToolMetadata> llmToolMetadata) {
				this.llmToolMetadata = llmToolMetadata;
				return this;
			}

			public Builder providedInputs(List<String> providedInputs) {
				this.providedInputs = providedInputs;
				return this;
			}

			public Builder nameMatchInputs(List<String> nameMatchInputs) {
				this.nameMatchInputs = nameMatchInputs;
				return this;
			}

			public WorkflowStep build() {
				return new WorkflowStep(this.name, this.type, this.description, this.method, this.pre, this.post,
						this.inputs, this.output, this.goal, this.costMethod, this.valueMethod, this.cost, this.value,
						this.goalValue, this.possibleOutputs, this.canRerun, this.readOnly, this.outputBinding,
						this.clearBlackboard, this.tags, this.examples, this.llmTool, this.llmToolDescription,
						this.exportedRemote, this.exportName, this.trigger, this.retryPolicy, this.llmToolReturnDirect,
						this.llmToolCategory, this.actionRetryPolicy, this.conditionCost, this.exportedLocal,
						this.exportStartingInputTypes, this.llmToolName, this.llmToolMetadata, this.providedInputs,
						this.nameMatchInputs);
			}

		}
	}

	private static <T> List<T> copyOf(List<T> values) {
		return values == null ? List.of() : List.copyOf(values);
	}

}
