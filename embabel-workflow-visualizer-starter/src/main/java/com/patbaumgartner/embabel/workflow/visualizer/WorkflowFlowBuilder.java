package com.patbaumgartner.embabel.workflow.visualizer;

import com.patbaumgartner.embabel.workflow.visualizer.WorkflowModels.AgentWorkflow;
import com.patbaumgartner.embabel.workflow.visualizer.WorkflowModels.FlowEdge;
import com.patbaumgartner.embabel.workflow.visualizer.WorkflowModels.FlowNode;
import com.patbaumgartner.embabel.workflow.visualizer.WorkflowModels.FlowPath;
import com.patbaumgartner.embabel.workflow.visualizer.WorkflowModels.WorkflowFlow;
import com.patbaumgartner.embabel.workflow.visualizer.WorkflowModels.WorkflowStep;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Derives an agent's {@link WorkflowFlow}: every route the planner can take from the
 * entry types to a goal, merged into one flow chart.
 *
 * <p>
 * Embabel plans backwards from a goal — an action is worth running when something later
 * in the plan needs what it produces — and re-plans after every step, so no single
 * sequence is <em>the</em> plan. The derivation follows the same rule statically: from
 * each goal, every input is traced to the actions that produce it and every precondition
 * to the actions that post it, branching wherever more than one action would do. What
 * comes out is the set of minimal plans a planner could pick between. Their union is the
 * chart; where they diverge is a decision; where one plan needs several independent
 * actions is a fork and a join; and their declared costs, summed, say which one GOAP
 * prefers.
 *
 * <p>
 * A goal counts as achieved the way Embabel counts it: when an instance of the goal
 * action's output type is on the blackboard. Any action producing that type therefore
 * ends a route, which is how a {@code SUPERVISOR} agent's synthetic action reaches the
 * goal its declared actions were written for.
 *
 * <p>
 * What the planner can only know at runtime stays out of the derivation and on the chart:
 * a {@code @Condition} nobody posts and a SpEL expression are kept as labels on the edges
 * they guard, and a {@code canRerun} action whose output feeds an earlier step is drawn
 * as a loop rather than unrolled.
 */
final class WorkflowFlowBuilder {

	/**
	 * Enumeration stops here. Plans multiply with every input that has several producers
	 * and with every optional input, so an agent written to be flexible can admit more
	 * routes than anyone would read; {@link WorkflowFlow#truncated()} says when the
	 * picture is partial.
	 */
	static final int MAX_PATHS = 100;

	static final String START_ID = "start";

	private static final String SPEL_PREFIX = "spel:";

	/**
	 * The step types the planner schedules; conditions, cost functions and tools are not.
	 */
	private static final Set<String> PLAN_STEP_TYPES = Set.of("Action", "AchievesGoal");

	WorkflowFlow build(AgentWorkflow agent) {
		return new Derivation(agent.steps()).derive();
	}

	private enum DependencyKind {

		TYPE, STATE, CONDITION

	}

	/** One reason an action is in a plan: {@code to} needs what {@code from} provides. */
	private record Dependency(String from, String to, String label, DependencyKind kind) {
	}

	/**
	 * A minimal set of actions that achieves one goal, with the reasons each is there.
	 */
	private static final class Plan {

		final String root;

		final String goal;

		final LinkedHashSet<String> actions;

		final List<Dependency> dependencies;

		/** Conditions an action needs that no action in this plan establishes. */
		final Map<String, LinkedHashSet<String>> guards;

		Plan(String root, String goal) {
			this.root = root;
			this.goal = goal;
			this.actions = new LinkedHashSet<>();
			this.dependencies = new ArrayList<>();
			this.guards = new LinkedHashMap<>();
		}

		Plan(Plan other) {
			this.root = other.root;
			this.goal = other.goal;
			this.actions = new LinkedHashSet<>(other.actions);
			this.dependencies = new ArrayList<>(other.dependencies);
			this.guards = new LinkedHashMap<>();
			other.guards.forEach((action, conditions) -> this.guards.put(action, new LinkedHashSet<>(conditions)));
		}

		Plan with(Dependency dependency) {
			Plan copy = new Plan(this);
			copy.dependencies.add(dependency);
			return copy;
		}

