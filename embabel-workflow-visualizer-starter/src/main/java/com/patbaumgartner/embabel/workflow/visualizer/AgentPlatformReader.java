package com.patbaumgartner.embabel.workflow.visualizer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads the agents Embabel actually registered from a live {@code AgentPlatform}.
 *
 * <p>
 * The annotation scan describes what an author <em>declared</em>; this describes what the
 * planner <em>runs</em>, and the two genuinely differ. A {@code SUPERVISOR} agent's
 * declared actions become tools of one synthetic supervisor action, a {@code UTILITY}
 * agent gains a synthetic goal, and an agent assembled in code has no annotations at all.
 *
 * <p>
 * Like the rest of the starter this never imports an Embabel type: the platform is
 * located by class name and read by method name, so the visualizer keeps working against
 * Embabel versions it was not built against, and simply reports nothing when the platform
 * is absent, not yet created, or shaped differently than expected.
 */
class AgentPlatformReader {

	private static final Logger log = LoggerFactory.getLogger(AgentPlatformReader.class);

	private static final String AGENT_PLATFORM_FQN = "com.embabel.agent.core.AgentPlatform";

	/**
	 * Planner bookkeeping that appears in runtime pre-conditions and effects. Embabel
	 * adds {@code hasRun_<action>} to sequence actions and {@code __unobtanium__} to make
	 * a goal deliberately unreachable; neither describes the authored workflow.
	 */
	private static final String HAS_RUN_PREFIX = "hasRun_";

	private static final String UNOBTAINABLE = "__unobtanium__";

	/**
	 * JVM array-element codes, which appear in a binding naming an array of primitives.
	 */
	private static final Map<String, String> PRIMITIVE_DESCRIPTORS = Map.of("B", "byte", "C", "char", "D", "double",
			"F", "float", "I", "int", "J", "long", "S", "short", "Z", "boolean");

	private final ApplicationContext applicationContext;

	AgentPlatformReader(ApplicationContext applicationContext) {
		this.applicationContext = applicationContext;
	}

	/**
	 * The registered agents, or an empty list when no usable platform is available.
	 *
	 * <p>
	 * Only an already-created platform is read. Like the annotation scan, this must not
	 * be the thing that brings a bean to life.
	 */
	List<RuntimeAgent> readAgents() {
		try {
			return platform().map(this::readAgentsFrom).orElseGet(List::of);
		}
		catch (Exception | LinkageError ex) {
			log.warn("Could not read the Embabel agent platform; reporting declared workflows only", ex);
			return List.of();
		}
	}

	private List<String> platformBeanNames() {
		if (!ClassUtils.isPresent(AGENT_PLATFORM_FQN, this.applicationContext.getClassLoader())) {
			return List.of();
		}
		try {
			Class<?> platformType = ClassUtils.resolveClassName(AGENT_PLATFORM_FQN,
					this.applicationContext.getClassLoader());
			return List.of(this.applicationContext.getBeanNamesForType(platformType, false, false));
		}
		catch (Exception | LinkageError ex) {
			return List.of();
		}
	}

	private Optional<Object> platform() {
		if (!(this.applicationContext instanceof ConfigurableApplicationContext configurable)) {
			return Optional.empty();
		}
		for (String beanName : platformBeanNames()) {
			Object existing = configurable.getBeanFactory().getSingleton(beanName);
			if (existing != null) {
				return Optional.of(existing);
			}
		}
		return Optional.empty();
	}

	/**
	 * Reads a platform-shaped object. Package-private so tests can exercise the
	 * reflective reading without booting an agent platform.
	 */
	List<RuntimeAgent> readAgentsFrom(Object platform) {
		List<RuntimeAgent> agents = new ArrayList<>();
		for (Object agent : asCollection(invoke(platform, "agents"))) {
			List<RuntimeStep> steps = new ArrayList<>();
			asCollection(invoke(agent, "getActions")).forEach(action -> steps.add(toAction(action)));
			asCollection(invoke(agent, "getGoals")).forEach(goal -> steps.add(toGoal(goal)));
			asCollection(invoke(agent, "getConditions")).forEach(condition -> steps.add(toCondition(condition)));
			agents.add(new RuntimeAgent(string(invoke(agent, "getName")), string(invoke(agent, "getDescription")),
					string(invoke(agent, "getProvider")), Boolean.TRUE.equals(invoke(agent, "getOpaque")),
					List.copyOf(steps)));
		}
		return List.copyOf(agents);
	}

