package com.patbaumgartner.embabel.workflow.visualizer;

import com.patbaumgartner.embabel.workflow.visualizer.WorkflowModels.AgentWorkflow;
import com.patbaumgartner.embabel.workflow.visualizer.WorkflowModels.ToolMetadata;
import com.patbaumgartner.embabel.workflow.visualizer.WorkflowModels.WorkflowCatalog;
import com.patbaumgartner.embabel.workflow.visualizer.WorkflowModels.WorkflowStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Proxy;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
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

	/** {@code ActionRetryPolicy.DEFAULT} means "not configured" and is not reported. */
	private static final String DEFAULT_RETRY_POLICY = "DEFAULT";

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

	public EmbabelWorkflowCatalogService(ApplicationContext applicationContext) {
		this.applicationContext = applicationContext;
	}

	/**
	 * Build the catalog. Each bean is inspected independently; failures are logged and do
	 * not abort the scan.
	 *
	 * <p>
	 * Only bean <em>types</em> are resolved, never bean instances. Asking for instances
	 * would force every lazy singleton and every {@code FactoryBean} product in the
	 * application to be created just so this endpoint can read their annotations —
	 * turning a read-only diagnostic into a side effect, and failing outright when a bean
	 * cannot be built in the current profile.
	 */
	public WorkflowCatalog catalog() {
		String[] beanNames;
		try {
			beanNames = applicationContext.getBeanNamesForType(Object.class, false, false);
		}
		catch (RuntimeException ex) {
			log.warn("Failed to enumerate beans for the Embabel workflow catalog", ex);
			return new WorkflowCatalog(List.of());
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
		agents.sort(Comparator.comparing(AgentWorkflow::agentName, String.CASE_INSENSITIVE_ORDER));
		return new WorkflowCatalog(agents);
	}

	/**
	 * Resolves the annotated user class behind a bean name without instantiating it.
	 *
	 * <p>
	 * CGLIB proxies are unwrapped by name. A JDK dynamic proxy hides the target class
	 * entirely, so the already-proxied instance is the only source for it; that bean must
	 * exist for a proxy to have been created, so obtaining it adds no new initialisation.
	 */
	private Optional<Class<?>> agentTypeOf(String beanName) {
		Class<?> beanType = applicationContext.getType(beanName, false);
		if (beanType == null) {
			return Optional.empty();
		}
		if (Proxy.isProxyClass(beanType)) {
			return Optional.of(ClassUtils.getUserClass(AopUtils.getTargetClass(applicationContext.getBean(beanName))));
		}
		return Optional.of(ClassUtils.getUserClass(beanType));
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
		String version = AnnotationAttributes.string(source, "version");
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
	 * included — because that is what Embabel's own {@code AgentMetadataReader} does when
	 * it registers actions, conditions and cost methods. Scanning only declared methods
	 * would silently hide every step an agent inherits from a shared base class.
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
