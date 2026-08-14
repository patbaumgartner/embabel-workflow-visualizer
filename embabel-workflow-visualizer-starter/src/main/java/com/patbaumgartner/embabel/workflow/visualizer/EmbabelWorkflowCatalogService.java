package com.patbaumgartner.embabel.workflow.visualizer;

import com.patbaumgartner.embabel.workflow.visualizer.AgentPlatformReader.RuntimeAgent;
import com.patbaumgartner.embabel.workflow.visualizer.AgentPlatformReader.RuntimeStep;
import com.patbaumgartner.embabel.workflow.visualizer.WorkflowModels.AgentWorkflow;
import com.patbaumgartner.embabel.workflow.visualizer.WorkflowModels.ToolMetadata;
import com.patbaumgartner.embabel.workflow.visualizer.WorkflowModels.WorkflowCatalog;
import com.patbaumgartner.embabel.workflow.visualizer.WorkflowModels.WorkflowStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Discovers Embabel agents in the Spring {@link ApplicationContext} and produces a
 * {@link WorkflowCatalog} describing their workflow.
 *
 * <p>
 * Discovery is purely reflective and never imports Embabel types directly so that the
 * starter remains usable without forcing the {@code embabel-agent-api} on the runtime
 * classpath.
 * </p>
 */
public class EmbabelWorkflowCatalogService {

	private static final Logger log = LoggerFactory.getLogger(EmbabelWorkflowCatalogService.class);

	private static final String AGENT_ANNOTATION_FQN = "com.embabel.agent.api.annotation.Agent";

	private static final String EMBABEL_COMPONENT_ANNOTATION_FQN = "com.embabel.agent.api.annotation.EmbabelComponent";

	private static final String ACTION_ANNOTATION_FQN = "com.embabel.agent.api.annotation.Action";

	private static final String ACHIEVES_GOAL_ANNOTATION_FQN = "com.embabel.agent.api.annotation.AchievesGoal";

	private static final String STATE_ANNOTATION_FQN = "com.embabel.agent.api.annotation.State";

	private static final String LLM_TOOL_ANNOTATION_FQN = "com.embabel.agent.api.annotation.LlmTool";

	private static final String CONDITION_ANNOTATION_FQN = "com.embabel.agent.api.annotation.Condition";

	private static final String PROVIDED_ANNOTATION_FQN = "com.embabel.agent.api.annotation.Provided";

	private static final String REQUIRE_NAME_MATCH_ANNOTATION_FQN = "com.embabel.agent.api.annotation.RequireNameMatch";

	/**
	 * Step types the planner registers. A {@code @Cost} function and an {@code @LlmTool}
	 * are deliberately not plan steps — flagging every one of them as missing from the
	 * plan would bury the case that actually matters, a declared action the planner does
	 * not run.
	 */
	private static final Set<String> PLANNABLE_STEP_TYPES = Set.of("Action", "AchievesGoal", "Condition");

	/** {@code ActionRetryPolicy.DEFAULT} means "not configured" and is not reported. */
	private static final String DEFAULT_RETRY_POLICY = "DEFAULT";

	/**
	 * The value Embabel gives {@code @Agent(version)} when the author declares nothing.
	 * Reporting it would stamp a version an author never wrote onto every agent that
	 * simply did not care about versioning.
	 */
	private static final String DEFAULT_AGENT_VERSION = "0.1.0-SNAPSHOT";

	/**
	 * {@code IoBinding.DEFAULT_BINDING}: the value Embabel gives
	 * {@code @Action(outputBinding)} when the author declares nothing. Reporting it would
	 * decorate every single action with a binding it never asked for.
	 */
	private static final String DEFAULT_OUTPUT_BINDING = "it";

	private static final Set<String> STEP_ANNOTATION_FQNS = Set.of(ACTION_ANNOTATION_FQN, ACHIEVES_GOAL_ANNOTATION_FQN,
			CONDITION_ANNOTATION_FQN, "com.embabel.agent.api.annotation.Cost", LLM_TOOL_ANNOTATION_FQN);

	/** Framework parameter types that should not be reported as workflow inputs. */
	private static final Set<String> FRAMEWORK_PARAMETER_TYPES = Set.of("com.embabel.agent.api.common.OperationContext",
			"com.embabel.agent.api.common.ActionContext", "com.embabel.agent.core.AgentProcess",
			"com.embabel.agent.api.common.Ai");