		Plan guarded(String action, String condition) {
			Plan copy = new Plan(this);
			copy.guards.computeIfAbsent(action, key -> new LinkedHashSet<>()).add(condition);
			return copy;
		}

	}

	/** An edge of the chart while it is being assembled. */
	private static final class Link {

		String from;

		String to;

		final LinkedHashSet<String> types = new LinkedHashSet<>();

		final LinkedHashSet<String> stateTypes = new LinkedHashSet<>();

		final LinkedHashSet<String> conditions = new LinkedHashSet<>();

		final TreeSet<Integer> paths = new TreeSet<>();

		boolean loop;

		Link(String from, String to) {
			this.from = from;
			this.to = to;
		}

		FlowEdge toEdge() {
			return new FlowEdge(this.from, this.to, List.copyOf(this.types), List.copyOf(this.conditions), this.loop,
					List.copyOf(this.paths));
		}

	}

	private record RankedPlan(Plan plan, Map<String, Link> links, FlowPath path) {
	}

	private static final class Derivation {

		private final List<WorkflowStep> steps;

		/** The steps the planner can schedule, by name. */
		private final Map<String, WorkflowStep> actions = new LinkedHashMap<>();

		private final Set<String> entryTypes;

		private boolean truncated;

		Derivation(List<WorkflowStep> steps) {
			this.steps = steps;
			for (WorkflowStep step : steps) {
				// A step the platform declined to register is not the planner's to run.
				if (PLAN_STEP_TYPES.contains(step.type()) && !Boolean.FALSE.equals(step.registered())) {
					this.actions.putIfAbsent(step.name(), step);
				}
			}
			this.entryTypes = entryTypes();
		}

		/**
		 * The types a caller has to supply: an exported goal's declared
		 * {@code startingInputTypes} when there are any, otherwise every input no action
		 * produces.
		 */
		private Set<String> entryTypes() {
			TreeSet<String> declared = new TreeSet<>();
			this.actions.values().forEach(action -> declared.addAll(action.exportStartingInputTypes()));
			if (!declared.isEmpty()) {
				return declared;
			}
			TreeSet<String> unproduced = new TreeSet<>();
			for (WorkflowStep action : this.actions.values()) {
				for (String type : requiredInputs(action)) {
					if (producersOf(type).isEmpty()) {
						unproduced.add(type);
					}
				}
			}
			return unproduced;
		}

		WorkflowFlow derive() {
			List<RankedPlan> ranked = new ArrayList<>();
			// Two producers of the same two types reach one action set by different
			// routes; once reduced to edges the plans are the same and are shown once.
			Set<String> seen = new HashSet<>();
			for (Plan plan : enumeratePlans()) {
				Map<String, Link> links = reducedLinks(plan);
				FlowPath path = toPath(plan, links);
				if (seen.add(path.goal() + '|' + path.steps() + '|' + new TreeSet<>(links.keySet()))) {
					ranked.add(new RankedPlan(plan, links, path));
				}
			}
			ranked.sort(Comparator.comparing((RankedPlan r) -> r.path().goal())
				.thenComparing(r -> costOrZero(r.path()))
				.thenComparing(r -> r.path().steps().size())
				.thenComparing(r -> String.join(",", r.path().steps())));
			List<FlowPath> paths = markCheapest(ranked.stream().map(RankedPlan::path).toList());

			List<FlowNode> nodes = new ArrayList<>();
			List<Link> links = mergePlans(ranked);
			Set<String> inFlow = ranked.stream()
				.flatMap(r -> r.plan().actions.stream())
				.collect(Collectors.toCollection(LinkedHashSet::new));
			Map<String, Set<Integer>> pathsOf = pathsByNode(ranked);

			nodes.add(new FlowNode(START_ID, "START", null, null, null));
			this.actions.keySet()
				.stream()
				.filter(inFlow::contains)
				.forEach(name -> nodes.add(new FlowNode(stepId(name), "ACTION", name, null, null)));
			insertSplits(links, nodes, pathsOf);
			insertJoins(links, nodes, pathsOf);
			ranked.stream()
				.map(r -> r.plan().goal)
				.distinct()
				.forEach(goal -> nodes
					.add(new FlowNode(endId(goal), "END", goal, this.actions.get(goal).output(), null)));
			addLoops(links, inFlow);
			nodes.addAll(detachedNodes(inFlow));

			return new WorkflowFlow(List.copyOf(this.entryTypes), nodes, links.stream().map(Link::toEdge).toList(),
					paths, this.truncated);
		}

