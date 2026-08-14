package com.patbaumgartner.embabel.workflow.visualizer;

import com.patbaumgartner.embabel.workflow.visualizer.WorkflowModels.AgentWorkflow;
import com.patbaumgartner.embabel.workflow.visualizer.WorkflowModels.ToolMetadata;
import com.patbaumgartner.embabel.workflow.visualizer.WorkflowModels.WorkflowStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.stream.Stream;

/**
 * Reads the workflows an application <em>declares</em>, by reflecting over the Embabel
 * annotations on the types behind its bean definitions.
 *
 * <p>
 * Never imports an Embabel type: every annotation is looked up by name, so the starter
 * stays usable without {@code embabel-agent-api} on the runtime classpath and keeps
 * working against Embabel versions it was not built against.
 *
 * <p>
 * Only bean <em>types</em> are resolved, never bean instances. Asking for instances would
 * force every lazy singleton and every {@code FactoryBean} product in the application to
 * be created just so this endpoint can read their annotations — turning a read-only
 * diagnostic into a side effect, and failing outright when a bean cannot be built in the
 * current profile.
 */
class DeclaredWorkflowReader {

	private static final Logger log = LoggerFactory.getLogger(DeclaredWorkflowReader.class);

	private static final String AGENT_ANNOTATION_FQN = "com.embabel.agent.api.annotation.Agent";

	private static final String EMBABEL_COMPONENT_ANNOTATION_FQN = "com.embabel.agent.api.annotation.EmbabelComponent";

	private static final String ACTION_ANNOTATION_FQN = "com.embabel.agent.api.annotation.Action";

	private static final String ACHIEVES_GOAL_ANNOTATION_FQN = "com.embabel.agent.api.annotation.AchievesGoal";

	private static final String STATE_ANNOTATION_FQN = "com.embabel.agent.api.annotation.State";

	private static final String LLM_TOOL_ANNOTATION_FQN = "com.embabel.agent.api.annotation.LlmTool";

	private static final String CONDITION_ANNOTATION_FQN = "com.embabel.agent.api.annotation.Condition";

	private static final String PROVIDED_ANNOTATION_FQN = "com.embabel.agent.api.annotation.Provided";

	private static final String REQUIRE_NAME_MATCH_ANNOTATION_FQN = "com.embabel.agent.api.annotation.RequireNameMatch";

	private static final String COST_ANNOTATION_FQN = "com.embabel.agent.api.annotation.Cost";

	/**
	 * The annotations that make a method a step, most defining first.
	 *
	 * <p>
	 * A method can carry several — {@code @Action} with {@code @AchievesGoal},
	 * {@code @AchievesGoal} with {@code @LlmTool} — and one of them has to decide what
	 * the step is called and what type it reports. {@link Method#getAnnotations()} does
	 * not specify its order, so taking whichever came first would let a compiler or JDK
	 * change silently rename a step and reclassify its node in the diagram.
	 */
	private static final List<String> STEP_ANNOTATIONS_BY_PRECEDENCE = List.of(ACTION_ANNOTATION_FQN,
			ACHIEVES_GOAL_ANNOTATION_FQN, CONDITION_ANNOTATION_FQN, COST_ANNOTATION_FQN, LLM_TOOL_ANNOTATION_FQN);

	private static final Set<String> STEP_ANNOTATION_FQNS = Set.copyOf(STEP_ANNOTATIONS_BY_PRECEDENCE);

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

	/** Framework parameter types that should not be reported as workflow inputs. */
	private static final Set<String> FRAMEWORK_PARAMETER_TYPES = Set.of("com.embabel.agent.api.common.OperationContext",
			"com.embabel.agent.api.common.ActionContext", "com.embabel.agent.core.AgentProcess",
			"com.embabel.agent.api.common.Ai");

	private final ApplicationContext applicationContext;

	DeclaredWorkflowReader(ApplicationContext applicationContext) {
		this.applicationContext = applicationContext;
	}

