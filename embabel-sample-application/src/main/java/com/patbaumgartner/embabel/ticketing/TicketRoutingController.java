package com.patbaumgartner.embabel.ticketing;

import com.embabel.agent.api.invocation.AgentInvocation;
import com.embabel.agent.core.AgentPlatform;
import com.patbaumgartner.embabel.ticketing.TicketModels.ApiSupportTicket;
import com.patbaumgartner.embabel.ticketing.TicketModels.TicketResolution;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tickets")
public class TicketRoutingController {

	private final AgentPlatform agentPlatform;

	public TicketRoutingController(AgentPlatform agentPlatform) {
		this.agentPlatform = agentPlatform;
	}

	@PostMapping("/route")
	public TicketResolution route(@Valid @RequestBody ApiSupportTicket request) {
		return AgentInvocation.create(agentPlatform, TicketResolution.class).invoke(request.toDomain());
	}

}
