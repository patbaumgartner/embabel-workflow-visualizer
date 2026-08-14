package com.patbaumgartner.embabel.workflow.visualizer;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.annotation.Condition;
import com.embabel.agent.api.annotation.State;
import com.embabel.agent.api.common.OperationContext;

/**
 * Test fixture for {@code @State} routing, shaped like the sample application's
 * {@code TicketRoutingAgent}.
 *
 * <p>
 * Embabel reaches a state by following an action's return type, so this declares a
 * routing action returning a {@code @State} marker interface, one implementation that
 * inherits its state-ness rather than declaring it, one that declares it, a state nothing
 * ever returns, and an action returning {@code Object} for reasons of its own.
 */
@Agent(name = "state-routing-agent", description = "Agent routing through @State records")
public class StateRoutingSampleAgent {

	public record Request(String id) {
	}

	public record BillingTicket(String id) {
	}

	public record TechnicalTicket(String id) {
	}

	/**
	 * The branches {@code route} may take. Implementations are states because this is.
	 */
	@State
	public interface Branch {

	}

	@Action(description = "Route the request to the state that handles it")
	public Branch route(Request request) {
		return new BillingState(new BillingTicket(request.id()));
	}

	/**
	 * Returns {@code Object} for its own reasons and routes nowhere. Every state below is
	 * assignable to {@code Object}, so this is what would expose a scan that walks
	 * assignability without a type to anchor it.
	 */
	@Action(description = "Render the request for logging")
	public Object describe(Request request) {
		return request.id();
	}

	/** Carries no {@code @State} of its own: it is a state because {@link Branch} is. */
	public record BillingState(BillingTicket ticket) implements Branch {

		@Action(description = "Handle the billing branch")
		@AchievesGoal(description = "Billing request resolved")
		public String handleBilling(OperationContext context) {
			return "billed " + this.ticket.id();
		}

		/** Not an {@code @Action}, so Embabel never registers it. */
		@Condition(name = "billingIsUrgent")
		public boolean urgent() {
			return false;
		}
	}

	@State
	public record TechnicalState(TechnicalTicket ticket) implements Branch {

		@Action(description = "Handle the technical branch")
		@AchievesGoal(description = "Technical request resolved")
		public String handleTechnical(OperationContext context) {
			return "fixed " + this.ticket.id();
		}
	}

	/** A state no action returns, which Embabel therefore never unrolls. */
	@State
	public record UnreachableState(Request request) {

		@Action(description = "Handle a branch nothing routes to")
		@AchievesGoal(description = "Unreachable goal")
		public String handleUnreachable(OperationContext context) {
			return "never";
		}
	}

}
