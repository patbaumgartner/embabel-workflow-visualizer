package com.patbaumgartner.embabel.workflow.visualizer;

import com.patbaumgartner.embabel.workflow.visualizer.AgentPlatformReader.RuntimeAgent;
import com.patbaumgartner.embabel.workflow.visualizer.AgentPlatformReader.RuntimeStep;
import com.patbaumgartner.embabel.workflow.visualizer.WorkflowModels.AgentWorkflow;
import com.patbaumgartner.embabel.workflow.visualizer.WorkflowModels.WorkflowStep;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Marks the declared workflows up with what a live {@code AgentPlatform} actually runs.
 *
 * <p>
 * Annotations describe intent; the planner decides what runs, and the two genuinely
 * differ. This is the only place that knows how, which keeps the annotation scan free of
 * planner semantics and this free of reflection.
 */
class RuntimeWorkflowReconciler {

	/**
	 * Step types the planner registers. A {@code @Cost} function and an {@code @LlmTool}
	 * are deliberately not plan steps — flagging every one of them as missing from the
	 * plan would bury the case that actually matters, a declared action the planner does
	 * not run.
	 */
	private static final Set<String> PLANNABLE_STEP_TYPES = Set.of("Action", "AchievesGoal", "Condition");

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
	List<AgentWorkflow> reconcile(List<AgentWorkflow> declared, List<RuntimeAgent> runtimeAgents) {
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
		return agent.toBuilder().steps(steps).registered(registered).build();
	}

	private WorkflowStep withRegistered(WorkflowStep step, Boolean registered) {
		return step.toBuilder().registered(registered).build();
	}

	private String emptyToNullValue(String value) {
		return StringUtils.hasText(value) ? value : null;
	}

}
