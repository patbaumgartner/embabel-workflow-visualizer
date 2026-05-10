package com.patbaumgartner.embabel.fraud;

import com.embabel.agent.api.invocation.AgentInvocation;
import com.embabel.agent.core.AgentPlatform;
import com.patbaumgartner.embabel.fraud.FraudModels.ApiTransactionRequest;
import com.patbaumgartner.embabel.fraud.FraudModels.FraudDecision;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/fraud")
public class FraudController {

	private final AgentPlatform agentPlatform;

	public FraudController(AgentPlatform agentPlatform) {
		this.agentPlatform = agentPlatform;
	}

	@PostMapping("/detect")
	public FraudDecision detect(@Valid @RequestBody ApiTransactionRequest request) {
		return AgentInvocation.create(agentPlatform, FraudDecision.class).invoke(request.toDomain());
	}

}
