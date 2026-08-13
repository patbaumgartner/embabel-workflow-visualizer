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
	 * Build the catalog. Each bean is processed independently; failures are logged and do
	 * not abort the scan.
	 */
	public WorkflowCatalog catalog() {
		// allowEagerInit=false prevents Spring from eagerly initialising lazy
		// singletons and factory beans (e.g. ChatClient) just to determine whether they
		// match Object.class — which can fail when required dependencies (ChatModel,
		// etc.) are not available in the current profile / environment.
		Map<String, Object> beans;
		try {
			beans = applicationContext.getBeansOfType(Object.class, false, false);
		}
		catch (RuntimeException ex) {
			log.error("Failed to enumerate beans for Embabel workflow catalog", ex);
			return new WorkflowCatalog(List.of());
		}

		List<AgentWorkflow> agents = new ArrayList<>();
		for (Object bean : beans.values()) {
			try {
				toAgentWorkflow(bean).ifPresent(agents::add);
			}
			catch (Throwable t) {
				log.error("Skipping bean {} while scanning Embabel agents: {}", bean.getClass().getName(),
						t.toString());
			}
		}
		agents.sort(Comparator.comparing(AgentWorkflow::agentName, String.CASE_INSENSITIVE_ORDER));
		return new WorkflowCatalog(agents);
	}

	private Optional<AgentWorkflow> toAgentWorkflow(Object bean) {
		Class<?> beanType = AopUtils.isAopProxy(bean) ? AopUtils.getTargetClass(bean) : bean.getClass();
		Class<?> targetType = ClassUtils.getUserClass(beanType);

		Annotation agentAnnotation = findAnnotation(targetType, AGENT_ANNOTATION_FQN);
		Annotation componentAnnotation = agentAnnotation == null
				? findAnnotation(targetType, EMBABEL_COMPONENT_ANNOTATION_FQN) : null;
		if (agentAnnotation == null && componentAnnotation == null) {
			return Optional.empty();
		}

		Annotation source = agentAnnotation != null ? agentAnnotation : componentAnnotation;
		String agentName = firstNonBlank(readStringAttribute(source, "name"), targetType.getSimpleName());
		String description = readStringAttribute(source, "description");
		String version = readStringAttribute(source, "version");
		// provider is only declared on @Agent (not @EmbabelComponent)
		String providerAttr = agentAnnotation != null ? readStringAttribute(agentAnnotation, "provider") : "";
		String provider = StringUtils.hasText(providerAttr) ? providerAttr : null;
		// plannerType: read enum constant name from @Agent; mark @EmbabelComponent as
		// "COMPONENT"
		String plannerType = agentAnnotation != null ? readEnumNameAttribute(agentAnnotation, "planner") : "COMPONENT";
		boolean opaque = agentAnnotation != null && readBooleanAttribute(agentAnnotation, "opaque");

		// beanName is only on @Agent; scan is on both annotations and defaults to true
		String beanNameAttr = agentAnnotation != null ? readStringAttribute(agentAnnotation, "beanName") : "";
		String beanName = StringUtils.hasText(beanNameAttr) ? beanNameAttr : null;
		boolean scan = readBooleanAttributeWithDefault(source, "scan", true);
		String retryPolicy = agentAnnotation != null ? readNonDefaultRetryPolicy(agentAnnotation) : null;
		String retryExpression = agentAnnotation != null
				? emptyToNull(readStringAttribute(agentAnnotation, "actionRetryPolicyExpression")) : null;

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
					|| findAnnotation(m.getReturnType(), STATE_ANNOTATION_FQN) != null;
			List<String> possibleOutputs = (returnsStateType && !stateComponentTypes.isEmpty()) ? stateComponentTypes
					: null;
			steps.add(toStep(m, entry.getValue(), implicitInputs, possibleOutputs));
		}

		// Scan @State-annotated inner classes; pass their record components as implicit
		// inputs so that e.g. handleBilling(OperationContext) shows BillingTicket as
		// its input (because the BillingState record holds a BillingTicket field).
		for (Class<?> inner : targetType.getDeclaredClasses()) {
			if (findAnnotation(inner, STATE_ANNOTATION_FQN) != null) {
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
			.filter(inner -> findAnnotation(inner, STATE_ANNOTATION_FQN) != null && inner.isRecord())
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

		String name = firstNonBlank(readStringAttribute(primary, "name"), method.getName());
		String description = annotations.stream()
			.map(a -> readStringAttribute(a, "description"))
			.filter(StringUtils::hasText)
			.findFirst()
			.orElse("");

		List<String> pre = readStringArrayAttribute(primary, "pre");
		List<String> post = readStringArrayAttribute(primary, "post");

		// Explicit method parameters (minus framework types) + any implicit inputs
		// from an enclosing @State record's components.
		List<String> methodInputs = Arrays.stream(method.getParameterTypes())
			.filter(p -> !FRAMEWORK_PARAMETER_TYPES.contains(p.getName()))
			.map(Class::getSimpleName)
			.toList();
		List<String> inputs = implicitInputs.isEmpty() ? methodInputs
				: Stream.concat(implicitInputs.stream(), methodInputs.stream()).distinct().toList();

		String costMethod = readStringAttribute(primary, "costMethod");
		String valueMethod = readStringAttribute(primary, "valueMethod");

		// Static cost/value declared directly on @Action; the annotation default is
		// 0.0, which we map to null ("not set") so the UI only shows explicit values.
		// Only read from @Action — @AchievesGoal has its own (different) "value".
		boolean primaryIsAction = ACTION_ANNOTATION_FQN.equals(primary.annotationType().getName());
		Double cost = primaryIsAction ? readNonZeroDoubleAttribute(primary, "cost") : null;
		Double value = primaryIsAction ? readNonZeroDoubleAttribute(primary, "value") : null;

		// @Action-specific fields
		boolean canRerun = readBooleanAttribute(primary, "canRerun");
		boolean readOnly = readBooleanAttribute(primary, "readOnly");
		boolean clearBlackboard = readBooleanAttribute(primary, "clearBlackboard");
		String outputBinding = readStringAttribute(primary, "outputBinding");

		// @Action(trigger = SomeEvent.class): the action is event-triggered. The
		// Embabel
		// default is kotlin.Unit ("no trigger"), which we map to null.
		String trigger = primaryIsAction ? readClassSimpleNameAttribute(primary, "trigger") : null;
		// @Action(actionRetryPolicyExpression = "..."): per-action retry policy.
		String retryExpr = primaryIsAction ? readStringAttribute(primary, "actionRetryPolicyExpression") : "";
		String retryPolicy = StringUtils.hasText(retryExpr) ? retryExpr : null;
		String actionRetryPolicy = primaryIsAction ? readNonDefaultRetryPolicy(primary) : null;

		// @Condition(cost = ...), distinct from the @Action(cost = ...) read above
		Double conditionCost = null;
		for (Annotation a : annotations) {
			if (CONDITION_ANNOTATION_FQN.equals(a.annotationType().getName())) {
				conditionCost = readNonZeroDoubleAttribute(a, "cost");
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
				tags = readStringArrayAttribute(a, "tags");
				examples = readStringArrayAttribute(a, "examples");
				goalValue = readNonZeroDoubleAttribute(a, "value");
				Annotation export = readAnnotationAttribute(a, "export");
				if (export != null) {
					exportedRemote = readBooleanAttribute(export, "remote");
					exportedLocal = readBooleanAttributeWithDefault(export, "local", true);
					exportStartingInputTypes = readClassArraySimpleNames(export, "startingInputTypes");
					String name1 = readStringAttribute(export, "name");
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
					llmToolDescription = readStringAttribute(a, "description");
					if (!StringUtils.hasText(llmToolDescription))
						llmToolDescription = null;
					llmToolReturnDirect = readBooleanAttribute(a, "returnDirect");
					String category = readStringAttribute(a, "category");
					llmToolCategory = StringUtils.hasText(category) ? category : null;
					llmToolName = emptyToNull(readStringAttribute(a, "name"));
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
			.costMethod(emptyToNull(costMethod))
			.valueMethod(emptyToNull(valueMethod))
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

	private Annotation findAnnotation(Class<?> type, String annotationTypeName) {
		for (Annotation annotation : type.getAnnotations()) {
			if (annotation.annotationType().getName().equals(annotationTypeName)) {
				return annotation;
			}
		}
		return null;
	}

	private String readStringAttribute(Annotation annotation, String attributeName) {
		try {
			Method method = annotation.annotationType().getMethod(attributeName);
			Object value = method.invoke(annotation);
			return value instanceof String str ? str : "";
		}
		catch (ReflectiveOperationException ignored) {
			return "";
		}
	}

	private List<String> readStringArrayAttribute(Annotation annotation, String attributeName) {
		try {
			Method method = annotation.annotationType().getMethod(attributeName);
			Object value = method.invoke(annotation);
			if (value instanceof String[] array) {
				return Arrays.stream(array).filter(StringUtils::hasText).toList();
			}
			return List.of();
		}
		catch (ReflectiveOperationException ignored) {
			return List.of();
		}
	}

	private String firstNonBlank(String first, String fallback) {
		return StringUtils.hasText(first) ? first : fallback;
	}

	/**
	 * Reads an {@link Enum} attribute and returns its {@code toString()} (constant name),
	 * or empty string if the attribute does not exist or is not an enum.
	 */
	private String readEnumNameAttribute(Annotation annotation, String attributeName) {
		try {
			Method method = annotation.annotationType().getMethod(attributeName);
			Object value = method.invoke(annotation);
			return value != null ? value.toString() : "";
		}
		catch (ReflectiveOperationException ignored) {
			return "";
		}
	}

	/**
	 * Reads a {@code boolean} attribute from an annotation.
	 */
	private boolean readBooleanAttribute(Annotation annotation, String attributeName) {
		try {
			Method method = annotation.annotationType().getMethod(attributeName);
			Object value = method.invoke(annotation);
			return Boolean.TRUE.equals(value);
		}
		catch (ReflectiveOperationException ignored) {
			return false;
		}
	}

	/**
	 * Reads a {@code double} attribute from an annotation, returning {@code null} when
	 * the attribute is absent or left at the {@code 0.0} default (i.e. "not set").
	 */
	private Double readNonZeroDoubleAttribute(Annotation annotation, String attributeName) {
		try {
			Method method = annotation.annotationType().getMethod(attributeName);
			Object value = method.invoke(annotation);
			if (value instanceof Double d && d != 0.0) {
				return d;
			}
			return null;
		}
		catch (ReflectiveOperationException ignored) {
			return null;
		}
	}

	/**
	 * Reads a {@link Class}-valued annotation attribute and returns
	 * {@link Class#getSimpleName()}, or {@code null} when the attribute is absent or left
	 * at a "no value" sentinel ({@code kotlin.Unit}, {@code void}, or {@code Void}).
	 */
	private String readClassSimpleNameAttribute(Annotation annotation, String attributeName) {
		try {
			Method method = annotation.annotationType().getMethod(attributeName);
			Object value = method.invoke(annotation);
			if (value instanceof Class<?> type) {
				String name = type.getName();
				if ("kotlin.Unit".equals(name) || "void".equals(name) || "java.lang.Void".equals(name)) {
					return null;
				}
				return type.getSimpleName();
			}
			return null;
		}
		catch (ReflectiveOperationException ignored) {
			return null;
		}
	}

	/**
	 * Reads a nested annotation attribute (e.g. {@code @AchievesGoal(export = @Export)}).
	 */
	private Annotation readAnnotationAttribute(Annotation annotation, String attributeName) {
		try {
			Method method = annotation.annotationType().getMethod(attributeName);
			Object value = method.invoke(annotation);
			return value instanceof Annotation nested ? nested : null;
		}
		catch (ReflectiveOperationException ignored) {
			return null;
		}
	}

	private String emptyToNull(String value) {
		return StringUtils.hasText(value) ? value : null;
	}

	/**
	 * Reads a {@code boolean} attribute, falling back to {@code defaultValue} when the
	 * attribute is missing — used for attributes whose Embabel default is {@code true}.
	 */
	private boolean readBooleanAttributeWithDefault(Annotation annotation, String attributeName, boolean defaultValue) {
		try {
			Method method = annotation.annotationType().getMethod(attributeName);
			Object value = method.invoke(annotation);
			return value instanceof Boolean b ? b : defaultValue;
		}
		catch (ReflectiveOperationException ignored) {
			return defaultValue;
		}
	}

	/**
	 * Reads {@code actionRetryPolicy}, returning {@code null} when left at
	 * {@code ActionRetryPolicy.DEFAULT} so the UI only surfaces explicit policies.
	 */
	private String readNonDefaultRetryPolicy(Annotation annotation) {
		String policy = readEnumNameAttribute(annotation, "actionRetryPolicy");
		return StringUtils.hasText(policy) && !DEFAULT_RETRY_POLICY.equals(policy) ? policy : null;
	}

	/**
	 * Reads a {@code Class[]} attribute and returns each entry's
	 * {@link Class#getSimpleName()}.
	 */
	private List<String> readClassArraySimpleNames(Annotation annotation, String attributeName) {
		try {
			Method method = annotation.annotationType().getMethod(attributeName);
			Object value = method.invoke(annotation);
			if (value instanceof Class<?>[] classes) {
				return Arrays.stream(classes).map(Class::getSimpleName).toList();
			}
			return List.of();
		}
		catch (ReflectiveOperationException ignored) {
			return List.of();
		}
	}

	/**
	 * Reads {@code @LlmTool(metadata = {@literal @}Meta(key, value))} pairs.
	 */
	private List<ToolMetadata> readToolMetadata(Annotation annotation) {
		try {
			Method method = annotation.annotationType().getMethod("metadata");
			Object value = method.invoke(annotation);
			if (!(value instanceof Annotation[] entries)) {
				return List.of();
			}
			List<ToolMetadata> metadata = new ArrayList<>();
			for (Annotation entry : entries) {
				String key = readStringAttribute(entry, "key");
				if (StringUtils.hasText(key)) {
					metadata.add(new ToolMetadata(key, readStringAttribute(entry, "value")));
				}
			}
			return List.copyOf(metadata);
		}
		catch (ReflectiveOperationException ignored) {
			return List.of();
		}
	}

	/**
	 * Returns the simple type names of parameters carrying the given parameter-level
	 * annotation.
	 */
	private List<String> readParameterAnnotatedTypes(Method method, String annotationTypeName) {
		List<String> names = new ArrayList<>();
		for (Parameter parameter : method.getParameters()) {
			if (hasAnnotation(parameter, annotationTypeName)) {
				names.add(parameter.getType().getSimpleName());
			}
		}
		return List.copyOf(names);
	}

	/**
	 * Returns {@code @RequireNameMatch} parameters as {@code Type}, or
	 * {@code Type:boundName} when the annotation declares an explicit binding name.
	 */
	private List<String> readRequireNameMatchInputs(Method method) {
		List<String> names = new ArrayList<>();
		for (Parameter parameter : method.getParameters()) {
			for (Annotation annotation : parameter.getAnnotations()) {
				if (REQUIRE_NAME_MATCH_ANNOTATION_FQN.equals(annotation.annotationType().getName())) {
					String bound = readStringAttribute(annotation, "value");
					String type = parameter.getType().getSimpleName();
					names.add(StringUtils.hasText(bound) ? type + ":" + bound : type);
				}
			}
		}
		return List.copyOf(names);
	}

	private boolean hasAnnotation(Parameter parameter, String annotationTypeName) {
		for (Annotation annotation : parameter.getAnnotations()) {
			if (annotation.annotationType().getName().equals(annotationTypeName)) {
				return true;
			}
		}
		return false;
	}

}