	private final ApplicationContext applicationContext;

	private final AgentPlatformReader platformReader;

	private volatile WorkflowCatalog cached;

	public EmbabelWorkflowCatalogService(ApplicationContext applicationContext) {
		this(applicationContext, new AgentPlatformReader(applicationContext));
	}

	EmbabelWorkflowCatalogService(ApplicationContext applicationContext, AgentPlatformReader platformReader) {
		this.applicationContext = applicationContext;
		this.platformReader = platformReader;
	}

	/**
	 * The workflow catalog for this application context.
	 *
	 * <p>
	 * Computed once and reused: agent metadata comes from annotations on bean
	 * definitions, which no longer change after the context has refreshed, and a
	 * monitoring system polling {@code /actuator/embabel} should not re-reflect over
	 * every bean in the application on every request.
	 *
	 * <p>
	 * Only bean <em>types</em> are resolved, never bean instances. Asking for instances
	 * would force every lazy singleton and every {@code FactoryBean} product in the
	 * application to be created just so this endpoint can read their annotations —
	 * turning a read-only diagnostic into a side effect, and failing outright when a bean
	 * cannot be built in the current profile. Each bean is inspected independently, so
	 * one unreadable bean does not abort the scan.
	 */
	public WorkflowCatalog catalog() {
		WorkflowCatalog current = this.cached;
		if (current != null) {
			return current;
		}
		Scan scanned = scan();
		// A racing scan produces an equal, immutable result, so no lock is needed.
		if (scanned.worthCaching()) {
			this.cached = scanned.catalog();
		}
		return scanned.catalog();
	}

	/**
	 * A catalog plus whether it is safe to keep. A scan is only worth caching when every
	 * bean could be inspected and, where Embabel is on the classpath, the agent platform
	 * was up to reconcile against — otherwise a catalog produced before the platform
	 * existed would freeze the declared-only view for the life of the context.
	 */
	private record Scan(WorkflowCatalog catalog, boolean complete, boolean reconciled) {

		boolean worthCaching() {
			return this.complete && this.reconciled && !this.catalog.agents().isEmpty();
		}
	}

	private Scan scan() {
		String[] beanNames;
		try {
			beanNames = this.applicationContext.getBeanNamesForType(Object.class, false, false);
		}
		catch (RuntimeException ex) {
			log.warn("Failed to enumerate beans for the Embabel workflow catalog", ex);
			return new Scan(new WorkflowCatalog(List.of()), false, false);
		}

		List<AgentWorkflow> agents = new ArrayList<>();
		boolean complete = true;
		for (String beanName : beanNames) {
			try {
				agentTypeOf(beanName).flatMap(this::toAgentWorkflow).ifPresent(agents::add);
			}
			catch (Exception | LinkageError ex) {
				complete = false;
				log.warn("Skipping bean '{}' while scanning for Embabel agents", beanName, ex);
			}
		}
		List<RuntimeAgent> runtimeAgents = this.platformReader.readAgents();
		List<AgentWorkflow> merged = withRuntimeView(agents, runtimeAgents);
		merged.sort(Comparator.comparing(AgentWorkflow::agentName, String.CASE_INSENSITIVE_ORDER));
		boolean reconciled = !runtimeAgents.isEmpty() || !this.platformReader.platformExpected();
		return new Scan(new WorkflowCatalog(List.copyOf(merged)), complete, reconciled);
	}

