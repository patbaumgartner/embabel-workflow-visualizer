package com.patbaumgartner.embabel.ticketing;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Domain model for the Ticket Routing Agent.
 *
 * <p>
 * Flow (Utility planner + @State routing):
 *
 * <pre>
 * SupportTicket
 *   → [classifyTicket]                                → TicketClassification
 *     → (BillingState | TechnicalState | GeneralState)  [via @State + Utility routing]
 *       → [handle*()] @AchievesGoal                   → TicketResolution
 * </pre>
 */
public class TicketModels {

	// ── Input ─────────────────────────────────────────────────────────────

	/**
	 * Incoming support ticket from the API.
	 */
	public record SupportTicket(String ticketId, String customerId, String subject, String description, String channel,
			String priority) {
	}

	// ── Intermediate ──────────────────────────────────────────────────────

	/**
	 * Classification result — determines which @State branch is entered.
	 */
	public record TicketClassification(String ticketId, String category, String subcategory, double urgencyScore,
			String classificationRationale) {
	}

	/**
	 * @State: billing-related tickets.
	 */
	public record BillingTicket(String ticketId, TicketClassification classification, SupportTicket original) {
	}

	/**
	 * @State: technical / product issue tickets.
	 */
	public record TechnicalTicket(String ticketId, TicketClassification classification, SupportTicket original) {
	}

	/**
	 * @State: general enquiry tickets.
	 */
	public record GeneralTicket(String ticketId, TicketClassification classification, SupportTicket original) {
	}

	// ── Output ────────────────────────────────────────────────────────────

	/**
	 * Final ticket resolution — goal of the agent.
	 */
	public record TicketResolution(String ticketId, String resolvedCategory, String responseTemplate,
			String[] suggestedActions, String escalationTarget, boolean autoResolvable) {
	}

	// ── REST API types ────────────────────────────────────────────────────

	public record ApiSupportTicket(@NotBlank String ticketId, @NotBlank String customerId, @NotBlank String subject,
			@NotBlank String description, String channel, @NotNull String priority) {
		public SupportTicket toDomain() {
			return new SupportTicket(ticketId, customerId, subject, description, channel != null ? channel : "web",
					priority);
		}
	}

}