		// -- Plan enumeration -------------------------------------------------------

		/**
		 * Every minimal plan, found the way GOAP finds one: from the goal, backwards.
		 * Every action producing a goal's output type roots a plan of its own, except a
		 * goal action reaching another goal's type, which is that goal's business.
		 */
		private List<Plan> enumeratePlans() {
			List<Plan> plans = new ArrayList<>();
			for (WorkflowStep goal : this.actions.values()) {
				if (!goal.goal() || "void".equals(goal.output())) {
					continue;
				}
				for (WorkflowStep root : producersOf(goal.output())) {
					if (!root.output().equals(goal.output()) || (root.goal() && root != goal)) {
						continue;
					}
					for (Plan plan : expand(new Plan(root.name(), goal.name()), root, new ArrayDeque<>())) {
						if (plans.size() >= MAX_PATHS) {
							this.truncated = true;
							return plans;
						}
						plans.add(plan);
					}
				}
			}
			return plans;
		}

		/**
		 * Adds {@code action} to the plan and resolves what it needs, returning one plan
		 * per way of resolving it. {@code stack} holds the actions whose needs are being
		 * resolved right now: an action cannot be produced by something that first needs
		 * the action's own result.
		 */
		private List<Plan> expand(Plan base, WorkflowStep action, Deque<String> stack) {
			if (base.actions.contains(action.name())) {
				return List.of(base);
			}
			Plan plan = new Plan(base);
			plan.actions.add(action.name());
			stack.push(action.name());
			List<Plan> variants = List.of(plan);
			for (String type : requiredInputs(action)) {
				variants = resolveEach(variants, v -> resolveInput(v, action, type, false, stack));
			}
			for (String type : optionalInputs(action)) {
				variants = resolveEach(variants, v -> resolveInput(v, action, type, true, stack));
			}
			for (String condition : action.pre()) {
				variants = resolveEach(variants, v -> resolveCondition(v, action, condition, stack));
			}
			stack.pop();
			return variants;
		}

		private List<Plan> resolveEach(List<Plan> variants, Function<Plan, List<Plan>> resolver) {
			List<Plan> resolved = new ArrayList<>();
			for (Plan variant : variants) {
				resolved.addAll(resolver.apply(variant));
				if (resolved.size() > MAX_PATHS) {
					this.truncated = true;
					return List.copyOf(resolved.subList(0, MAX_PATHS));
				}
			}
			return resolved;
		}

		/**
		 * An input is satisfied by the caller when it is an entry type, by nothing when
		 * it is optional, and otherwise by each action that produces it — one plan each.
		 */
		private List<Plan> resolveInput(Plan plan, WorkflowStep consumer, String type, boolean optional,
				Deque<String> stack) {
			List<Plan> resolved = new ArrayList<>();
			if (optional || this.entryTypes.contains(type)) {
				resolved.add(plan);
			}
			for (WorkflowStep producer : producersOf(type)) {
				if (stack.contains(producer.name())) {
					continue;
				}
				DependencyKind kind = producer.output().equals(type) ? DependencyKind.TYPE : DependencyKind.STATE;
				for (Plan expanded : expand(plan, producer, stack)) {
					resolved.add(expanded.with(new Dependency(producer.name(), consumer.name(), type, kind)));
				}
			}
			return resolved;
		}