	/**
	 * Reconciles the declared workflows with the agents a live {@code AgentPlatform}
	 * actually registered.
	 *
	 * <p>
	 * Annotations describe intent; the planner decides what runs, and the two diverge in
	 * ways an author cannot see from the source. A {@code SUPERVISOR} agent's declared
	 * actions are replaced by one synthetic supervisor action, a {@code UTILITY} agent
	 * gains a synthetic goal, and an agent assembled in code carries no annotations at
	 * all. Each declared step is therefore marked with whether the planner registered it,
	 * runtime-only steps are added, and agents with no annotated class are reported in
	 * their own right.
	 *
	 * <p>
	 * With no platform available every {@code registered} flag stays {@code null},
	 * meaning "not known" rather than "not registered", and the catalog is exactly the
	 * declared view.
	 */
	private List<AgentWorkflow> withRuntimeView(List<AgentWorkflow> declared, List<RuntimeAgent> runtimeAgents) {
		if (runtimeAgents.isEmpty()) {
			return new ArrayList<>(declared);
		}

		List<RuntimeAgent> unmatched = new ArrayList<>(runtimeAgents);
		List<AgentWorkflow> reconciled = new ArrayList<>();
		for (AgentWorkflow agent : declared) {
			RuntimeAgent runtime = unmatched.stream()
				.filter(candidate -> matches(candidate, agent))
				.findFirst()
				.orElse(null);
			if (runtime == null) {
				reconciled.add(withRegistered(agent, false, agent.steps()));
				continue;
			}
			unmatched.remove(runtime);
			reconciled.add(withRegistered(agent, true, reconcileSteps(agent, runtime)));
		}
		unmatched.forEach(runtime -> reconciled.add(toDeclaredlessAgent(runtime)));
		return reconciled;
	}

	private boolean matches(RuntimeAgent runtime, AgentWorkflow declared) {
		return runtime.name().equals(declared.className()) || runtime.name().equals(declared.agentName())
				|| AgentPlatformReader.simpleName(runtime.name()).equals(declared.agentName());
	}

	private List<WorkflowStep> reconcileSteps(AgentWorkflow declared, RuntimeAgent runtime) {
		Set<String> registeredNames = runtime.steps()
			.stream()
			.map(RuntimeStep::simpleStepName)
			.collect(Collectors.toSet());

		List<WorkflowStep> steps = new ArrayList<>();
		Set<String> declaredNames = new HashSet<>();
		for (WorkflowStep step : declared.steps()) {
			declaredNames.add(step.method());
			declaredNames.add(step.name());
			Boolean registered = PLANNABLE_STEP_TYPES.contains(step.type())
					? registeredNames.contains(step.method()) || registeredNames.contains(step.name()) : null;
			steps.add(withRegistered(step, registered));
		}
		runtime.steps()
			.stream()
			.filter(runtimeStep -> !declaredNames.contains(runtimeStep.simpleStepName()))
			.map(this::toPlannerGeneratedStep)
			.forEach(steps::add);

		steps.sort(Comparator.comparing(WorkflowStep::name, String.CASE_INSENSITIVE_ORDER));
		return steps;
	}

	private AgentWorkflow toDeclaredlessAgent(RuntimeAgent runtime) {
		List<WorkflowStep> steps = new ArrayList<>(runtime.steps().stream().map(this::toPlannerGeneratedStep).toList());
		steps.sort(Comparator.comparing(WorkflowStep::name, String.CASE_INSENSITIVE_ORDER));
		return AgentWorkflow.builder(AgentPlatformReader.simpleName(runtime.name()), runtime.name())
			.description(runtime.description())
			.plannerType("RUNTIME")
			.opaque(runtime.opaque())
			.steps(steps)
			.provider(emptyToNullValue(runtime.provider()))
			.registered(true)
			.build();
	}

	private WorkflowStep toPlannerGeneratedStep(RuntimeStep runtimeStep) {
		return WorkflowStep.builder(runtimeStep.simpleStepName(), runtimeStep.type(), runtimeStep.simpleStepName())
			.description(runtimeStep.description())
			.inputs(runtimeStep.inputs())
			.output(runtimeStep.output())
			.pre(runtimeStep.pre())
			.post(runtimeStep.post())
			.goal("AchievesGoal".equals(runtimeStep.type()))
			.canRerun(runtimeStep.canRerun())
			.readOnly(runtimeStep.readOnly())
			.registered(true)
			.plannerGenerated(true)
			.build();
	}

	private AgentWorkflow withRegistered(AgentWorkflow agent, boolean registered, List<WorkflowStep> steps) {
		return AgentWorkflow.builder(agent.agentName(), agent.className())
			.description(agent.description())
			.version(agent.version())
			.plannerType(agent.plannerType())
			.opaque(agent.opaque())
			.steps(steps)
			.provider(agent.provider())
			.beanName(agent.beanName())
			.scan(agent.scan())
			.retryPolicy(agent.retryPolicy())
			.retryPolicyExpression(agent.retryPolicyExpression())
			.registered(registered)
			.build();
	}

