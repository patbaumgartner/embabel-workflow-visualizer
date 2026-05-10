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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Discovers Embabel agents in the Spring {@link ApplicationContext} and
 * produces a
 * {@link WorkflowCatalog} describing their workflow.
 *
 * <p>
 * Discovery is purely reflective and never imports Embabel types directly so
 * that the
 * starter remains usable without forcing the {@code embabel-agent-api} on the
 * runtime
 * classpath.
 */
public class EmbabelWorkflowCatalogService {

    private static final Logger log = LoggerFactory.getLogger(EmbabelWorkflowCatalogService.class);

    private static final String AGENT_ANNOTATION_FQN = "com.embabel.agent.api.annotation.Agent";

    private static final String EMBABEL_COMPONENT_ANNOTATION_FQN = "com.embabel.agent.api.annotation.EmbabelComponent";

    private static final String ACTION_ANNOTATION_FQN = "com.embabel.agent.api.annotation.Action";

    private static final String ACHIEVES_GOAL_ANNOTATION_FQN = "com.embabel.agent.api.annotation.AchievesGoal";

    private static final Set<String> STEP_ANNOTATION_FQNS = Set.of(ACTION_ANNOTATION_FQN, ACHIEVES_GOAL_ANNOTATION_FQN,
            "com.embabel.agent.api.annotation.Condition", "com.embabel.agent.api.annotation.Cost",
            "com.embabel.agent.api.annotation.Goal");

    /** Framework parameter types that should not be reported as workflow inputs. */
    private static final Set<String> FRAMEWORK_PARAMETER_TYPES = Set.of("com.embabel.agent.api.common.OperationContext",
            "com.embabel.agent.api.common.ActionContext", "com.embabel.agent.core.AgentProcess");

    private final ApplicationContext applicationContext;

    public EmbabelWorkflowCatalogService(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    /**
     * Build the catalog. Each bean is processed independently; failures are logged
     * and
     * do not abort the scan.
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
        } catch (RuntimeException ex) {
            log.warn("Failed to enumerate beans for Embabel workflow catalog", ex);
            return new WorkflowCatalog(List.of());
        }

        List<AgentWorkflow> agents = new ArrayList<>();
        for (Object bean : beans.values()) {
            try {
                toAgentWorkflow(bean).ifPresent(agents::add);
            } catch (Throwable t) {
                log.warn("Skipping bean {} while scanning Embabel agents: {}", bean.getClass().getName(), t.toString());
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
                ? findAnnotation(targetType, EMBABEL_COMPONENT_ANNOTATION_FQN)
                : null;
        if (agentAnnotation == null && componentAnnotation == null) {
            return Optional.empty();
        }

        Annotation source = agentAnnotation != null ? agentAnnotation : componentAnnotation;
        String agentName = firstNonBlank(readStringAttribute(source, "name"), targetType.getSimpleName());
        String description = readStringAttribute(source, "description");
        String version = readStringAttribute(source, "version");

        List<WorkflowStep> steps = collectSteps(targetType);
        return Optional.of(new AgentWorkflow(agentName, description, version, targetType.getName(), steps));
    }

    private List<WorkflowStep> collectSteps(Class<?> targetType) {
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

        List<WorkflowStep> steps = new ArrayList<>();
        for (Map.Entry<Method, List<Annotation>> entry : grouped.entrySet()) {
            steps.add(toStep(entry.getKey(), entry.getValue()));
        }
        steps.sort(Comparator.comparing(WorkflowStep::name, String.CASE_INSENSITIVE_ORDER));
        return steps;
    }

    private WorkflowStep toStep(Method method, List<Annotation> annotations) {
        Annotation primary = annotations.stream()
                .filter(a -> ACTION_ANNOTATION_FQN.equals(a.annotationType().getName()))
                .findFirst()
                .orElse(annotations.get(0));

        boolean achievesGoal = annotations.stream()
                .anyMatch(a -> ACHIEVES_GOAL_ANNOTATION_FQN.equals(a.annotationType().getName()));

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

        List<String> inputs = Arrays.stream(method.getParameterTypes())
                .filter(p -> !FRAMEWORK_PARAMETER_TYPES.contains(p.getName()))
                .map(Class::getSimpleName)
                .toList();

        String output = method.getReturnType().getSimpleName();

        return new WorkflowStep(name, type, description, method.getName(), pre, post, inputs, output, achievesGoal);
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
        } catch (ReflectiveOperationException ignored) {
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
        } catch (ReflectiveOperationException ignored) {
            return List.of();
        }
    }

    private String firstNonBlank(String first, String fallback) {
        return StringUtils.hasText(first) ? first : fallback;
    }

}
