package com.patbaumgartner.embabel.moderation;

import com.embabel.agent.api.invocation.AgentInvocation;
import com.embabel.agent.core.AgentPlatform;
import com.patbaumgartner.embabel.moderation.ContentModels.ApiContentRequest;
import com.patbaumgartner.embabel.moderation.ContentModels.ModerationDecision;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/moderation")
public class ContentController {

    private final AgentPlatform agentPlatform;

    public ContentController(AgentPlatform agentPlatform) {
        this.agentPlatform = agentPlatform;
    }

    @PostMapping("/evaluate")
    public ModerationDecision evaluate(@Valid @RequestBody ApiContentRequest request) {
        return AgentInvocation.create(agentPlatform, ModerationDecision.class).invoke(request.toDomain());
    }

}