	private WorkflowStep withRegistered(WorkflowStep step, Boolean registered) {
		return new WorkflowStep(step.name(), step.type(), step.description(), step.method(), step.pre(), step.post(),
				step.inputs(), step.output(), step.goal(), step.costMethod(), step.valueMethod(), step.cost(),
				step.value(), step.goalValue(), step.possibleOutputs(), step.canRerun(), step.readOnly(),
				step.outputBinding(), step.clearBlackboard(), step.tags(), step.examples(), step.llmTool(),
				step.llmToolDescription(), step.exportedRemote(), step.exportName(), step.trigger(), step.retryPolicy(),
				step.llmToolReturnDirect(), step.llmToolCategory(), step.actionRetryPolicy(), step.conditionCost(),
				step.exportedLocal(), step.exportStartingInputTypes(), step.llmToolName(), step.llmToolMetadata(),
				step.providedInputs(), step.nameMatchInputs(), registered, step.plannerGenerated());
	}

	private String emptyToNullValue(String value) {
		return StringUtils.hasText(value) ? value : null;
	}

	/**
	 * Resolves the annotated user class behind a bean name without ever creating a bean.
	 *
	 * <p>
	 * An already-created singleton is the most reliable source — it reveals the concrete
	 * class behind a JDK dynamic proxy, which no declared type can — and reading one that
	 * already exists initialises nothing. Otherwise the declared type is used, which
	 * Spring resolves from the bean definition.
	 *
	 * <p>
	 * That leaves exactly one blind spot: a bean that has not been created <em>and</em>
	 * whose declared type is too generic to carry the annotation, such as
	 * {@code @Bean @Lazy Object agent()}. Identifying it would require instantiating it,
	 * which this scan will not do, so it is reported at debug level rather than passed
	 * over silently.
	 */
	private Optional<Class<?>> agentTypeOf(String beanName) {
		Object existing = existingSingleton(beanName);
		if (existing != null) {
			return Optional.of(ClassUtils.getUserClass(AopUtils.getTargetClass(existing)));
		}

		Class<?> declaredType = this.applicationContext.getType(beanName, false);
		if (declaredType == null) {
			return Optional.empty();
		}
		if (declaredType == Object.class || declaredType.isInterface()) {
			log.debug("Bean '{}' is not yet created and is declared as {}, which cannot carry an Embabel annotation; "
					+ "it is skipped rather than instantiated to find out", beanName, declaredType.getName());
			return Optional.empty();
		}
		return Optional.of(ClassUtils.getUserClass(declaredType));
	}

	/** The singleton instance if it already exists; never triggers creation. */
	private Object existingSingleton(String beanName) {
		return this.applicationContext instanceof ConfigurableApplicationContext configurable
				? configurable.getBeanFactory().getSingleton(beanName) : null;
	}

	private Optional<AgentWorkflow> toAgentWorkflow(Class<?> targetType) {
		Annotation agentAnnotation = AnnotationAttributes.find(targetType, AGENT_ANNOTATION_FQN);
		Annotation componentAnnotation = agentAnnotation == null
				? AnnotationAttributes.find(targetType, EMBABEL_COMPONENT_ANNOTATION_FQN) : null;
		if (agentAnnotation == null && componentAnnotation == null) {
			return Optional.empty();
		}

		Annotation source = agentAnnotation != null ? agentAnnotation : componentAnnotation;
		String agentName = nameOr(source, targetType.getSimpleName());
		String description = AnnotationAttributes.string(source, "description");
		String version = readDeclaredVersion(source);
		// provider is only declared on @Agent (not @EmbabelComponent)
		String providerAttr = agentAnnotation != null ? AnnotationAttributes.string(agentAnnotation, "provider") : "";
		String provider = StringUtils.hasText(providerAttr) ? providerAttr : null;
		// plannerType: read enum constant name from @Agent; mark @EmbabelComponent as
		// "COMPONENT"
		String plannerType = agentAnnotation != null ? AnnotationAttributes.enumName(agentAnnotation, "planner")
				: "COMPONENT";
		boolean opaque = agentAnnotation != null && AnnotationAttributes.flag(agentAnnotation, "opaque");

		// beanName is only on @Agent; scan is on both annotations and defaults to true
		String beanNameAttr = agentAnnotation != null ? AnnotationAttributes.string(agentAnnotation, "beanName") : "";
		String beanName = StringUtils.hasText(beanNameAttr) ? beanNameAttr : null;
		boolean scan = AnnotationAttributes.flag(source, "scan", true);
		String retryPolicy = agentAnnotation != null ? readNonDefaultRetryPolicy(agentAnnotation) : null;
		String retryExpression = agentAnnotation != null
				? AnnotationAttributes.stringOrNull(agentAnnotation, "actionRetryPolicyExpression") : null;

		return Optional.of(AgentWorkflow.builder(agentName, targetType.getName())
			.description(description)
			.version(version)
			.plannerType(plannerType)
			.opaque(opaque)
			.steps(collectSteps(targetType))
			.provider(provider)
			.beanName(beanName)
			.scan(scan)
			.retryPolicy(retryPolicy)
			.retryPolicyExpression(retryExpression)
			.build());
	}

