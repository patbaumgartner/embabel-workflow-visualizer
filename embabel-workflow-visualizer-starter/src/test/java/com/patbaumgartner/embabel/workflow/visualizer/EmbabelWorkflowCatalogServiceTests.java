package com.patbaumgartner.embabel.workflow.visualizer;

import com.patbaumgartner.embabel.workflow.visualizer.AgentPlatformReader.RuntimeAgent;
import com.patbaumgartner.embabel.workflow.visualizer.WorkflowModels.AgentWorkflow;
import com.patbaumgartner.embabel.workflow.visualizer.WorkflowModels.ToolMetadata;
import com.patbaumgartner.embabel.workflow.visualizer.WorkflowModels.WorkflowCatalog;
import com.patbaumgartner.embabel.workflow.visualizer.WorkflowModels.WorkflowStep;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class EmbabelWorkflowCatalogServiceTests {

	// -------------------------------------------------------------------------
	// Helper
	// -------------------------------------------------------------------------

	/**
	 * Builds a {@link WorkflowCatalog} from a fresh
	 * {@link AnnotationConfigApplicationContext} that contains exactly the supplied bean
	 * classes, then closes the context.
	 */
	private WorkflowCatalog catalogWith(Class<?>... beanClasses) {
		try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
			for (Class<?> bc : beanClasses) {
				ctx.registerBean(bc.getSimpleName().toLowerCase(), bc);
			}
			ctx.refresh();
			return new EmbabelWorkflowCatalogService(ctx).catalog();
		}
	}

	// -------------------------------------------------------------------------
	// Context / bean scanning
	// -------------------------------------------------------------------------

	@Test
	void emptyContextProducesEmptyCatalog() {
		assertThat(catalogWith().agents()).isEmpty();
	}

	@Test
	void plainBeansAreIgnored() {
		assertThat(catalogWith(NotAnAgent.class).agents()).isEmpty();
	}

	// -------------------------------------------------------------------------
	// Agent metadata
	// -------------------------------------------------------------------------

	@Test
	void discoversAgentMetadata() {
		WorkflowCatalog catalog = catalogWith(SampleEmbabelAgent.class);

		assertThat(catalog.agents()).hasSize(1);
		AgentWorkflow agent = catalog.agents().get(0);
		assertThat(agent.agentName()).isEqualTo("demo-agent");
		assertThat(agent.description()).isEqualTo("Demo test agent");
		assertThat(agent.plannerType()).isEqualTo("GOAP");
		assertThat(agent.opaque()).isFalse();
		assertThat(agent.className()).isEqualTo(SampleEmbabelAgent.class.getName());
		assertThat(agent.steps()).extracting(WorkflowStep::method)
			.containsExactlyInAnyOrder("draftPlan", "hasInput", "completeGoal");
	}

	@Test
	void discoversEmbabelComponentBean() {
		WorkflowCatalog catalog = catalogWith(EmbabelComponentSampleBean.class);

		assertThat(catalog.agents()).hasSize(1);
		AgentWorkflow agent = catalog.agents().get(0);
		assertThat(agent.agentName()).isEqualTo(EmbabelComponentSampleBean.class.getSimpleName());
		assertThat(agent.plannerType()).isEqualTo("COMPONENT");
		assertThat(agent.opaque()).isFalse();
		assertThat(agent.steps()).extracting(WorkflowStep::method).containsExactly("doWork");
	}

	@Test
	void opaqueAttributeIsReflected() {
		AgentWorkflow agent = catalogWith(RichActionSampleAgent.class).agents().get(0);

		assertThat(agent.agentName()).isEqualTo("rich-agent");
		assertThat(agent.version()).isEqualTo("3.0.0");
		assertThat(agent.opaque()).isTrue();
	}

	@Test
	void agentProviderIsReflected() {
		AgentWorkflow agent = catalogWith(RichActionSampleAgent.class).agents().get(0);

		assertThat(agent.provider()).isEqualTo("acme");
	}

	// -------------------------------------------------------------------------
	// Sorting
	// -------------------------------------------------------------------------

	@Test
	void agentsAreSortedAlphabeticallyCaseInsensitive() {
		WorkflowCatalog catalog = catalogWith(MultiGoalSampleAgent.class, SampleEmbabelAgent.class);

		assertThat(catalog.agents()).extracting(AgentWorkflow::agentName)
			.containsExactly("demo-agent", "MultiGoalAgent");
	}

	@Test
	void stepsAreSortedAlphabeticallyByName() {
		assertThat(catalogWith(MultiGoalSampleAgent.class).agents()
			.get(0)
			.steps()
			.stream()
			.map(WorkflowStep::name)
			.toList()).isSortedAccordingTo(String.CASE_INSENSITIVE_ORDER);
	}

	// -------------------------------------------------------------------------
	// Step types and annotations
	// -------------------------------------------------------------------------

	@Test
	void multipleAchievesGoalMethodsAreAllCaptured() {
		AgentWorkflow agent = catalogWith(MultiGoalSampleAgent.class).agents().get(0);
		assertThat(agent.version()).isEqualTo("2.0.0");

		Map<String, WorkflowStep> byMethod = agent.steps()
			.stream()
			.collect(Collectors.toMap(WorkflowStep::method, s -> s));

		assertThat(byMethod).containsKeys("inspect", "isFastPath", "completeFast", "completeSlow");

		WorkflowStep inspect = byMethod.get("inspect");
		assertThat(inspect.type()).isEqualTo("Action");
		assertThat(inspect.post()).containsExactlyInAnyOrder(MultiGoalSampleAgent.FAST_PATH,
				MultiGoalSampleAgent.SLOW_PATH);
		assertThat(inspect.inputs()).containsExactly("Request");
		assertThat(inspect.output()).isEqualTo("Inspection");
		assertThat(inspect.goal()).isFalse();

		WorkflowStep fast = byMethod.get("completeFast");
		assertThat(fast.type()).isEqualTo("AchievesGoal");
		assertThat(fast.goal()).isTrue();
		assertThat(fast.pre()).containsExactly(MultiGoalSampleAgent.FAST_PATH);
		assertThat(fast.inputs()).containsExactly("Inspection");
		assertThat(fast.output()).isEqualTo("Result");

		WorkflowStep slow = byMethod.get("completeSlow");
		assertThat(slow.type()).isEqualTo("AchievesGoal");
		assertThat(slow.goal()).isTrue();
		assertThat(slow.pre()).containsExactly(MultiGoalSampleAgent.SLOW_PATH);

		WorkflowStep cond = byMethod.get("isFastPath");
		assertThat(cond.type()).isEqualTo("Condition");
		assertThat(cond.output()).isEqualTo("boolean");
	}

	@Test
	void richActionAttributesAreReflected() {
		Map<String, WorkflowStep> byMethod = catalogWith(RichActionSampleAgent.class).agents()
			.get(0)
			.steps()
			.stream()
			.collect(Collectors.toMap(WorkflowStep::method, s -> s));

		WorkflowStep step = byMethod.get("processData");
		assertThat(step.pre()).containsExactly("precondition");
		assertThat(step.post()).containsExactly("postcondition");
		assertThat(step.canRerun()).isTrue();
		assertThat(step.readOnly()).isTrue();
		assertThat(step.outputBinding()).isEqualTo("myOutput");
		assertThat(step.costMethod()).isEqualTo("calcCost");
		assertThat(step.valueMethod()).isEqualTo("calcValue");
		assertThat(step.clearBlackboard()).isFalse();
		// No static cost/value declared — must be null, not 0.0
		assertThat(step.cost()).isNull();
		assertThat(step.value()).isNull();
		// No trigger / retry policy on this action
		assertThat(step.trigger()).isNull();
		assertThat(step.retryPolicy()).isNull();
	}

	@Test
	void outputBindingIsOnlyReportedWhenTheAuthorNamedOne() {
		Map<String, WorkflowStep> byMethod = catalogWith(RichActionSampleAgent.class).agents()
			.get(0)
			.steps()
			.stream()
			.collect(Collectors.toMap(WorkflowStep::method, s -> s));

		assertThat(byMethod.get("processData").outputBinding()).isEqualTo("myOutput");
		// Embabel defaults @Action(outputBinding) to IoBinding.DEFAULT_BINDING ("it"),
		// which carries no information and must not be reported as a custom binding
		assertThat(byMethod.get("staticCostAction").outputBinding()).isNull();
		// @Condition / @Cost have no outputBinding attribute at all
		assertThat(byMethod.get("calcCost").outputBinding()).isNull();
	}

	@Test
	void actionTriggerAndRetryPolicyAreReflected() {
		Map<String, WorkflowStep> byMethod = catalogWith(RichActionSampleAgent.class).agents()
			.get(0)
			.steps()
			.stream()
			.collect(Collectors.toMap(WorkflowStep::method, s -> s));

		WorkflowStep step = byMethod.get("onRefresh");
		assertThat(step.trigger()).isEqualTo("RefreshEvent");
		assertThat(step.retryPolicy()).isEqualTo("maxAttempts=3");
	}

	@Test
	void staticCostAndValueAreReflected() {
		Map<String, WorkflowStep> byMethod = catalogWith(RichActionSampleAgent.class).agents()
			.get(0)
			.steps()
			.stream()
			.collect(Collectors.toMap(WorkflowStep::method, s -> s));

		WorkflowStep step = byMethod.get("staticCostAction");
		assertThat(step.cost()).isEqualTo(2.5);
		assertThat(step.value()).isEqualTo(0.4);
		assertThat(step.costMethod()).isNull();
		assertThat(step.valueMethod()).isNull();
	}

	@Test
	void achievesGoalTagsAndExamplesAreReflected() {
		Map<String, WorkflowStep> byMethod = catalogWith(RichActionSampleAgent.class).agents()
			.get(0)
			.steps()
			.stream()
			.collect(Collectors.toMap(WorkflowStep::method, s -> s));

		WorkflowStep step = byMethod.get("achieveRichGoal");
		assertThat(step.type()).isEqualTo("AchievesGoal");
		assertThat(step.goal()).isTrue();
		assertThat(step.tags()).containsExactlyInAnyOrder("tag1", "tag2");
		assertThat(step.examples()).containsExactlyInAnyOrder("example 1", "example 2");
		assertThat(step.goalValue()).isEqualTo(0.9);
		assertThat(step.exportedRemote()).isTrue();
		assertThat(step.exportName()).isEqualTo("richGoalTool");
		// @AchievesGoal(value=) must not leak into the @Action static cost/value
		assertThat(step.cost()).isNull();
		assertThat(step.value()).isNull();
	}

	@Test
	void llmToolAnnotationIsRecognized() {
		Map<String, WorkflowStep> byMethod = catalogWith(RichActionSampleAgent.class).agents()
			.get(0)
			.steps()
			.stream()
			.collect(Collectors.toMap(WorkflowStep::method, s -> s));

		WorkflowStep step = byMethod.get("helpTool");
		assertThat(step.type()).isEqualTo("LlmTool");
		assertThat(step.llmTool()).isTrue();
		assertThat(step.llmToolDescription()).isEqualTo("A helpful LLM tool");
		assertThat(step.description()).isEqualTo("A helpful LLM tool");
		assertThat(step.inputs()).containsExactly("String");
		assertThat(step.llmToolReturnDirect()).isTrue();
		assertThat(step.llmToolCategory()).isEqualTo("utility");
	}

	// -------------------------------------------------------------------------
	// Full annotation-attribute coverage
	// -------------------------------------------------------------------------

	private Map<String, WorkflowStep> fullCoverageStepsByMethod() {
		return catalogWith(FullCoverageSampleAgent.class).agents()
			.get(0)
			.steps()
			.stream()
			.collect(Collectors.toMap(WorkflowStep::method, s -> s));
	}

	@Test
	void hybridPlannerAndAgentLevelAttributesAreReflected() {
		AgentWorkflow agent = catalogWith(FullCoverageSampleAgent.class).agents().get(0);

		assertThat(agent.plannerType()).isEqualTo("HYBRID");
		assertThat(agent.beanName()).isEqualTo("fullCoverageBean");
		assertThat(agent.retryPolicy()).isEqualTo("FIRE_ONCE");
		assertThat(agent.retryPolicyExpression()).isEqualTo("agentMaxAttempts=2");
		assertThat(agent.scan()).isTrue();
	}

	@Test
	void agentRetryPolicyIsNullWhenLeftAtDefault() {
		AgentWorkflow agent = catalogWith(RichActionSampleAgent.class).agents().get(0);

		assertThat(agent.retryPolicy()).isNull();
		assertThat(agent.retryPolicyExpression()).isNull();
		assertThat(agent.beanName()).isNull();
	}

	@Test
	void actionRetryPolicyConstantIsReflected() {
		Map<String, WorkflowStep> byMethod = fullCoverageStepsByMethod();

		assertThat(byMethod.get("fireOnce").actionRetryPolicy()).isEqualTo("FIRE_ONCE");
		// ActionRetryPolicy.DEFAULT means "not configured" and must not be reported
		assertThat(byMethod.get("bindParameters").actionRetryPolicy()).isNull();
	}

	@Test
	void conditionCostIsReflected() {
		Map<String, WorkflowStep> byMethod = fullCoverageStepsByMethod();

		WorkflowStep condition = byMethod.get("isReady");
		assertThat(condition.type()).isEqualTo("Condition");
		assertThat(condition.conditionCost()).isEqualTo(0.25);
	}

	@Test
	void exportLocalAndStartingInputTypesAreReflected() {
		WorkflowStep step = fullCoverageStepsByMethod().get("achieveExportedGoal");

		assertThat(step.exportedRemote()).isTrue();
		assertThat(step.exportedLocal()).isFalse();
		assertThat(step.exportName()).isEqualTo("hiddenLocally");
		assertThat(step.exportStartingInputTypes()).containsExactly("Draft");
	}

	@Test
	void exportDefaultsToLocallyCallable() {
		Map<String, WorkflowStep> byMethod = catalogWith(RichActionSampleAgent.class).agents()
			.get(0)
			.steps()
			.stream()
			.collect(Collectors.toMap(WorkflowStep::method, s -> s));

		assertThat(byMethod.get("achieveRichGoal").exportedLocal()).isTrue();
	}

	@Test
	void llmToolNameAndMetadataAreReflected() {
		WorkflowStep step = fullCoverageStepsByMethod().get("annotatedTool");

		assertThat(step.llmToolName()).isEqualTo("explicitToolName");
		assertThat(step.llmToolMetadata()).extracting(ToolMetadata::key, ToolMetadata::value)
			.containsExactly(tuple("owner", "platform"), tuple("stability", "beta"));
	}

	@Test
	void providedAndRequireNameMatchParametersAreReflected() {
		WorkflowStep step = fullCoverageStepsByMethod().get("bindParameters");

		assertThat(step.providedInputs()).containsExactly("Draft");
		assertThat(step.nameMatchInputs()).containsExactly("String:editorNotes");
	}

	// -------------------------------------------------------------------------
	// Inherited steps
	// -------------------------------------------------------------------------

	@Test
	void discoversStepsInheritedFromSuperclassesAndInterfaces() {
		AgentWorkflow agent = catalogWith(InheritedStepsSampleAgent.class).agents().get(0);

		assertThat(agent.steps()).extracting(WorkflowStep::method)
			.containsExactlyInAnyOrder("finishReview", "prepareReview", "inheritedReady", "inheritedCost",
					"recordAuditTrail");
	}

	@Test
	void inheritedStepsKeepTheirDeclaredAttributes() {
		Map<String, WorkflowStep> byMethod = catalogWith(InheritedStepsSampleAgent.class).agents()
			.get(0)
			.steps()
			.stream()
			.collect(Collectors.toMap(WorkflowStep::method, s -> s));

		assertThat(byMethod.get("prepareReview").costMethod()).isEqualTo("inheritedCost");
		assertThat(byMethod.get("inheritedReady").type()).isEqualTo("Condition");
		assertThat(byMethod.get("inheritedReady").conditionCost()).isEqualTo(0.125);
		assertThat(byMethod.get("inheritedCost").type()).isEqualTo("Cost");
		assertThat(byMethod.get("recordAuditTrail").readOnly()).isTrue();
	}

	@Test
	void anOverriddenInheritedStepIsReportedOnce() {
		List<WorkflowStep> steps = catalogWith(InheritedStepsSampleAgent.class).agents().get(0).steps();

		assertThat(steps).filteredOn(step -> step.method().equals("prepareReview")).hasSize(1);
	}

	// -------------------------------------------------------------------------
	// Scanning must not disturb the application context
	// -------------------------------------------------------------------------

	@Test
	void scanningDoesNotInstantiateLazyBeans() {
		LazyBeanConfiguration.instantiations.set(0);

		try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
			ctx.register(LazyBeanConfiguration.class);
			ctx.registerBean("sampleAgent", SampleEmbabelAgent.class);
			ctx.refresh();

			WorkflowCatalog catalog = new EmbabelWorkflowCatalogService(ctx).catalog();

			assertThat(catalog.agents()).extracting(AgentWorkflow::agentName).containsExactly("demo-agent");
			assertThat(LazyBeanConfiguration.instantiations)
				.describedAs("reading the workflow catalog must not create application beans")
				.hasValue(0);
		}
	}

	@Test
	void discoversAgentsDeclaredThroughAGenericBeanMethodReturnType() {
		try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
			ctx.register(GenericFactoryMethodConfiguration.class);
			ctx.refresh();

			WorkflowCatalog catalog = new EmbabelWorkflowCatalogService(ctx).catalog();

			// Neither @Bean method declares the agent's concrete type, but both beans
			// are eagerly created, so their real class is available without any
			// initialisation of our own
			assertThat(catalog.agents()).extracting(AgentWorkflow::className)
				.containsExactlyInAnyOrder(SampleEmbabelAgent.class.getName(), RunnableAgent.class.getName());
		}
	}

	/**
	 * A lazy bean whose declared type cannot carry the annotation is the one case the
	 * scan cannot resolve — finding out would mean instantiating it, which this service
	 * refuses to do. It must still leave every other bean, lazy or not, untouched.
	 */
	@Test
	void doesNotInstantiateLazyBeansToDiscoverTheirConcreteType() {
		LazyAgentConfiguration.instantiations.set(0);

		try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
			ctx.register(LazyAgentConfiguration.class);
			ctx.refresh();

			WorkflowCatalog catalog = new EmbabelWorkflowCatalogService(ctx).catalog();

			// The lazy bean with a concrete declared type is still discovered, because
			// the bean definition alone answers the question
			assertThat(catalog.agents()).extracting(AgentWorkflow::agentName).containsExactly("demo-agent");
			assertThat(LazyAgentConfiguration.instantiations).hasValue(0);
		}
	}

	@Test
	void discoversAgentsBehindClassBasedProxies() {
		try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
			ctx.registerBean("proxying", ClassProxyingPostProcessor.class);
			ctx.registerBean("sampleAgent", SampleEmbabelAgent.class);
			ctx.refresh();

			assertThat(AopUtils.isCglibProxy(ctx.getBean("sampleAgent"))).isTrue();

			WorkflowCatalog catalog = new EmbabelWorkflowCatalogService(ctx).catalog();

			assertThat(catalog.agents()).singleElement()
				.extracting(AgentWorkflow::agentName, AgentWorkflow::className)
				.containsExactly("demo-agent", SampleEmbabelAgent.class.getName());
		}
	}

	@Test
	void oneUnreadableBeanDoesNotAbortTheScan() {
		try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
			ctx.registerBean("sampleAgent", SampleEmbabelAgent.class);
			ctx.refresh();

			ApplicationContext failingOnOneBean = mock(ApplicationContext.class);
			given(failingOnOneBean.getBeanNamesForType(Object.class, false, false))
				.willReturn(new String[] { "broken", "sampleAgent" });
			given(failingOnOneBean.getType("broken", false)).willThrow(new IllegalStateException("cannot resolve"));
			given(failingOnOneBean.getType("sampleAgent", false)).willReturn((Class) SampleEmbabelAgent.class);

			WorkflowCatalog catalog = new EmbabelWorkflowCatalogService(failingOnOneBean).catalog();

			assertThat(catalog.agents()).extracting(AgentWorkflow::agentName).containsExactly("demo-agent");
		}
	}

	/**
	 * A bean that could not be inspected may well be readable on the next attempt, so
	 * caching a scan that skipped one would make a transient failure permanent.
	 */
	@Test
	void anIncompleteScanIsNotCached() {
		ApplicationContext failingOnOneBean = mock(ApplicationContext.class);
		given(failingOnOneBean.getBeanNamesForType(Object.class, false, false))
			.willReturn(new String[] { "broken", "sampleAgent" });
		given(failingOnOneBean.getType("broken", false)).willThrow(new IllegalStateException("cannot resolve"));
		given(failingOnOneBean.getType("sampleAgent", false)).willReturn((Class) SampleEmbabelAgent.class);
		EmbabelWorkflowCatalogService service = new EmbabelWorkflowCatalogService(failingOnOneBean);

		assertThat(service.catalog().agents()).hasSize(1);
		assertThat(service.catalog().agents()).hasSize(1);

		verify(failingOnOneBean, times(2)).getBeanNamesForType(Object.class, false, false);
	}

	@Test
	void theCatalogIsScannedOnceAndReused() {
		try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
			ctx.registerBean("sampleAgent", SampleEmbabelAgent.class);
			ctx.refresh();
			ApplicationContext counting = spy(ctx);
			EmbabelWorkflowCatalogService service = new EmbabelWorkflowCatalogService(counting);

			WorkflowCatalog first = service.catalog();
			WorkflowCatalog second = service.catalog();

			assertThat(second).isSameAs(first);
			verify(counting, times(1)).getBeanNamesForType(Object.class, false, false);
		}
	}

	/**
	 * An empty result may simply mean the context had not finished refreshing when the
	 * catalog was first asked for, so it must not be cached forever.
	 */
	@Test
	void anEmptyCatalogIsNotCached() {
		try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
			ctx.refresh();
			ApplicationContext counting = spy(ctx);
			EmbabelWorkflowCatalogService service = new EmbabelWorkflowCatalogService(counting);

			assertThat(service.catalog().agents()).isEmpty();
			assertThat(service.catalog().agents()).isEmpty();

			verify(counting, times(2)).getBeanNamesForType(Object.class, false, false);
		}
	}

	@Test
	void anUnusableBeanFactoryYieldsAnEmptyCatalogInsteadOfAnError() {
		ApplicationContext broken = mock(ApplicationContext.class);
		given(broken.getBeanNamesForType(Object.class, false, false)).willThrow(new IllegalStateException("closed"));

		assertThat(new EmbabelWorkflowCatalogService(broken).catalog().agents()).isEmpty();
	}

	@Configuration
	static class GenericFactoryMethodConfiguration {

		@Bean
		Object objectReturnAgent() {
			return new SampleEmbabelAgent();
		}

		@Bean
		Runnable interfaceReturnAgent() {
			return new RunnableAgent();
		}

	}

	/**
	 * An agent a {@code @Bean} method can legitimately declare as {@code Runnable}.
	 * Annotated in its own right because {@code @Agent} is not {@code @Inherited} —
	 * extending an annotated class would not make this one an agent, to Embabel or to the
	 * catalog.
	 */
	@Agent(name = "runnable-agent", description = "Declared through an interface return type")
	static class RunnableAgent implements Runnable {

		@Action(description = "Work")
		public String work() {
			return "done";
		}

		@Override
		public void run() {
		}

	}

	@Configuration
	static class LazyAgentConfiguration {

		static final AtomicInteger instantiations = new AtomicInteger();

		@Bean
		@Lazy
		SampleEmbabelAgent lazyConcreteAgent() {
			instantiations.incrementAndGet();
			return new SampleEmbabelAgent();
		}

		@Bean
		@Lazy
		Object lazyGenericAgent() {
			instantiations.incrementAndGet();
			return new SampleEmbabelAgent();
		}

	}

	@Configuration
	static class LazyBeanConfiguration {

		static final AtomicInteger instantiations = new AtomicInteger();

		@Bean
		@Lazy
		ExpensiveBean expensiveBean() {
			instantiations.incrementAndGet();
			return new ExpensiveBean();
		}

	}

	static class ExpensiveBean {

	}

	/** Wraps the sample agent in a CGLIB proxy, as Spring AOP would. */
	static class ClassProxyingPostProcessor implements BeanPostProcessor {

		@Override
		public Object postProcessAfterInitialization(Object bean, String beanName) {
			if (!(bean instanceof SampleEmbabelAgent)) {
				return bean;
			}
			ProxyFactory proxyFactory = new ProxyFactory(bean);
			proxyFactory.setProxyTargetClass(true);
			return proxyFactory.getProxy();
		}

	}

	// -------------------------------------------------------------------------
	// Runtime reconciliation
	// -------------------------------------------------------------------------

	private WorkflowCatalog catalogWithPlatform(FakeAgentPlatform.Platform platform, Class<?>... beanClasses) {
		try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
			for (Class<?> beanClass : beanClasses) {
				ctx.registerBean(beanClass.getSimpleName().toLowerCase(), beanClass);
			}
			ctx.refresh();
			AgentPlatformReader reader = new AgentPlatformReader(ctx) {
				@Override
				List<RuntimeAgent> readAgents() {
					return readAgentsFrom(platform);
				}
			};
			return new EmbabelWorkflowCatalogService(ctx, reader).catalog();
		}
	}

	@Test
	void withoutAPlatformEveryRegisteredFlagMeansUnknown() {
		AgentWorkflow agent = catalogWith(SampleEmbabelAgent.class).agents().get(0);

		assertThat(agent.registered()).isNull();
		assertThat(agent.steps()).extracting(WorkflowStep::registered).containsOnlyNulls();
	}

	@Test
	void marksDeclaredStepsThePlatformRegistered() {
		FakeAgentPlatform.Platform platform = new FakeAgentPlatform.Platform(
				List.of(FakeAgentPlatform.Agent.named("demo-agent",
						List.of(FakeAgentPlatform.Action.named(SampleEmbabelAgent.class.getName() + ".draftPlan")),
						Set.of(FakeAgentPlatform.Goal.named(SampleEmbabelAgent.class.getName() + ".completeGoal")))));

		AgentWorkflow agent = catalogWithPlatform(platform, SampleEmbabelAgent.class).agents().get(0);

		assertThat(agent.registered()).isTrue();
		assertThat(agent.steps()).extracting(WorkflowStep::method, WorkflowStep::registered)
			.contains(tuple("draftPlan", true), tuple("completeGoal", true));
	}

	/**
	 * The case annotations cannot reveal: a SUPERVISOR agent's declared actions are not
	 * planner actions — the planner registers one synthetic supervisor action instead.
	 * Showing the declared steps as registered would be a lie.
	 */
	@Test
	void marksDeclaredStepsThePlannerDoesNotRun() {
		FakeAgentPlatform.Platform platform = new FakeAgentPlatform.Platform(List.of(FakeAgentPlatform.Agent
			.named("demo-agent", List.of(FakeAgentPlatform.Action.named("SampleEmbabelAgent.supervisor")), Set.of())));

		AgentWorkflow agent = catalogWithPlatform(platform, SampleEmbabelAgent.class).agents().get(0);

		Map<String, WorkflowStep> byMethod = agent.steps()
			.stream()
			.collect(Collectors.toMap(WorkflowStep::method, step -> step));
		assertThat(byMethod.get("draftPlan").registered()).isFalse();
		assertThat(byMethod.get("completeGoal").registered()).isFalse();
	}

	@Test
	void addsStepsThePlannerSynthesised() {
		FakeAgentPlatform.Platform platform = new FakeAgentPlatform.Platform(List
			.of(FakeAgentPlatform.Agent.named("demo-agent",
					List.of(new FakeAgentPlatform.Action("SampleEmbabelAgent.supervisor", "Orchestrates tools",
							FakeAgentPlatform.IoBinding.of("it:com.example.Request"),
							FakeAgentPlatform.IoBinding.of("it:com.example.Report"), Map.of(), Map.of(), false, false)),
					Set.of())));

		AgentWorkflow agent = catalogWithPlatform(platform, SampleEmbabelAgent.class).agents().get(0);

		WorkflowStep supervisor = agent.steps()
			.stream()
			.filter(WorkflowStep::plannerGenerated)
			.findFirst()
			.orElseThrow();
		assertThat(supervisor.name()).isEqualTo("supervisor");
		assertThat(supervisor.description()).isEqualTo("Orchestrates tools");
		assertThat(supervisor.inputs()).containsExactly("Request");
		assertThat(supervisor.output()).isEqualTo("Report");
		assertThat(supervisor.registered()).isTrue();
	}

	/**
	 * A {@code @Cost} function and an {@code @LlmTool} are deliberately not plan steps.
	 * Reporting them as missing from the plan would bury the case that matters.
	 */
	@Test
	void stepsThatAreNotPlanStepsAreNeverFlaggedAsMissing() {
		FakeAgentPlatform.Platform platform = new FakeAgentPlatform.Platform(List.of(FakeAgentPlatform.Agent.named(
				"rich-agent", List.of(FakeAgentPlatform.Action.named("RichActionSampleAgent.processData")), Set.of())));

		Map<String, WorkflowStep> byMethod = catalogWithPlatform(platform, RichActionSampleAgent.class).agents()
			.get(0)
			.steps()
			.stream()
			.collect(Collectors.toMap(WorkflowStep::method, step -> step));

		assertThat(byMethod.get("calcCost").type()).isEqualTo("Cost");
		assertThat(byMethod.get("calcCost").registered()).isNull();
		assertThat(byMethod.get("helpTool").type()).isEqualTo("LlmTool");
		assertThat(byMethod.get("helpTool").registered()).isNull();
		// an action, though, is a plan step and is reported either way
		assertThat(byMethod.get("processData").registered()).isTrue();
		assertThat(byMethod.get("onRefresh").registered()).isFalse();
	}

	@Test
	void reportsAgentsThatOnlyExistAtRuntime() {
		FakeAgentPlatform.Platform platform = new FakeAgentPlatform.Platform(List.of(FakeAgentPlatform.Agent.named(
				"com.example.BuiltInCode", List.of(FakeAgentPlatform.Action.named("com.example.BuiltInCode.step")),
				Set.of(FakeAgentPlatform.Goal.named("com.example.BuiltInCode.done")))));

		WorkflowCatalog catalog = catalogWithPlatform(platform, SampleEmbabelAgent.class);

		AgentWorkflow runtimeOnly = catalog.agents()
			.stream()
			.filter(agent -> "BuiltInCode".equals(agent.agentName()))
			.findFirst()
			.orElseThrow();
		assertThat(runtimeOnly.plannerType()).isEqualTo("RUNTIME");
		assertThat(runtimeOnly.className()).isEqualTo("com.example.BuiltInCode");
		assertThat(runtimeOnly.registered()).isTrue();
		assertThat(runtimeOnly.steps()).extracting(WorkflowStep::name, WorkflowStep::plannerGenerated)
			.containsExactlyInAnyOrder(tuple("step", true), tuple("done", true));
	}

	@Test
	void marksAnAnnotatedAgentThePlatformNeverDeployed() {
		FakeAgentPlatform.Platform platform = new FakeAgentPlatform.Platform(
				List.of(FakeAgentPlatform.Agent.named("com.example.Other", List.of(), Set.of())));

		AgentWorkflow agent = catalogWithPlatform(platform, SampleEmbabelAgent.class).agents()
			.stream()
			.filter(candidate -> "demo-agent".equals(candidate.agentName()))
			.findFirst()
			.orElseThrow();

		assertThat(agent.registered()).isFalse();
	}

	/** Embabel names an @EmbabelComponent agent by its fully-qualified class name. */
	@Test
	void matchesARuntimeAgentNamedByFullyQualifiedClassName() {
		FakeAgentPlatform.Platform platform = new FakeAgentPlatform.Platform(
				List.of(FakeAgentPlatform.Agent.named(EmbabelComponentSampleBean.class.getName(),
						List.of(FakeAgentPlatform.Action.named(EmbabelComponentSampleBean.class.getName() + ".doWork")),
						Set.of())));

		AgentWorkflow agent = catalogWithPlatform(platform, EmbabelComponentSampleBean.class).agents().get(0);

		assertThat(agent.registered()).isTrue();
		assertThat(agent.steps()).extracting(WorkflowStep::method, WorkflowStep::registered)
			.containsExactly(tuple("doWork", true));
	}

}
