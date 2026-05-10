package com.patbaumgartner.embabel.ticketing;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.annotation.Cost;
import com.embabel.agent.api.annotation.State;
import com.embabel.agent.api.common.OperationContext;
import com.embabel.agent.api.common.PlannerType;
import com.patbaumgartner.embabel.ticketing.TicketModels.BillingTicket;
import com.patbaumgartner.embabel.ticketing.TicketModels.GeneralTicket;
import com.patbaumgartner.embabel.ticketing.TicketModels.SupportTicket;
import com.patbaumgartner.embabel.ticketing.TicketModels.TechnicalTicket;
import com.patbaumgartner.embabel.ticketing.TicketModels.TicketClassification;
import com.patbaumgartner.embabel.ticketing.TicketModels.TicketResolution;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ticket Routing Agent — Utility AI planner with @State-based category routing.
 *
 * <p>
 * <b>New features demonstrated:</b>
 * <ul>
 * <li>{@code planner = PlannerType.UTILITY} — the Utility AI planner selects the
 * highest-value available action at each step instead of planning a fixed path</li>
 * <li>{@link State} annotation — {@link BillingTicket}, {@link TechnicalTicket}, and
 * {@link GeneralTicket} are @State-annotated records that trigger state transitions. Each
 * state contains its own @Action and @AchievesGoal methods.</li>
 * <li>{@code valueMethod =} on @Action — dynamic value computation via a {@link Cost}
 * method, used by the Utility planner to rank action priority</li>
 * <li>{@code @Cost} method for value — same annotation used for both cost and value
 * computation (referenced by costMethod / valueMethod)</li>
 * </ul>
 *
 * <p>
 * <b>Plan (Utility planner + @State routing):</b>
 *
 * <pre>
 * SupportTicket
 *   → [classifyTicket]    → TicketClassification
 *     → [routeToCategory] → BillingTicket | TechnicalTicket | GeneralTicket  (@State)
 *       → handleBilling / handleTechnical / handleGeneral  (@AchievesGoal in each @State)
 *         → TicketResolution
 * </pre>
 *
 * <p>
 * The Utility planner continuously evaluates which action has the highest net value
 * (value − cost) given what is currently on the blackboard. When a @State type is
 * produced, planning within that state begins automatically.
 */
@Agent(name = "TicketRoutingAgent",
		description = "Routes and resolves customer support tickets by category using Utility AI planning and state-based handlers.",
		version = "1.0.0", planner = PlannerType.UTILITY)
public class TicketRoutingAgent {

	private static final Logger log = LoggerFactory.getLogger(TicketRoutingAgent.class);

	// ── Dynamic value methods (@Cost used for valueMethod) ────────────────

	/**
	 * Dynamic value for the classification step.
	 *
	 * <p>
	 * High-priority tickets get a higher value score so the Utility planner runs
	 * classification sooner relative to any competing actions.
	 *
	 * <p>
	 * {@link Cost} methods must accept {@code @Nullable} domain parameters — if the
	 * object is not yet on the blackboard, {@code null} is passed.
	 */
	@Cost(name = "classificationValue")
	public double classificationValue(@Nullable SupportTicket ticket) {
		if (ticket == null) {
			return 0.5;
		}
		return switch (ticket.priority().toLowerCase()) {
			case "critical" -> 1.0;
			case "high" -> 0.8;
			case "medium" -> 0.6;
			default -> 0.4;
		};
	}

	/**
	 * Dynamic value for the routing step — high urgency classifications get higher value.
	 */
	@Cost(name = "routingValue")
	public double routingValue(@Nullable TicketClassification classification) {
		if (classification == null) {
			return 0.3;
		}
		return Math.min(1.0, classification.urgencyScore());
	}

	// ── Step 1: Classify ─────────────────────────────────────────────────