	private List<WorkflowStep> collectSteps(Class<?> targetType) {
		return collectSteps(targetType, List.of());
	}

	private List<WorkflowStep> collectSteps(Class<?> targetType, List<String> implicitInputs) {
		Map<Method, List<Annotation>> stepMethods = findStepMethods(targetType);

		// Compute @State component types so that steps returning Object can expose
		// the concrete types the @State routing actually produces.
		List<String> stateComponentTypes = stateComponentTypesOf(targetType);

		List<WorkflowStep> steps = new ArrayList<>();
		for (Map.Entry<Method, List<Annotation>> entry : stepMethods.entrySet()) {
			Method m = entry.getKey();
			boolean returnsStateType = m.getReturnType() == Object.class
					|| AnnotationAttributes.find(m.getReturnType(), STATE_ANNOTATION_FQN) != null;
			List<String> possibleOutputs = (returnsStateType && !stateComponentTypes.isEmpty()) ? stateComponentTypes
					: null;
			steps.add(toStep(m, entry.getValue(), implicitInputs, possibleOutputs));
		}

		// Scan @State-annotated inner classes; pass their record components as implicit
		// inputs so that e.g. handleBilling(OperationContext) shows BillingTicket as
		// its input (because the BillingState record holds a BillingTicket field).
		for (Class<?> inner : targetType.getDeclaredClasses()) {
			if (AnnotationAttributes.find(inner, STATE_ANNOTATION_FQN) != null) {
				steps.addAll(collectSteps(inner, recordComponentSimpleNames(inner)));
			}
		}

		steps.sort(Comparator.comparing(WorkflowStep::name, String.CASE_INSENSITIVE_ORDER));
		return steps;
	}

	/**
	 * Collects every annotated step method reachable from {@code targetType}, grouping
	 * the annotations by method so a method carrying both {@code @Action} and
	 * {@code @AchievesGoal} becomes one enriched step rather than two duplicates.
	 *
	 * <p>
	 * Walks the whole type hierarchy — superclasses and interface default methods
	 * included — to match how Embabel's own {@code AgentMetadataReader} discovers
	 * actions, conditions and cost methods. Scanning only declared methods would silently
	 * hide every step an agent inherits from a shared base class.
	 */
	private Map<Method, List<Annotation>> findStepMethods(Class<?> targetType) {
		// putIfAbsent keeps the override: doWithMethods visits the most derived first.
		Map<String, Method> mostDerived = new LinkedHashMap<>();
		ReflectionUtils.doWithMethods(targetType, method -> mostDerived.putIfAbsent(signatureOf(method), method),
				method -> !method.isSynthetic() && !method.isBridge() && hasStepAnnotation(method));

		Map<Method, List<Annotation>> grouped = new LinkedHashMap<>();
		for (Method method : mostDerived.values()) {
			for (Annotation annotation : method.getAnnotations()) {
				if (STEP_ANNOTATION_FQNS.contains(annotation.annotationType().getName())) {
					grouped.computeIfAbsent(method, m -> new ArrayList<>()).add(annotation);
				}
			}
		}
		return grouped;
	}