		/**
		 * A precondition some action posts is planned for by running that action. One
		 * nothing posts — a {@code @Condition} evaluated against the blackboard, or a
		 * SpEL expression — cannot be planned for, only checked, so it stays as a guard.
		 */
		private List<Plan> resolveCondition(Plan plan, WorkflowStep consumer, String condition, Deque<String> stack) {
			List<Plan> resolved = new ArrayList<>();
			if (!condition.startsWith(SPEL_PREFIX)) {
				for (WorkflowStep poster : postersOf(condition)) {
					if (stack.contains(poster.name())) {
						continue;
					}
					for (Plan expanded : expand(plan, poster, stack)) {
						resolved.add(expanded
							.with(new Dependency(poster.name(), consumer.name(), condition, DependencyKind.CONDITION)));
					}
				}
			}
			if (resolved.isEmpty()) {
				resolved.add(plan.guarded(consumer.name(), condition));
			}
			return resolved;
		}

		// -- Per-plan shaping -------------------------------------------------------

		/**
		 * The plan's dependencies as edges, one per pair of actions, minus every edge a
		 * longer route already implies. An action that needs the screening result and a
		 * condition the screening posted, but also the review that itself needed both,
		 * follows the review; drawing it straight from the screening as well would say
		 * the two run side by side. A condition the dropped edge carried moves onto the
		 * consumer as a guard unless the plan still shows it elsewhere.
		 */
		private Map<String, Link> reducedLinks(Plan plan) {
			Map<String, Link> links = new LinkedHashMap<>();
			Map<String, Set<String>> successors = new LinkedHashMap<>();
			for (Dependency dependency : plan.dependencies) {
				Link link = links.computeIfAbsent(dependency.from() + '>' + dependency.to(),
						key -> new Link(dependency.from(), dependency.to()));
				switch (dependency.kind()) {
					case TYPE -> link.types.add(dependency.label());
					case STATE -> {
						link.types.add(dependency.label());
						link.stateTypes.add(dependency.label());
					}
					case CONDITION -> link.conditions.add(dependency.label());
				}
				successors.computeIfAbsent(dependency.from(), key -> new LinkedHashSet<>()).add(dependency.to());
			}
			List<Link> implied = new ArrayList<>();
			for (Link link : links.values()) {
				boolean viaOthers = successors.get(link.from)
					.stream()
					.anyMatch(next -> !next.equals(link.to) && reaches(next, link.to, successors::get));
				if (viaOthers) {
					implied.add(link);
				}
			}
			implied.forEach(link -> links.remove(link.from + '>' + link.to));
			for (Link link : implied) {
				for (String condition : link.conditions) {
					if (links.values().stream().noneMatch(kept -> kept.conditions.contains(condition))) {
						plan.guards.computeIfAbsent(link.to, key -> new LinkedHashSet<>()).add(condition);
					}
				}
			}
			return links;
		}

		private FlowPath toPath(Plan plan, Map<String, Link> links) {
			List<String> order = executionOrder(plan.actions, links.values());
			Double total = null;
			boolean dynamic = false;
			for (String name : order) {
				WorkflowStep step = this.actions.get(name);
				if (step.cost() != null) {
					total = (total == null ? 0.0 : total) + step.cost();
				}
				dynamic |= step.costMethod() != null;
			}
			// Declared costs are short decimals; summing them in binary yields
			// 0.15000000000000002, which nobody declared.
			if (total != null) {
				total = Math.round(total * 1_000_000d) / 1_000_000d;
			}
			return new FlowPath(plan.goal, order, total, dynamic, false);
		}

		/**
		 * Producers before consumers; among the ready, alphabetical, so the order is
		 * stable.
		 */
		private static List<String> executionOrder(Set<String> actions, Iterable<Link> links) {
			Map<String, Integer> pending = new LinkedHashMap<>();
			actions.forEach(action -> pending.put(action, 0));
			for (Link link : links) {
				pending.merge(link.to, 1, Integer::sum);
			}
			List<String> order = new ArrayList<>();
			TreeSet<String> ready = new TreeSet<>();
			pending.forEach((action, count) -> {
				if (count == 0) {
					ready.add(action);
				}
			});
			while (!ready.isEmpty()) {
				String next = ready.pollFirst();
				order.add(next);
				for (Link link : links) {
					if (link.from.equals(next) && pending.merge(link.to, -1, Integer::sum) == 0) {
						ready.add(link.to);
					}
				}
			}
			return order;
		}

