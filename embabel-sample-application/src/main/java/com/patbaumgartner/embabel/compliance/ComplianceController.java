package com.patbaumgartner.embabel.compliance;

import com.embabel.agent.api.invocation.AgentInvocation;
import com.embabel.agent.core.AgentPlatform;
import com.patbaumgartner.embabel.compliance.ComplianceModels.ApiReviewRequest;
import com.patbaumgartner.embabel.compliance.ComplianceModels.ComplianceVerdict;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/compliance")
public class ComplianceController {

	private final AgentPlatform agentPlatform;

	public ComplianceController(AgentPlatform agentPlatform) {
		this.agentPlatform = agentPlatform;
	}

	@PostMapping("/review")
	public ComplianceVerdict review(@Valid @RequestBody ApiReviewRequest request) {
		return AgentInvocation.create(agentPlatform, ComplianceVerdict.class).invoke(request.toDomain());
	}

}
