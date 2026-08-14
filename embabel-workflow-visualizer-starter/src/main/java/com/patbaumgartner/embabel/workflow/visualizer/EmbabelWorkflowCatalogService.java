package com.patbaumgartner.embabel.workflow.visualizer;

import com.patbaumgartner.embabel.workflow.visualizer.WorkflowModels.AgentWorkflow;
import com.patbaumgartner.embabel.workflow.visualizer.WorkflowModels.WorkflowCatalog;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;

import java.util.Comparator;
import java.util.List;

/**
 * Discovers Embabel agents in the Spring {@link ApplicationContext} and produces a
 * {@link WorkflowCatalog} describing their workflow.
 *
 * <p>
 * The work itself belongs to three collaborators, each answering a different question:
 * {@link DeclaredWorkflowReader} reads what the source declares,
 * {@link AgentPlatformReader} reads what the planner registered, and
 * {@link RuntimeWorkflowReconciler} says where the two differ. What is left here is when
 * to ask them and how long to keep the answer.
 *
 * <p>
 * Discovery is purely reflective and never imports Embabel types directly so that the
 * starter remains usable without forcing the {@code embabel-agent-api} on the runtime
 * classpath.
 */
public class EmbabelWorkflowCatalogService implements ApplicationListener<ApplicationEvent> {

	private final ApplicationContext applicationContext;

	private final DeclaredWorkflowReader declaredReader;

	private final AgentPlatformReader platformReader;

	private final RuntimeWorkflowReconciler reconciler = new RuntimeWorkflowReconciler();

	private final Object scanLock = new Object();

	private volatile WorkflowCatalog cached;

	public EmbabelWorkflowCatalogService(ApplicationContext applicationContext) {
		this(applicationContext, new AgentPlatformReader(applicationContext));
	}

	EmbabelWorkflowCatalogService(ApplicationContext applicationContext, AgentPlatformReader platformReader) {
		this.applicationContext = applicationContext;
		this.declaredReader = new DeclaredWorkflowReader(applicationContext);
		this.platformReader = platformReader;
	}

	/**
	 * The workflow catalog for this application context.
	 *
	 * <p>
	 * Scanned once and reused until the application reaches its next startup milestone. A
	 * monitoring system polling {@code /actuator/embabel} should not re-reflect over
	 * every bean in the application on every request, and the answer only changes while
	 * the application is still starting: agent metadata comes from annotations on bean
	 * definitions, which do not change after refresh, and the planner has registered
	 * everything it is going to register once the runners have finished.
	 *
	 * <p>
	 * The web server accepts requests before either milestone, so an early caller can be
	 * answered from a context that is not finished yet. That answer is cached like any
	 * other — repeating the scan per request would not make it more correct — and
	 * discarded when the milestone it preceded arrives.
	 * @return every agent this context declares or runs, ordered by name
	 */
	public WorkflowCatalog catalog() {
		WorkflowCatalog current = this.cached;
		if (current != null) {
			return current;
		}
		synchronized (this.scanLock) {
			if (this.cached == null) {
				this.cached = scan();
			}
			return this.cached;
		}
	}

	/**
	 * Discards the cached catalog when the application reaches a startup milestone that
	 * can have changed the answer.
	 *
	 * <p>
	 * {@code ContextRefreshedEvent} covers everything created during refresh, and
	 * {@code ApplicationReadyEvent} covers agents a runner deployed afterwards —
	 * Embabel's {@code AgentPlatform} is mutable, so being readable is not the same as
	 * being finished. Both fire after the server is already accepting requests, which is
	 * precisely why an answer given before them must not be kept.
	 */
	@Override
	public void onApplicationEvent(ApplicationEvent event) {
		boolean ownRefresh = event instanceof ContextRefreshedEvent refreshed
				&& refreshed.getApplicationContext() == this.applicationContext;
		if (ownRefresh || event instanceof ApplicationReadyEvent) {
			synchronized (this.scanLock) {
				this.cached = null;
			}
		}
	}

	private WorkflowCatalog scan() {
		List<AgentWorkflow> agents = this.reconciler.reconcile(this.declaredReader.readDeclaredAgents(),
				this.platformReader.readAgents());
		agents.sort(Comparator.comparing(AgentWorkflow::agentName, String.CASE_INSENSITIVE_ORDER));
		return new WorkflowCatalog(List.copyOf(agents));
	}

}