		/**
		 * GOAP takes the cheapest plan, ties broken by length. Nothing is marked when
		 * every route ties, because then the declarations express no preference.
		 */
		private static List<FlowPath> markCheapest(List<FlowPath> paths) {
			if (paths.size() < 2) {
				return paths;
			}
			Comparator<FlowPath> byCost = Comparator.comparingDouble(Derivation::costOrZero)
				.thenComparingInt(path -> path.steps().size());
			FlowPath best = paths.stream().min(byCost).orElseThrow();
			List<FlowPath> cheapest = paths.stream().filter(path -> byCost.compare(path, best) == 0).toList();
			if (cheapest.size() == paths.size()) {
				return paths;
			}
			return paths.stream()
				.map(path -> cheapest.contains(path)
						? new FlowPath(path.goal(), path.steps(), path.totalCost(), path.dynamicCost(), true) : path)
				.toList();
		}

		private static double costOrZero(FlowPath path) {
			return path.totalCost() == null ? 0.0 : path.totalCost();
		}

		// -- Merging into one chart ---------------------------------------------------

		/**
		 * The union of every plan's edges, each remembering which plans take it. A plan's
		 * first actions hang off the start node, its root leads to the goal's end node,
		 * and an action's guards go on every edge into it.
		 */
		private List<Link> mergePlans(List<RankedPlan> ranked) {
			Map<String, Link> merged = new LinkedHashMap<>();
			Map<String, Set<String>> guards = new LinkedHashMap<>();
			for (int index = 0; index < ranked.size(); index++) {
				Plan plan = ranked.get(index).plan();
				Map<String, Link> links = ranked.get(index).links();
				for (String action : plan.actions) {
					if (links.values().stream().noneMatch(link -> link.to.equals(action))) {
						Link start = merged.computeIfAbsent(START_ID + '>' + stepId(action),
								key -> new Link(START_ID, stepId(action)));
						requiredInputs(this.actions.get(action)).stream()
							.filter(this.entryTypes::contains)
							.forEach(start.types::add);
						start.paths.add(index);
					}
				}
				for (Link link : links.values()) {
					Link target = merged.computeIfAbsent(stepId(link.from) + '>' + stepId(link.to),
							key -> new Link(stepId(link.from), stepId(link.to)));
					target.types.addAll(link.types);
					target.stateTypes.addAll(link.stateTypes);
					target.conditions.addAll(link.conditions);
					target.paths.add(index);
				}
				Link end = merged.computeIfAbsent(stepId(plan.root) + '>' + endId(plan.goal),
						key -> new Link(stepId(plan.root), endId(plan.goal)));
				end.types.add(this.actions.get(plan.goal).output());
				end.paths.add(index);
				plan.guards.forEach(
						(action, conditions) -> guards.computeIfAbsent(stepId(action), key -> new LinkedHashSet<>())
							.addAll(conditions));
			}
			for (Link link : merged.values()) {
				link.conditions.addAll(guards.getOrDefault(link.to, Set.of()));
			}
			return new ArrayList<>(merged.values());
		}

		private static Map<String, Set<Integer>> pathsByNode(List<RankedPlan> ranked) {
			Map<String, Set<Integer>> pathsOf = new LinkedHashMap<>();
			for (int index = 0; index < ranked.size(); index++) {
				Plan plan = ranked.get(index).plan();
				pathsOf.computeIfAbsent(START_ID, key -> new TreeSet<>()).add(index);
				pathsOf.computeIfAbsent(endId(plan.goal), key -> new TreeSet<>()).add(index);
				for (String action : plan.actions) {
					pathsOf.computeIfAbsent(stepId(action), key -> new TreeSet<>()).add(index);
				}
			}
			return pathsOf;
		}

