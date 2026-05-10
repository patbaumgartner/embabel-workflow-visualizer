package com.patbaumgartner.embabel.sentiment;

import com.embabel.agent.api.invocation.AgentInvocation;
import com.embabel.agent.core.AgentPlatform;
import com.patbaumgartner.embabel.sentiment.SentimentModels.ApiFeedbackRequest;
import com.patbaumgartner.embabel.sentiment.SentimentModels.FeedbackResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sentiment")
public class SentimentController {

    private final AgentPlatform agentPlatform;

    public SentimentController(AgentPlatform agentPlatform) {
        this.agentPlatform = agentPlatform;
    }

    @PostMapping("/analyze")
    public FeedbackResponse analyze(@Valid @RequestBody ApiFeedbackRequest request) {
        return AgentInvocation.create(agentPlatform, FeedbackResponse.class).invoke(request.toDomain());
    }

}