	private boolean hasStepAnnotation(Method method) {
		for (Annotation annotation : method.getAnnotations()) {
			if (STEP_ANNOTATION_FQNS.contains(annotation.annotationType().getName())) {
				return true;
			}
		}
		return false;
	}

	private String signatureOf(Method method) {
		StringJoiner signature = new StringJoiner(",", method.getName() + "(", ")");
		for (Class<?> parameterType : method.getParameterTypes()) {
			signature.add(parameterType.getName());
		}
		return signature.toString();
	}

	/**
	 * Returns the simple class names of all record components for a record class.
	 */
	private List<String> recordComponentSimpleNames(Class<?> type) {
		if (!type.isRecord()) {
			return List.of();
		}
		return Arrays.stream(type.getRecordComponents())
			.map(RecordComponent::getType)
			.filter(t -> !FRAMEWORK_PARAMETER_TYPES.contains(t.getName()))
			.map(Class::getSimpleName)
			.toList();
	}

	/**
	 * Collects the simple class names of all record components from every @State inner
	 * record on {@code targetType}.
	 */
	private List<String> stateComponentTypesOf(Class<?> targetType) {
		return Arrays.stream(targetType.getDeclaredClasses())
			.filter(inner -> AnnotationAttributes.find(inner, STATE_ANNOTATION_FQN) != null && inner.isRecord())
			.flatMap(inner -> Arrays.stream(inner.getRecordComponents()))
			.map(RecordComponent::getType)
			.filter(t -> !FRAMEWORK_PARAMETER_TYPES.contains(t.getName()))
			.map(Class::getSimpleName)
			.distinct()
			.toList();
	}