	/**
	 * Classifies the incoming ticket into a category (billing / technical / general) and
	 * assigns an urgency score.
	 *
	 * <p>
	 * {@code valueMethod = "classificationValue"}: The Utility planner calls the
	 * {@link #classificationValue} method at planning time to determine how valuable it
	 * is to run this action now. Actions with higher net value are selected first.
	 */
	@Action(description = "Classify the support ticket into billing, technical, or general category with an urgency score.",
			valueMethod = "classificationValue")
	public TicketClassification classifyTicket(SupportTicket ticket, OperationContext context) {
		log.info("Classifying ticket: {} priority={}", ticket.ticketId(), ticket.priority());

		var prompt = """
				You are a customer support ticket classifier.

				Classify the ticket below into one of: billing, technical, general.
				Assign an urgency score from 0.0 (not urgent) to 1.0 (critical).

				Ticket ID: %s
				Subject: %s
				Description: %s
				Channel: %s
				Priority: %s

				Return:
				1. category — "billing", "technical", or "general"
				2. subcategory — more specific label (e.g. "payment failure", "login issue", "product question")
				3. urgencyScore — 0.0–1.0
				4. classificationRationale — one sentence explaining the classification
				""".formatted(ticket.ticketId(), ticket.subject(), ticket.description(), ticket.channel(),
				ticket.priority());

		return context.ai().withDefaultLlm().creating(TicketClassification.class).fromPrompt(prompt);
	}

	// ── Step 2: Route to @State ────────────────────────────────────────────

	/**
	 * Routes the classified ticket to the appropriate @State handler.
	 *
	 * <p>
	 * Returns one of three @State-annotated types based on the classification category.
	 * The planner transitions into the returned state and runs the @Action methods
	 * defined inside that state record.
	 *
	 * <p>
	 * {@code valueMethod = "routingValue"}: Dynamic value based on urgency ensures that
	 * urgent tickets are routed immediately while low-priority ones yield to any other
	 * available high-value action.
	 */
	@Action(description = "Route the classified ticket to the appropriate category-specific state handler.",
			valueMethod = "routingValue")
	public TicketCategory routeToCategory(SupportTicket ticket, TicketClassification classification) {
		log.info("Routing ticket {} to category: {}", ticket.ticketId(), classification.category());

		return switch (classification.category().toLowerCase()) {
			case "billing" -> new BillingState(new BillingTicket(ticket.ticketId(), classification, ticket));
			case "technical" -> new TechnicalState(new TechnicalTicket(ticket.ticketId(), classification, ticket));
			default -> new GeneralState(new GeneralTicket(ticket.ticketId(), classification, ticket));
		};
	}

	// ── @State marker interface (required so routeToCategory return type is
	// discoverable) ──

	/**
	 * Marker interface annotated with {@link State} so the framework's classpath scan
	 * triggered by the {@code routeToCategory} return type discovers all three
	 * implementing records ({@link BillingState}, {@link TechnicalState},
	 * {@link GeneralState}) and registers their {@code @Action}/{@code @AchievesGoal}
	 * methods.
	 */
	@State
	public interface TicketCategory {

	}

	// ── @State: Billing ───────────────────────────────────────────────────

	/**
	 * State for billing-related tickets.
	 *
	 * <p>
	 * {@link State} tells the framework that when a {@link BillingTicket} is produced,
	 * the agent transitions into this state and the GOAP planner within the state takes
	 * over. The {@code handleBilling} action inside this state is the terminal step.
	 */
	@State
	public record BillingState(BillingTicket ticket) implements TicketCategory {

