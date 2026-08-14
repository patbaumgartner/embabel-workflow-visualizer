package com.patbaumgartner.embabel.workflow.visualizer;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.annotation.State;
import com.embabel.agent.api.common.OperationContext;

/**
 * Test fixture for {@code @State} routing, shaped like the sample application's
 * {@code TicketRoutingAgent}.
 *
 * <p>
 * A routing action whose declared return type says nothing useful, and two {@code @State}
 * records that each carry the terminal step for their branch. The interesting parts are
 * what the diagram cannot get from a signature: which concrete types {@code route} may
 * really produce, and that a handler taking only an {@code OperationContext} still
 * consumes the ticket its state holds.
 */
@Agent(name = "state-routing-agent", description = "Agent routing through @State records")
public class StateRoutingSampleAgent {

	public record Request(String id) {
	}

	public record BillingTicket(String id) {
	}

	public record TechnicalTicket(String id) {
	}

	@Action(description = "Route the request to the state that handles it")
	public Object route(Request request) {
		return new BillingState(new BillingTicket(request.id()));
	}

	@State
	public record BillingState(BillingTicket ticket) {

		@Action(description = "Handle the billing branch")
		@AchievesGoal(description = "Billing request resolved")
		public String handleBilling(OperationContext context) {
			return "billed " + this.ticket.id();
		}
	}

	@State
	public record TechnicalState(TechnicalTicket ticket) {

		@Action(description = "Handle the technical branch")
		@AchievesGoal(description = "Technical request resolved")
		public String handleTechnical(OperationContext context) {
			return "fixed " + this.ticket.id();
		}
	}

}