	private WorkflowStep toStep(Method method, List<Annotation> annotations, List<String> implicitInputs,
			List<String> possibleOutputs) {
		Annotation primary = annotations.stream()
			.filter(a -> ACTION_ANNOTATION_FQN.equals(a.annotationType().getName()))
			.findFirst()
			.orElse(annotations.get(0));

		boolean achievesGoal = annotations.stream()
			.anyMatch(a -> ACHIEVES_GOAL_ANNOTATION_FQN.equals(a.annotationType().getName()));

		boolean llmTool = annotations.stream()
			.anyMatch(a -> LLM_TOOL_ANNOTATION_FQN.equals(a.annotationType().getName()));

		String type = achievesGoal && ACTION_ANNOTATION_FQN.equals(primary.annotationType().getName()) ? "AchievesGoal"
				: primary.annotationType().getSimpleName();

		String name = nameOr(primary, method.getName());
		String description = annotations.stream()
			.map(a -> AnnotationAttributes.string(a, "description"))
			.filter(StringUtils::hasText)
			.findFirst()
			.orElse("");

		List<String> pre = AnnotationAttributes.strings(primary, "pre");
		List<String> post = AnnotationAttributes.strings(primary, "post");

		// Explicit method parameters (minus framework types) + any implicit inputs
		// from an enclosing @State record's components.
		List<String> methodInputs = Arrays.stream(method.getParameterTypes())
			.filter(p -> !FRAMEWORK_PARAMETER_TYPES.contains(p.getName()))
			.map(Class::getSimpleName)
			.toList();
		List<String> inputs = implicitInputs.isEmpty() ? methodInputs
				: Stream.concat(implicitInputs.stream(), methodInputs.stream()).distinct().toList();

		String costMethod = AnnotationAttributes.stringOrNull(primary, "costMethod");
		String valueMethod = AnnotationAttributes.stringOrNull(primary, "valueMethod");

		// Static cost/value declared directly on @Action; the annotation default is
		// 0.0, which we map to null ("not set") so the UI only shows explicit values.
		// Only read from @Action — @AchievesGoal has its own (different) "value".
		boolean primaryIsAction = ACTION_ANNOTATION_FQN.equals(primary.annotationType().getName());
		Double cost = primaryIsAction ? AnnotationAttributes.nonZeroDouble(primary, "cost") : null;
		Double value = primaryIsAction ? AnnotationAttributes.nonZeroDouble(primary, "value") : null;

		// @Action-specific fields
		boolean canRerun = AnnotationAttributes.flag(primary, "canRerun");
		boolean readOnly = AnnotationAttributes.flag(primary, "readOnly");
		boolean clearBlackboard = AnnotationAttributes.flag(primary, "clearBlackboard");
		String outputBinding = readCustomOutputBinding(primary);

		// @Action(trigger = SomeEvent.class): the action is event-triggered. The
		// Embabel
		// default is kotlin.Unit ("no trigger"), which we map to null.
		String trigger = primaryIsAction ? AnnotationAttributes.classSimpleName(primary, "trigger") : null;
		// @Action(actionRetryPolicyExpression = "..."): per-action retry policy.
		String retryExpr = primaryIsAction ? AnnotationAttributes.string(primary, "actionRetryPolicyExpression") : "";
		String retryPolicy = StringUtils.hasText(retryExpr) ? retryExpr : null;
		String actionRetryPolicy = primaryIsAction ? readNonDefaultRetryPolicy(primary) : null;

		// @Condition(cost = ...), distinct from the @Action(cost = ...) read above
		Double conditionCost = null;
		for (Annotation a : annotations) {
			if (CONDITION_ANNOTATION_FQN.equals(a.annotationType().getName())) {
				conditionCost = AnnotationAttributes.nonZeroDouble(a, "cost");
			}
		}

		// @AchievesGoal-specific fields
		List<String> tags = List.of();
		List<String> examples = List.of();
		Double goalValue = null;
		boolean exportedRemote = false;
		boolean exportedLocal = false;
		List<String> exportStartingInputTypes = List.of();
		String exportName = null;
		for (Annotation a : annotations) {
			if (ACHIEVES_GOAL_ANNOTATION_FQN.equals(a.annotationType().getName())) {
				tags = AnnotationAttributes.strings(a, "tags");
				examples = AnnotationAttributes.strings(a, "examples");
				goalValue = AnnotationAttributes.nonZeroDouble(a, "value");
				Annotation export = AnnotationAttributes.nested(a, "export");
				if (export != null) {
					exportedRemote = AnnotationAttributes.flag(export, "remote");
					exportedLocal = AnnotationAttributes.flag(export, "local", true);
					exportStartingInputTypes = AnnotationAttributes.classSimpleNames(export, "startingInputTypes");
					String name1 = AnnotationAttributes.string(export, "name");
					exportName = StringUtils.hasText(name1) ? name1 : null;
				}
			}
		}

		// @LlmTool-specific fields
		String llmToolDescription = null;
		boolean llmToolReturnDirect = false;
		String llmToolCategory = null;
		String llmToolName = null;
		List<ToolMetadata> llmToolMetadata = List.of();
		if (llmTool) {
			for (Annotation a : annotations) {
				if (LLM_TOOL_ANNOTATION_FQN.equals(a.annotationType().getName())) {
					llmToolDescription = AnnotationAttributes.string(a, "description");
					if (!StringUtils.hasText(llmToolDescription))
						llmToolDescription = null;
					llmToolReturnDirect = AnnotationAttributes.flag(a, "returnDirect");
					String category = AnnotationAttributes.string(a, "category");
					llmToolCategory = StringUtils.hasText(category) ? category : null;
					llmToolName = AnnotationAttributes.stringOrNull(a, "name");
					llmToolMetadata = readToolMetadata(a);
					// Use @LlmTool description as step description if no other
					// description
					if (!StringUtils.hasText(description) && StringUtils.hasText(llmToolDescription)) {
						description = llmToolDescription;
					}
					break;
				}
			}
		}

		List<String> providedInputs = readParameterAnnotatedTypes(method, PROVIDED_ANNOTATION_FQN);
		List<String> nameMatchInputs = readRequireNameMatchInputs(method);

		return WorkflowStep.builder(name, type, method.getName())
			.description(description)
			.pre(pre)
			.post(post)
			.inputs(inputs)
			.output(method.getReturnType().getSimpleName())
			.goal(achievesGoal)
			.costMethod(costMethod)
			.valueMethod(valueMethod)
			.cost(cost)
			.value(value)
			.goalValue(goalValue)
			.possibleOutputs(possibleOutputs)
			.canRerun(canRerun)
			.readOnly(readOnly)
			.outputBinding(outputBinding)
			.clearBlackboard(clearBlackboard)
			.tags(tags)
			.examples(examples)
			.llmTool(llmTool)
			.llmToolDescription(llmToolDescription)
			.exportedRemote(exportedRemote)
			.exportName(exportName)
			.trigger(trigger)
			.retryPolicy(retryPolicy)
			.llmToolReturnDirect(llmToolReturnDirect)
			.llmToolCategory(llmToolCategory)
			.actionRetryPolicy(actionRetryPolicy)
			.conditionCost(conditionCost)
			.exportedLocal(exportedLocal)
			.exportStartingInputTypes(exportStartingInputTypes)
			.llmToolName(llmToolName)
			.llmToolMetadata(llmToolMetadata)
			.providedInputs(providedInputs)
			.nameMatchInputs(nameMatchInputs)
			.build();
	}