		@AchievesGoal(description = "Resolve a billing-related support ticket with a tailored response template.",
				tags = { "billing", "finance" },
				examples = { "I was charged twice", "My invoice is incorrect", "Update my payment method" })
		@Action(description = "Handle the billing ticket: generate response template and escalation guidance.")
		public TicketResolution handleBilling(OperationContext context) {
			var classification = ticket.classification();
			var original = ticket.original();

			var prompt = """
					You are a billing support specialist.

					Resolve the following billing ticket.

					Subject: %s
					Description: %s
					Subcategory: %s
					Urgency: %.2f

					Return:
					1. resolvedCategory — "billing"
					2. responseTemplate — a professional customer-facing response (2–3 sentences)
					3. suggestedActions — array of 2–4 internal action items for the support agent
					4. escalationTarget — "billing-team" if urgency > 0.7, else "auto-resolve"
					5. autoResolvable — true if a standard template resolves it, false if human review needed
					""".formatted(original.subject(), original.description(), classification.subcategory(),
					classification.urgencyScore());

			var resolution = context.ai().withDefaultLlm().creating(TicketResolution.class).fromPrompt(prompt);
			return new TicketResolution(ticket.ticketId(), "billing", resolution.responseTemplate(),
					resolution.suggestedActions(), resolution.escalationTarget(), resolution.autoResolvable());
		}
	}

	// ── @State: Technical ─────────────────────────────────────────────────

	/**
	 * State for technical / product issue tickets.
	 */
	@State
	public record TechnicalState(TechnicalTicket ticket) implements TicketCategory {

		@AchievesGoal(description = "Resolve a technical support ticket with debugging steps and escalation path.",
				tags = { "technical", "engineering" },
				examples = { "My login is broken", "The app crashes on startup", "Feature X is not working" })
		@Action(description = "Handle the technical ticket: generate debugging steps and escalation guidance.")
		public TicketResolution handleTechnical(OperationContext context) {
			var classification = ticket.classification();
			var original = ticket.original();

			var prompt = """
					You are a technical support engineer.

					Resolve the following technical ticket.

					Subject: %s
					Description: %s
					Subcategory: %s
					Urgency: %.2f

					Return:
					1. resolvedCategory — "technical"
					2. responseTemplate — a helpful technical response with first-step debugging instructions
					3. suggestedActions — array of 2–4 debugging / triage steps for the agent
					4. escalationTarget — "engineering-team" if urgency > 0.8, else "tier-2-support"
					5. autoResolvable — true if a known solution template applies
					""".formatted(original.subject(), original.description(), classification.subcategory(),
					classification.urgencyScore());

			var resolution = context.ai().withDefaultLlm().creating(TicketResolution.class).fromPrompt(prompt);
			return new TicketResolution(ticket.ticketId(), "technical", resolution.responseTemplate(),
					resolution.suggestedActions(), resolution.escalationTarget(), resolution.autoResolvable());
		}
	}

	// ── @State: General ───────────────────────────────────────────────────

	/**
	 * State for general enquiry tickets.
	 */
	@State
	public record GeneralState(GeneralTicket ticket) implements TicketCategory {

		@AchievesGoal(description = "Resolve a general enquiry ticket with an informative response.",
				tags = { "general", "enquiry" },
				examples = { "How do I update my profile?", "What are your business hours?",
						"Where can I find your pricing?" })
		@Action(description = "Handle the general enquiry: generate an informative response template.")
		public TicketResolution handleGeneral(OperationContext context) {
			var classification = ticket.classification();
			var original = ticket.original();

			var prompt = """
					You are a customer support agent handling general enquiries.

					Resolve the following general ticket.

					Subject: %s
					Description: %s
					Subcategory: %s
					Urgency: %.2f

					Return:
					1. resolvedCategory — "general"
					2. responseTemplate — a friendly, helpful response (2–3 sentences)
					3. suggestedActions — array of 1–3 follow-up actions
					4. escalationTarget — "auto-resolve" (general tickets auto-close)
					5. autoResolvable — true for standard FAQs, false for unusual requests
					""".formatted(original.subject(), original.description(), classification.subcategory(),
					classification.urgencyScore());

			var resolution = context.ai().withDefaultLlm().creating(TicketResolution.class).fromPrompt(prompt);
			return new TicketResolution(ticket.ticketId(), "general", resolution.responseTemplate(),
					resolution.suggestedActions(), resolution.escalationTarget(), resolution.autoResolvable());
		}
	}

}
