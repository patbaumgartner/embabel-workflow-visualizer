package com.patbaumgartner.embabel.loan;

import com.embabel.agent.api.invocation.AgentInvocation;
import com.embabel.agent.core.AgentPlatform;
import com.patbaumgartner.embabel.loan.LoanModels.ApiLoanRequest;
import com.patbaumgartner.embabel.loan.LoanModels.LoanDecision;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/loan")
public class LoanController {

	private final AgentPlatform agentPlatform;

	public LoanController(AgentPlatform agentPlatform) {
		this.agentPlatform = agentPlatform;
	}

	@PostMapping("/apply")
	public LoanDecision apply(@Valid @RequestBody ApiLoanRequest request) {
		return AgentInvocation.create(agentPlatform, LoanDecision.class).invoke(request.toDomain());
	}

}