		/**
		 * Where a node has several successors, they either always run together — a fork
		 * that later joins — or they are options the planner chooses between — a
		 * decision. A successor is "always" when every plan through the node takes that
		 * edge. When both kinds meet at one node, the fork carries the decision as one of
		 * its branches.
		 */
		private void insertSplits(List<Link> links, List<FlowNode> nodes, Map<String, Set<Integer>> pathsOf) {
			for (String nodeId : List.copyOf(pathsOf.keySet())) {
				List<Link> out = links.stream().filter(link -> link.from.equals(nodeId)).toList();
				if (out.size() < 2 || nodeId.startsWith("end:")) {
					continue;
				}
				Set<Integer> through = pathsOf.get(nodeId);
				List<Link> always = out.stream().filter(link -> link.paths.containsAll(through)).toList();
				List<Link> sometimes = out.stream().filter(link -> !always.contains(link)).toList();
				if (sometimes.isEmpty()) {
					insertHub(nodeId, "FORK", out, links, nodes);
				}
				else if (always.isEmpty()) {
					insertHub(nodeId, "DECISION", out, links, nodes);
				}
				else {
					String fork = insertHub(nodeId, "FORK", out, links, nodes);
					insertHub(fork, "DECISION", sometimes, links, nodes);
				}
			}
		}

		/**
		 * Reroutes {@code out} through a new fork or decision node. The type every branch
		 * hands over moves onto the edge into the hub; a branch keeps only what tells it
		 * apart from the others.
		 */
		private String insertHub(String source, String kind, List<Link> out, List<Link> links, List<FlowNode> nodes) {
			String hubId = kind.toLowerCase() + ':' + bareName(source);
			Link into = new Link(source, hubId);
			Set<String> common = new LinkedHashSet<>(out.get(0).types);
			out.forEach(link -> common.retainAll(link.types));
			into.types.addAll(common);
			WorkflowStep step = source.equals(START_ID) ? null : this.actions.get(bareName(source));
			if (common.isEmpty() && step != null && !"void".equals(step.output())) {
				into.types.add(step.output());
			}
			String label = null;
			String detail = null;
			if ("DECISION".equals(kind)) {
				detail = decisionKind(out);
				label = switch (detail) {
					case "SPEL" -> out.stream()
						.flatMap(link -> link.conditions.stream())
						.filter(condition -> condition.startsWith(SPEL_PREFIX))
						.map(condition -> condition.substring(SPEL_PREFIX.length()))
						.distinct()
						.collect(Collectors.joining(" \u00b7 "));
					case "STATE" -> "state";
					case "CONDITION" -> "condition";
					default -> "planner choice";
				};
			}
			for (Link link : out) {
				into.paths.addAll(link.paths);
				link.from = hubId;
				link.types.removeAll(common);
			}
			links.add(links.indexOf(out.get(0)), into);
			nodes.add(new FlowNode(hubId, kind, null, label, detail));
			return hubId;
		}

		/**
		 * What tells the branches apart, most specific first: a SpEL expression, a posted
		 * or evaluated condition, or the state an action routed to. Conditions only count
		 * when the branches differ in them — two actions gated by the same precondition
		 * are still the planner's call, as are two goals that are equally reachable.
		 */
		private static String decisionKind(List<Link> out) {
			boolean differ = out.stream().map(link -> link.conditions).distinct().count() > 1;
			if (differ) {
				return out.stream().anyMatch(link -> link.conditions.stream().anyMatch(c -> c.startsWith(SPEL_PREFIX)))
						? "SPEL" : "CONDITION";
			}
			if (out.stream().anyMatch(link -> !link.stateTypes.isEmpty())) {
				return "STATE";
			}
			return "PLANNER";
		}

		/**
		 * An action reached by several edges that all arrive whenever it runs waits for
		 * all of them: a join. Edges that arrive in different plans are alternatives and
		 * simply merge.
		 */
		private void insertJoins(List<Link> links, List<FlowNode> nodes, Map<String, Set<Integer>> pathsOf) {
			for (String nodeId : List.copyOf(pathsOf.keySet())) {
				List<Link> in = links.stream().filter(link -> link.to.equals(nodeId)).toList();
				if (in.size() < 2) {
					continue;
				}
				Set<Integer> through = pathsOf.get(nodeId);
				List<Link> always = in.stream().filter(link -> link.paths.containsAll(through)).toList();
				if (always.size() < 2) {
					continue;
				}
				String joinId = "join:" + bareName(nodeId);
				Link outOf = new Link(joinId, nodeId);
				for (Link link : always) {
					link.to = joinId;
					outOf.paths.addAll(link.paths);
				}
				links.add(outOf);
				nodes.add(new FlowNode(joinId, "JOIN", null, null, null));
			}
		}