	/**
	 * Every agent declared by a bean definition in this context.
	 *
	 * <p>
	 * Each bean is inspected independently: one whose type cannot be resolved is reported
	 * and stepped over rather than aborting the scan, because a single unreadable bean
	 * should not cost the whole catalog.
	 */
	List<AgentWorkflow> readDeclaredAgents() {
		String[] beanNames;
		try {
			beanNames = this.applicationContext.getBeanNamesForType(Object.class, false, false);
		}
		catch (RuntimeException | LinkageError ex) {
			log.warn("Failed to enumerate beans for the Embabel workflow catalog", ex);
			return List.of();
		}

		List<AgentWorkflow> agents = new ArrayList<>();
		for (String beanName : beanNames) {
			try {
				agentTypeOf(beanName).flatMap(this::toAgentWorkflow).ifPresent(agents::add);
			}
			catch (Exception | LinkageError ex) {
				log.warn("Skipping bean '{}' while scanning for Embabel agents", beanName, ex);
			}
		}
		return agents;
	}

	/**
	 * Resolves the annotated user class behind a bean name without ever creating a bean.
	 *
	 * <p>
	 * An already-created singleton is the most reliable source — it reveals the concrete
	 * class behind a JDK dynamic proxy, which no declared type can — and reading one that
	 * already exists initialises nothing. A {@link FactoryBean} is the exception, because
	 * there the singleton is the factory rather than the bean it publishes. Otherwise the
	 * declared type is used, which Spring resolves from the bean definition, and for a
	 * factory resolves to its product.
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
		// A FactoryBean singleton is the factory, and the bean it publishes is its
		// product — which is where the annotations are. Reading the factory instead
		// would lose the agent from the moment the factory was created, so the declared
		// type is used, which Spring resolves to the product.
		if (existing != null && !(existing instanceof FactoryBean<?>)) {
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

	private List<WorkflowStep> collectSteps(Class<?> agentType) {
		List<WorkflowStep> steps = new ArrayList<>();
		collectStepsInto(agentType, agentType, List.of(), steps, new LinkedHashSet<>());
		steps.sort(Comparator.comparing(WorkflowStep::name, String.CASE_INSENSITIVE_ORDER));
		return steps;
	}

	/**
	 * Collects the steps {@code owner} declares, then follows each one's return type into
	 * the {@code @State} classes it can produce.
	 *
	 * <p>
	 * Embabel reaches a state through an action's <em>return type</em>, not through where
	 * the state happens to be written, and it binds the state class itself as an input of
	 * every action declared inside it. Both are reproduced here, because a state nobody
	 * returns is never unrolled and a handler that takes only an {@code OperationContext}
	 * still consumes the state it belongs to.
	 * @param owner the type whose step methods are being read
	 * @param agentType the agent the scan started from, whose nested types are candidate
	 * state implementations
	 * @param implicitInputs inputs every step of {@code owner} consumes regardless of its
	 * signature — the enclosing state class, when {@code owner} is one
	 * @param steps accumulates the steps found
	 * @param unrolledStates state classes already visited, which stops a routing cycle
	 * from recursing forever
	 */
	private void collectStepsInto(Class<?> owner, Class<?> agentType, List<String> implicitInputs,
			List<WorkflowStep> steps, Set<Class<?>> unrolledStates) {
		boolean insideState = !implicitInputs.isEmpty();
		for (Map.Entry<Method, List<Annotation>> entry : findStepMethods(owner).entrySet()) {
			Method method = entry.getKey();
			// Embabel only unrolls @Action methods of a state class; a @Condition or
			// @Cost written there is never registered, so reporting it would invent a
			// step the planner does not have.
			if (insideState && !declaresAction(entry.getValue())) {
				continue;
			}
			List<Class<?>> reachableStates = statesReachableFrom(method.getReturnType(), agentType);
			steps.add(toStep(method, entry.getValue(), implicitInputs, alternativeOutputs(method, reachableStates)));

			for (Class<?> state : reachableStates) {
				if (unrolledStates.add(state)) {
					collectStepsInto(state, agentType, List.of(state.getSimpleName()), steps, unrolledStates);
				}
			}
		}
	}

