package com.patbaumgartner.embabel.document;

import com.embabel.agent.api.invocation.AgentInvocation;
import com.embabel.agent.core.AgentPlatform;
import com.patbaumgartner.embabel.document.DocumentModels.ApiDocumentRequest;
import com.patbaumgartner.embabel.document.DocumentModels.DocumentSummary;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/documents")
public class DocumentProcessingController {

	private final AgentPlatform agentPlatform;

	public DocumentProcessingController(AgentPlatform agentPlatform) {
		this.agentPlatform = agentPlatform;
	}

	@PostMapping("/process")
	public DocumentSummary process(@Valid @RequestBody ApiDocumentRequest request) {
		Map<String, Object> inputs = new HashMap<>();
		inputs.put("documentRequest", request.toDomain());
		// MetadataHints is optional — put it only when present
		var hints = request.toHints();
		if (hints != null) {
			inputs.put("metadataHints", hints);
		}
		return AgentInvocation.create(agentPlatform, DocumentSummary.class).invoke(inputs);
	}

}