		/**
		 * A {@code canRerun} action whose output an earlier step consumes can send the
		 * flow back there. The loop is drawn, not unrolled: the planner decides at
		 * runtime how many times it goes round.
		 */
		private void addLoops(List<Link> links, Set<String> inFlow) {
			for (String name : inFlow) {
				WorkflowStep action = this.actions.get(name);
				if (!action.canRerun()) {
					continue;
				}
				Set<String> produced = new LinkedHashSet<>();
				if (!"void".equals(action.output())) {
					produced.add(action.output());
				}
				if (action.possibleOutputs() != null) {
					produced.addAll(action.possibleOutputs());
				}
				for (String other : inFlow) {
					if (other.equals(name)) {
						continue;
					}
					WorkflowStep consumer = this.actions.get(other);
					List<String> consumed = new ArrayList<>(requiredInputs(consumer));
					consumed.addAll(optionalInputs(consumer));
					consumed.retainAll(produced);
					if (consumed.isEmpty() || !reaches(stepId(other), stepId(name), successorsIn(links))) {
						continue;
					}
					Link loop = new Link(stepId(name), stepId(other));
					loop.loop = true;
					loop.types.addAll(consumed);
					links.add(loop);
				}
			}
		}

		private static Function<String, Set<String>> successorsIn(List<Link> links) {
			Map<String, Set<String>> successors = new LinkedHashMap<>();
			for (Link link : links) {
				if (!link.loop) {
					successors.computeIfAbsent(link.from, key -> new LinkedHashSet<>()).add(link.to);
				}
			}
			return successors::get;
		}

		/**
		 * The steps drawn beside the chart: those the platform did not register, and
		 * those the planner could run but that lead to no goal.
		 */
		private List<FlowNode> detachedNodes(Set<String> inFlow) {
			List<FlowNode> detached = new ArrayList<>();
			for (WorkflowStep step : this.steps) {
				if (!PLAN_STEP_TYPES.contains(step.type())) {
					continue;
				}
				if (Boolean.FALSE.equals(step.registered())) {
					detached
						.add(new FlowNode(stepId(step.name()), "DETACHED", step.name(), "not in plan", "NOT_IN_PLAN"));
				}
				else if (!inFlow.contains(step.name()) && this.actions.get(step.name()) == step) {
					detached.add(
							new FlowNode(stepId(step.name()), "DETACHED", step.name(), "no goal path", "NO_GOAL_PATH"));
				}
			}
			return detached;
		}

		// -- Lookups ------------------------------------------------------------------

		private List<WorkflowStep> producersOf(String type) {
			return this.actions.values()
				.stream()
				.filter(action -> (!"void".equals(type) && type.equals(action.output()))
						|| (action.possibleOutputs() != null && action.possibleOutputs().contains(type)))
				.toList();
		}

		private List<WorkflowStep> postersOf(String condition) {
			return this.actions.values().stream().filter(action -> action.post().contains(condition)).toList();
		}

		private static List<String> requiredInputs(WorkflowStep step) {
			Set<String> skipped = new HashSet<>(step.providedInputs());
			skipped.addAll(step.optionalInputs());
			return step.inputs().stream().filter(type -> !skipped.contains(type)).distinct().toList();
		}

		private static List<String> optionalInputs(WorkflowStep step) {
			return step.optionalInputs()
				.stream()
				.filter(type -> !step.providedInputs().contains(type))
				.distinct()
				.toList();
		}

		private static boolean reaches(String from, String to, Function<String, Set<String>> successors) {
			Deque<String> pending = new ArrayDeque<>(List.of(from));
			Set<String> seen = new HashSet<>();
			while (!pending.isEmpty()) {
				String current = pending.pop();
				if (current.equals(to)) {
					return true;
				}
				Set<String> next = successors.apply(current);
				if (next != null && seen.add(current)) {
					pending.addAll(next);
				}
			}
			return false;
		}

		private static String stepId(String name) {
			return "step:" + name;
		}

		private static String endId(String goal) {
			return "end:" + goal;
		}

		private static String bareName(String nodeId) {
			return nodeId.startsWith("step:") ? nodeId.substring("step:".length()) : nodeId;
		}

	}

}