	private boolean declaresAction(List<Annotation> annotations) {
		return annotations.stream().anyMatch(a -> ACTION_ANNOTATION_FQN.equals(a.annotationType().getName()));
	}

	/**
	 * The concrete states a step may really produce, or {@code null} when its return type
	 * already names the one thing it returns.
	 *
	 * <p>
	 * A routing action returns the common supertype — {@code TicketCategory}, or bare
	 * {@code Object} — which says nothing about where the workflow goes next. Only then
	 * is there anything to add.
	 */
	private List<String> alternativeOutputs(Method method, List<Class<?>> reachableStates) {
		if (reachableStates.isEmpty() || reachableStates.equals(List.of(method.getReturnType()))) {
			return null;
		}
		return reachableStates.stream().map(Class::getSimpleName).distinct().toList();
	}

	/**
	 * The {@code @State} classes an action returning {@code returnType} can produce: the
	 * type itself when it is one, plus the implementations Embabel would find for it.
	 *
	 * <p>
	 * Embabel scans the classpath for subtypes. This resolves the two shapes that scan
	 * can be reproduced from without one — a sealed type's permitted subclasses, and the
	 * agent's own nested types — which together cover how a routing state is written in
	 * practice. {@code Object} is excluded deliberately: every nested state would be
	 * assignable to it, so honouring it would claim a branch to every state in the agent
	 * from any method that happens to return {@code Object}.
	 */
	private List<Class<?>> statesReachableFrom(Class<?> returnType, Class<?> agentType) {
		if (returnType == null || returnType == Object.class || returnType.isPrimitive()) {
			return List.of();
		}
		List<Class<?>> states = new ArrayList<>();
		// An interface or abstract class is how a routing action names its branches, not
		// something it can return: only its implementations are ever produced.
		if (isStateType(returnType) && !isAbstract(returnType)) {
			states.add(returnType);
		}
		Stream.concat(Arrays.stream(candidateSubtypes(returnType)), Arrays.stream(agentType.getDeclaredClasses()))
			.filter(candidate -> candidate != returnType && returnType.isAssignableFrom(candidate))
			.filter(this::isStateType)
			.forEach(candidate -> {
				if (!states.contains(candidate)) {
					states.add(candidate);
				}
			});
		return List.copyOf(states);
	}

	private boolean isAbstract(Class<?> type) {
		return type.isInterface() || Modifier.isAbstract(type.getModifiers());
	}

	private Class<?>[] candidateSubtypes(Class<?> returnType) {
		Class<?>[] permitted = returnType.getPermittedSubclasses();
		return permitted != null ? permitted : new Class<?>[0];
	}

	/**
	 * Whether Embabel would treat this as a state, which it decides by looking for
	 * {@code @State} on the type, its superclasses and its interfaces. A record carrying
	 * no annotation of its own is still a state if the interface it implements is one.
	 */
	private boolean isStateType(Class<?> type) {
		return isStateType(type, new HashSet<>());
	}

	private boolean isStateType(Class<?> type, Set<Class<?>> visited) {
		if (type == null || type == Object.class || !visited.add(type)) {
			return false;
		}
		if (AnnotationAttributes.find(type, STATE_ANNOTATION_FQN) != null) {
			return true;
		}
		if (isStateType(type.getSuperclass(), visited)) {
			return true;
		}
		for (Class<?> implemented : type.getInterfaces()) {
			if (isStateType(implemented, visited)) {
				return true;
			}
		}
		return false;
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
		grouped.values().forEach(annotations -> annotations.sort(Comparator.comparingInt(this::precedenceOf)));
		return grouped;
	}

	private int precedenceOf(Annotation annotation) {
		return STEP_ANNOTATIONS_BY_PRECEDENCE.indexOf(annotation.annotationType().getName());
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

	private WorkflowStep toStep(Method method, List<Annotation> annotations, List<String> implicitInputs,
			List<String> possibleOutputs) {
		// Sorted by precedence in findStepMethods, so the most defining annotation leads
		// and both the step's identity and its description are read from it.
		Annotation primary = annotations.get(0);

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