	/** Annotation {@code name} attribute, falling back to the Java element's own name. */
	private String nameOr(Annotation annotation, String fallback) {
		String declared = AnnotationAttributes.stringOrNull(annotation, "name");
		return declared != null ? declared : fallback;
	}

	/**
	 * Reads {@code version}, returning {@code null} unless the author declared one.
	 * {@code @Agent} defaults it to {@code 0.1.0-SNAPSHOT} and {@code @EmbabelComponent}
	 * has no such attribute at all, so neither case describes an authored version.
	 */
	private String readDeclaredVersion(Annotation annotation) {
		String version = AnnotationAttributes.stringOrNull(annotation, "version");
		return DEFAULT_AGENT_VERSION.equals(version) ? null : version;
	}

	/**
	 * Reads {@code actionRetryPolicy}, returning {@code null} when left at
	 * {@code ActionRetryPolicy.DEFAULT} so only explicit policies are surfaced.
	 */
	private String readNonDefaultRetryPolicy(Annotation annotation) {
		String policy = AnnotationAttributes.enumName(annotation, "actionRetryPolicy");
		return StringUtils.hasText(policy) && !DEFAULT_RETRY_POLICY.equals(policy) ? policy : null;
	}

	/**
	 * Reads {@code @Action(outputBinding)}, returning {@code null} unless the author
	 * named a custom blackboard binding.
	 */
	private String readCustomOutputBinding(Annotation annotation) {
		String binding = AnnotationAttributes.string(annotation, "outputBinding");
		return DEFAULT_OUTPUT_BINDING.equals(binding) ? null
				: AnnotationAttributes.stringOrNull(annotation, "outputBinding");
	}

	/**
	 * Reads {@code @LlmTool(metadata = {@literal @}Meta(key, value))} pairs.
	 */
	private List<ToolMetadata> readToolMetadata(Annotation annotation) {
		List<ToolMetadata> metadata = new ArrayList<>();
		for (Annotation entry : AnnotationAttributes.nestedArray(annotation, "metadata")) {
			String key = AnnotationAttributes.string(entry, "key");
			if (StringUtils.hasText(key)) {
				metadata.add(new ToolMetadata(key, AnnotationAttributes.string(entry, "value")));
			}
		}
		return List.copyOf(metadata);
	}

	/**
	 * Returns the simple type names of parameters carrying the given parameter-level
	 * annotation.
	 */
	private List<String> readParameterAnnotatedTypes(Method method, String annotationTypeName) {
		return Arrays.stream(method.getParameters())
			.filter(parameter -> AnnotationAttributes.isPresent(parameter, annotationTypeName))
			.map(parameter -> parameter.getType().getSimpleName())
			.toList();
	}

	/**
	 * Returns {@code @RequireNameMatch} parameters as {@code Type}, or
	 * {@code Type:boundName} when the annotation declares an explicit binding name.
	 */
	private List<String> readRequireNameMatchInputs(Method method) {
		List<String> names = new ArrayList<>();
		for (Parameter parameter : method.getParameters()) {
			Annotation nameMatch = AnnotationAttributes.find(parameter, REQUIRE_NAME_MATCH_ANNOTATION_FQN);
			if (nameMatch != null) {
				String type = parameter.getType().getSimpleName();
				String bound = AnnotationAttributes.stringOrNull(nameMatch, "value");
				names.add(bound != null ? type + ":" + bound : type);
			}
		}
		return List.copyOf(names);
	}

}
