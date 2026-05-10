package com.patbaumgartner.embabel.workflow.visualizer;

import com.patbaumgartner.embabel.workflow.visualizer.WorkflowModels.AgentWorkflow;
import com.patbaumgartner.embabel.workflow.visualizer.WorkflowModels.WorkflowCatalog;
import com.patbaumgartner.embabel.workflow.visualizer.WorkflowModels.WorkflowStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Discovers Embabel agents in the Spring {@link ApplicationContext} and produces a
 * {@link WorkflowCatalog} describing their workflow.
 *
 * <p>
 * Discovery is purely reflective and never imports Embabel types directly so that the
 * starter remains usable without forcing the {@code embabel-agent-api} on the runtime
 * classpath.
 */
public class EmbabelWorkflowCatalogService {

	private static final Logger log = LoggerFactory.getLogger(EmbabelWorkflowCatalogService.class);

	private static final String AGENT_ANNOTATION_FQN = "com.embabel.agent.api.annotation.Agent";

	private static final String EMBABEL_COMPONENT_ANNOTATION_FQN = "com.embabel.agent.api.annotation.EmbabelComponent";

	private static final String ACTION_ANNOTATION_FQN = "com.embabel.agent.api.annotation.Action";

	private static final String ACHIEVES_GOAL_ANNOTATION_FQN = "com.embabel.agent.api.annotation.AchievesGoal";

	private static final String STATE_ANNOTATION_FQN = "com.embabel.agent.api.annotation.State";

	private static final String LLM_TOOL_ANNOTATION_FQN = "com.embabel.agent.api.annotation.LlmTool";

	private static final Set<String> STEP_ANNOTATION_FQNS = Set.of(ACTION_ANNOTATION_FQN, ACHIEVES_GOAL_ANNOTATION_FQN,
			"com.embabel.agent.api.annotation.Condition", "com.embabel.agent.api.annotation.Cost",
			"com.embabel.agent.api.annotation.Goal", LLM_TOOL_ANNOTATION_FQN);

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
		// singletons
		// and factory beans (e.g. ChatClient) just to determine whether they match
		// Object.class — which can fail when required dependencies (ChatModel, etc.)
		// are not available in the current profile / environment.
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
		// plannerType: read enum constant name from @Agent; mark @EmbabelComponent as
		// "COMPONENT"
		String plannerType = agentAnnotation != null ? readEnumNameAttribute(agentAnnotation, "planner") : "COMPONENT";
		boolean opaque = agentAnnotation != null && readBooleanAttribute(agentAnnotation, "opaque");

		List<WorkflowStep> steps = collectSteps(targetType);
		return Optional
			.of(new AgentWorkflow(agentName, description, version, plannerType, opaque, targetType.getName(), steps));
	}

	private List<WorkflowStep> collectSteps(Class<?> targetType) {
		return collectStepsInternal(targetType, List.of(), List.of());
	}

	private List<WorkflowStep> collectStepsInternal(Class<?> targetType, List<String> implicitInputs,
			List<String> possibleOutputsForObjectReturn) {
		// Group annotations by method so a method with both @Action and @AchievesGoal
		// becomes a single enriched step, not two duplicates.
		Map<Method, List<Annotation>> grouped = new LinkedHashMap<>();
		for (Method method : targetType.getDeclaredMethods()) {
			if (method.isSynthetic() || method.isBridge()) {
				continue;
			}
			for (Annotation annotation : method.getAnnotations()) {
				if (STEP_ANNOTATION_FQNS.contains(annotation.annotationType().getName())) {
					grouped.computeIfAbsent(method, m -> new ArrayList<>()).add(annotation);
				}
			}
		}

		// Compute @State component types so that steps returning Object can expose
		// the concrete types the @State routing actually produces.
		List<String> stateComponentTypes = stateComponentTypesOf(targetType);

		List<WorkflowStep> steps = new ArrayList<>();
		for (Map.Entry<Method, List<Annotation>> entry : grouped.entrySet()) {
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
				List<String> stateInputs = recordComponentSimpleNames(inner);
				steps.addAll(collectStepsInternal(inner, stateInputs, List.of()));
			}
		}

		steps.sort(Comparator.comparing(WorkflowStep::name, String.CASE_INSENSITIVE_ORDER));
		return steps;
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

		String output = method.getReturnType().getSimpleName();

		String costMethod = readStringAttribute(primary, "costMethod");
		String valueMethod = readStringAttribute(primary, "valueMethod");

		// @Action-specific fields
		boolean canRerun = readBooleanAttribute(primary, "canRerun");
		boolean readOnly = readBooleanAttribute(primary, "readOnly");
		boolean clearBlackboard = readBooleanAttribute(primary, "clearBlackboard");
		String outputBinding = readStringAttribute(primary, "outputBinding");

		// @AchievesGoal-specific fields
		List<String> tags = List.of();
		List<String> examples = List.of();
		for (Annotation a : annotations) {
			if (ACHIEVES_GOAL_ANNOTATION_FQN.equals(a.annotationType().getName())) {
				tags = readStringArrayAttribute(a, "tags");
				examples = readStringArrayAttribute(a, "examples");
			}
		}

		// @LlmTool-specific fields
		String llmToolDescription = null;
		if (llmTool) {
			for (Annotation a : annotations) {
				if (LLM_TOOL_ANNOTATION_FQN.equals(a.annotationType().getName())) {
					llmToolDescription = readStringAttribute(a, "description");
					if (!StringUtils.hasText(llmToolDescription))
						llmToolDescription = null;
					// Use @LlmTool description as step description if no other
					// description
					if (!StringUtils.hasText(description) && StringUtils.hasText(llmToolDescription)) {
						description = llmToolDescription;
					}
					break;
				}
			}
		}

		return new WorkflowStep(name, type, description, method.getName(), pre, post, inputs, output, achievesGoal,
				costMethod.isEmpty() ? null : costMethod, valueMethod.isEmpty() ? null : valueMethod, possibleOutputs,
				canRerun, readOnly, outputBinding.isEmpty() ? null : outputBinding, clearBlackboard, tags, examples,
				llmTool, llmToolDescription);
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

}
