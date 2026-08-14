package com.patbaumgartner.embabel.workflow.visualizer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Stand-ins shaped like Embabel's runtime API, for tests that must not depend on booting
 * a real agent platform.
 *
 * <p>
 * The reader locates the platform by class name and reads it by method name, so a type
 * with the same method names exercises exactly the same code path. What these fakes
 * cannot prove is that the shape still matches Embabel — that is what
 * {@code EmbabelWorkflowCatalogRuntimeIntegrationTests} in the sample application is for.
 */
final class FakeAgentPlatform {

	private FakeAgentPlatform() {
	}

	/** Mirrors {@code AgentPlatform.agents()}; the record accessor is the method read. */
	record Platform(List<Agent> agents) {
	}

	/** Mirrors {@code com.embabel.agent.core.Agent}. */
	record Agent(String name, String description, String provider, boolean opaque, List<Action> actions,
			Set<Goal> goals, Set<Condition> conditions) {

		static Agent named(String name, List<Action> actions, Set<Goal> goals) {
			return new Agent(name, "", "embabel", false, actions, goals, Set.of());
		}

		public String getName() {
			return this.name;
		}

		public String getDescription() {
			return this.description;
		}

		public String getProvider() {
			return this.provider;
		}

		public boolean getOpaque() {
			return this.opaque;
		}

		public List<Action> getActions() {
			return this.actions;
		}

		public Set<Goal> getGoals() {
			return this.goals;
		}

		public Set<Condition> getConditions() {
			return this.conditions;
		}
	}

	/** Mirrors {@code com.embabel.agent.core.Action}. */
	record Action(String name, String description, Set<IoBinding> inputs, Set<IoBinding> outputs,
			Map<String, String> preconditions, Map<String, String> effects, boolean canRerun, boolean readOnly) {

		static Action named(String name) {
			return new Action(name, "", Set.of(), Set.of(), Map.of(), Map.of(), false, false);
		}

		public String getName() {
			return this.name;
		}

		public String getDescription() {
			return this.description;
		}

		public Set<IoBinding> getInputs() {
			return this.inputs;
		}

		public Set<IoBinding> getOutputs() {
			return this.outputs;
		}

		public Map<String, String> getPreconditions() {
			return this.preconditions;
		}

		public Map<String, String> getEffects() {
			return this.effects;
		}

		public boolean getCanRerun() {
			return this.canRerun;
		}

		public boolean getReadOnly() {
			return this.readOnly;
		}
	}

	/** Mirrors {@code com.embabel.agent.core.Goal}. */
	record Goal(String name, String description, Set<String> pre, DomainType outputType) {

		static Goal named(String name) {
			return new Goal(name, "", Set.of(), null);
		}

		public String getName() {
			return this.name;
		}

		public String getDescription() {
			return this.description;
		}

		public Set<String> getPre() {
			return this.pre;
		}

		public DomainType getOutputType() {
			return this.outputType;
		}
	}

	/** Mirrors {@code com.embabel.agent.core.Condition}. */
	record Condition(String name, String description) {

		public String getName() {
			return this.name;
		}

		public String getDescription() {
			return this.description;
		}
	}

	/** Mirrors {@code com.embabel.agent.core.DomainType}. */
	record DomainType(String name) {

		public String getName() {
			return this.name;
		}
	}

	/**
	 * Mirrors {@code com.embabel.agent.core.IoBinding}, whose value is
	 * {@code "bindingName:fully.Qualified.Type"}.
	 */
	record IoBinding(String value) {

		static Set<IoBinding> of(String... values) {
			Map<String, IoBinding> ordered = new LinkedHashMap<>();
			for (String value : values) {
				ordered.put(value, new IoBinding(value));
			}
			return Set.copyOf(ordered.values());
		}

		public String getValue() {
			return this.value;
		}
	}

}