	private RuntimeStep toAction(Object action) {
		List<String> outputs = bindingTypes(invoke(action, "getOutputs"));
		return new RuntimeStep(string(invoke(action, "getName")), "Action", string(invoke(action, "getDescription")),
				bindingTypes(invoke(action, "getInputs")), outputs.isEmpty() ? "void" : outputs.get(0),
				conditionNames(invoke(action, "getPreconditions")), conditionNames(invoke(action, "getEffects")),
				Boolean.TRUE.equals(invoke(action, "getCanRerun")), Boolean.TRUE.equals(invoke(action, "getReadOnly")));
	}

	private RuntimeStep toGoal(Object goal) {
		Object outputType = invoke(goal, "getOutputType");
		String output = outputType == null ? "void" : simpleTypeName(string(invoke(outputType, "getName")));
		return new RuntimeStep(string(invoke(goal, "getName")), "AchievesGoal", string(invoke(goal, "getDescription")),
				List.of(), output, conditionNames(invoke(goal, "getPre")), List.of(), false, false);
	}

	private RuntimeStep toCondition(Object condition) {
		return new RuntimeStep(string(invoke(condition, "getName")), "Condition",
				string(invoke(condition, "getDescription")), List.of(), "boolean", List.of(), List.of(), false, false);
	}

	/**
	 * Turns Embabel's {@code IoBinding} values — {@code "bindingName:fully.Qualified"} —
	 * into the simple type names the rest of the catalog speaks in.
	 */
	private List<String> bindingTypes(Object bindings) {
		List<String> types = new ArrayList<>();
		for (Object binding : asCollection(bindings)) {
			String value = string(invoke(binding, "getValue"));
			int separator = value.indexOf(':');
			String type = separator < 0 ? value : value.substring(separator + 1);
			if (!type.isEmpty()) {
				types.add(simpleTypeName(type));
			}
		}
		return List.copyOf(types);
	}

	/**
	 * Keeps the named conditions from a runtime pre-condition or effect map and drops the
	 * planner's own bookkeeping — {@code hasRun_*}, {@code __unobtanium__}, and the
	 * {@code binding:type} entries that merely restate the step's inputs and outputs.
	 */
	private List<String> conditionNames(Object conditions) {
		Collection<?> names = conditions instanceof Map<?, ?> map ? map.keySet() : asCollection(conditions);
		return names.stream()
			.map(String::valueOf)
			.filter(name -> !name.startsWith(HAS_RUN_PREFIX) && !name.equals(UNOBTAINABLE) && name.indexOf(':') < 0)
			.toList();
	}

	private Object invoke(Object target, String methodName) {
		if (target == null) {
			return null;
		}
		try {
			Method method = ClassUtils.getInterfaceMethodIfPossible(target.getClass().getMethod(methodName),
					target.getClass());
			return method.invoke(target);
		}
		catch (ReflectiveOperationException | RuntimeException ex) {
			return null;
		}
	}

	private Collection<?> asCollection(Object value) {
		return value instanceof Collection<?> collection ? collection : List.of();
	}

	private String string(Object value) {
		return value instanceof String text ? text : "";
	}

	/** Embabel qualifies runtime names, e.g. {@code com.foo.MyAgent.myAction}. */
	static String simpleName(String qualifiedName) {
		int lastDot = qualifiedName.lastIndexOf('.');
		return lastDot < 0 ? qualifiedName : qualifiedName.substring(lastDot + 1);
	}

	/**
	 * Simple name of a type, nested types and arrays included: a runtime binding names
	 * them the way the JVM does ({@code com.foo.Models$Request},
	 * {@code [Lcom.foo.Order;}), while the annotation scan reports
	 * {@link Class#getSimpleName()} ({@code Request}, {@code Order[]}). They have to
	 * agree, or the diagram fails to connect a runtime step to the declared step
	 * producing its input.
	 */
	private static String simpleTypeName(String qualifiedType) {
		int dimensions = 0;
		String type = qualifiedType;
		while (type.startsWith("[")) {
			dimensions++;
			type = type.substring(1);
		}
		if (dimensions > 0) {
			type = type.startsWith("L") && type.endsWith(";") ? type.substring(1, type.length() - 1)
					: PRIMITIVE_DESCRIPTORS.getOrDefault(type, type);
		}
		String afterPackage = simpleName(type);
		int lastNested = afterPackage.lastIndexOf('$');
		return (lastNested < 0 ? afterPackage : afterPackage.substring(lastNested + 1)) + "[]".repeat(dimensions);
	}

	/** An agent as the platform registered it. */
	record RuntimeAgent(String name, String description, String provider, boolean opaque, List<RuntimeStep> steps) {
	}

	/** An action, goal, or condition as the planner sees it. */
	record RuntimeStep(String name, String type, String description, List<String> inputs, String output,
			List<String> pre, List<String> post, boolean canRerun, boolean readOnly) {

		/** Name without Embabel's qualifier, for matching against a declared step. */
		String simpleStepName() {
			return AgentPlatformReader.simpleName(this.name);
		}
	}

}
