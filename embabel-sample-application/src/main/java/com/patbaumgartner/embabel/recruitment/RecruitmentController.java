package com.patbaumgartner.embabel.recruitment;

import com.embabel.agent.api.invocation.AgentInvocation;
import com.embabel.agent.core.AgentPlatform;
import com.patbaumgartner.embabel.recruitment.RecruitmentModels.ApiCandidateRequest;
import com.patbaumgartner.embabel.recruitment.RecruitmentModels.HiringDecision;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/recruitment")
public class RecruitmentController {

    private final AgentPlatform agentPlatform;

    public RecruitmentController(AgentPlatform agentPlatform) {
        this.agentPlatform = agentPlatform;
    }

    @PostMapping("/screen")
    public HiringDecision screen(@Valid @RequestBody ApiCandidateRequest request) {
        return AgentInvocation.create(agentPlatform, HiringDecision.class).invoke(request.toDomain());
    }

}
