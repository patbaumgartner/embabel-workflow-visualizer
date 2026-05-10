package com.patbaumgartner.embabel.ticketing;

import com.patbaumgartner.embabel.ticketing.TicketModels.SupportTicket;
import com.patbaumgartner.embabel.ticketing.TicketModels.TicketClassification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Unit tests for {@link TicketRoutingAgent}.
 *
 * <p>
 * Covers pure-Java methods used by the Utility planner:
 * <ul>
 * <li>{@code classificationValue()} — @Cost method: priority-based dynamic value</li>
 * <li>{@code routingValue()} — @Cost method: urgency-score-based dynamic value</li>
 * <li>{@code routeToCategory()} — @Action: routing to correct @State type</li>
 * </ul>
 */
@DisplayName("TicketRoutingAgent — Utility planner @Cost and routing unit tests")
class TicketRoutingAgentTest {

	private TicketRoutingAgent agent;

	@BeforeEach
	void setUp() {
		agent = new TicketRoutingAgent();
	}

	// ── classificationValue (@Cost) ────────────────────────────────────────

	@Nested
	@DisplayName("classificationValue() — @Cost method for classification action")
	class ClassificationValue {

		@Test
		@DisplayName("returns 1.0 for critical priority")
		void criticalPriority() {
			var ticket = new SupportTicket("t-1", "cust-1", "App down", "Outage", "web", "critical");
			assertThat(agent.classificationValue(ticket)).isEqualTo(1.0, within(0.001));
		}

		@Test
		@DisplayName("returns 0.8 for high priority")
		void highPriority() {
			var ticket = new SupportTicket("t-2", "cust-2", "Login broken", "Can't login", "email", "high");
			assertThat(agent.classificationValue(ticket)).isEqualTo(0.8, within(0.001));
		}

		@Test
		@DisplayName("returns 0.6 for medium priority")
		void mediumPriority() {
			var ticket = new SupportTicket("t-3", "cust-3", "Slow load", "Page slow", "chat", "medium");
			assertThat(agent.classificationValue(ticket)).isEqualTo(0.6, within(0.001));
		}

		@Test
		@DisplayName("returns 0.4 for low/unknown priority")
		void lowOrUnknownPriority() {
			var ticket = new SupportTicket("t-4", "cust-4", "Question", "How to?", "web", "low");
			assertThat(agent.classificationValue(ticket)).isEqualTo(0.4, within(0.001));
		}

		@Test
		@DisplayName("returns default 0.5 for null ticket (planner pre-check)")
		void defaultForNullTicket() {
			assertThat(agent.classificationValue(null)).isEqualTo(0.5, within(0.001));
		}

		@ParameterizedTest(name = "priority={0} expected={1}")
		@CsvSource({ "CRITICAL, 1.0", "HIGH,     0.8", "MEDIUM,   0.6", "normal,   0.4" })
		@DisplayName("priority matching is case-insensitive")
		void caseInsensitive(String priority, double expected) {
			var ticket = new SupportTicket("t-p", "c-1", "Subject", "Body", "web", priority);
			assertThat(agent.classificationValue(ticket)).isEqualTo(expected, within(0.001));
		}

	}

	// ── routingValue (@Cost) ──────────────────────────────────────────────

	@Nested
	@DisplayName("routingValue() — @Cost method for routing action")
	class RoutingValue {

		@Test
		@DisplayName("returns urgencyScore directly when between 0.0 and 1.0")
		void returnsUrgencyScoreDirectly() {
			var classification = new TicketClassification("t-5", "billing", "payment failure", 0.7, "Billing issue");
			assertThat(agent.routingValue(classification)).isEqualTo(0.7, within(0.001));
		}

		@Test
		@DisplayName("caps value at 1.0 for urgencyScore above 1.0")
		void capsAtOne() {
			var classification = new TicketClassification("t-6", "technical", "outage", 1.5, "Critical outage");
			assertThat(agent.routingValue(classification)).isEqualTo(1.0, within(0.001));
		}

		@Test
		@DisplayName("returns 0.3 for null classification (planner pre-check)")
		void defaultForNullClassification() {
			assertThat(agent.routingValue(null)).isEqualTo(0.3, within(0.001));
		}

		@Test
		@DisplayName("returns 0.0 urgency score when urgency is 0")
		void zeroUrgency() {
			var classification = new TicketClassification("t-7", "general", "faq", 0.0, "No urgency");
			assertThat(agent.routingValue(classification)).isEqualTo(0.0, within(0.001));
		}

	}

	// ── routeToCategory ───────────────────────────────────────────────────

	@Nested
	@DisplayName("routeToCategory() — routes to correct @State type")
	class RouteToCategory {

		private final SupportTicket ticket = new SupportTicket("t-route", "cust-route", "Subject", "Body", "web",
				"medium");

		@Test
		@DisplayName("routes 'billing' category to BillingState @State wrapper")
		void routesToBillingState() {
			var classification = new TicketClassification("t-route", "billing", "refund", 0.5, "billing");
			var result = agent.routeToCategory(ticket, classification);
			assertThat(result).isInstanceOf(TicketRoutingAgent.BillingState.class);
		}

		@Test
		@DisplayName("routes 'technical' category to TechnicalState @State wrapper")
		void routesToTechnicalState() {
			var classification = new TicketClassification("t-route", "technical", "crash", 0.8, "technical");
			var result = agent.routeToCategory(ticket, classification);
			assertThat(result).isInstanceOf(TicketRoutingAgent.TechnicalState.class);
		}

		@Test
		@DisplayName("routes 'general' category to GeneralState @State wrapper")
		void routesToGeneralState() {
			var classification = new TicketClassification("t-route", "general", "faq", 0.2, "general");
			var result = agent.routeToCategory(ticket, classification);
			assertThat(result).isInstanceOf(TicketRoutingAgent.GeneralState.class);
		}

		@Test
		@DisplayName("routes unknown category to GeneralState @State wrapper (default)")
		void routesUnknownToGeneral() {
			var classification = new TicketClassification("t-route", "unknown", "other", 0.3, "other");
			var result = agent.routeToCategory(ticket, classification);
			assertThat(result).isInstanceOf(TicketRoutingAgent.GeneralState.class);
		}

		@Test
		@DisplayName("routing is case-insensitive for category names")
		void caseInsensitiveRouting() {
			var billing = new TicketClassification("t-1", "BILLING", "fee", 0.4, "billing");
			var technical = new TicketClassification("t-2", "Technical", "bug", 0.7, "technical");

			assertThat(agent.routeToCategory(ticket, billing)).isInstanceOf(TicketRoutingAgent.BillingState.class);
			assertThat(agent.routeToCategory(ticket, technical)).isInstanceOf(TicketRoutingAgent.TechnicalState.class);
		}

		@Test
		@DisplayName("BillingState @State wrapper contains correct ticket data")
		void billingStateContainsCorrectData() {
			var classification = new TicketClassification("t-route", "billing", "invoice", 0.6, "billing");
			var state = (TicketRoutingAgent.BillingState) agent.routeToCategory(ticket, classification);
			var result = state.ticket();
			assertThat(result.ticketId()).isEqualTo("t-route");
			assertThat(result.original()).isEqualTo(ticket);
			assertThat(result.classification()).isEqualTo(classification);
		}

	}

}
