package com.patbaumgartner.embabel.research;

import com.patbaumgartner.embabel.research.ResearchModels.ApiResearchRequest;
import com.patbaumgartner.embabel.research.ResearchModels.ResearchReport;
import com.patbaumgartner.embabel.research.ResearchModels.ResearchRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

/**
 * REST controller for the Product Research agent.
 *
 * <p>
 * Triggers the Product Research flow which demonstrates:
 * <ul>
 * <li>{@code planner = PlannerType.SUPERVISOR} — LLM-driven action selection</li>
 * <li>SpEL preconditions — confidence threshold gates competitor analysis</li>
 * <li>{@code @Action(outputBinding = "marketData")} — named blackboard binding</li>
 * <li>{@code @EmbabelComponent} — shared action in {@link ResearchUtils}</li>
 * </ul>
 *
 * <p>
 * See {@code src/main/resources/requests/research.http} for sample HTTP requests.
 */
@RestController
@RequestMapping("/api/research")
public class ResearchController {

	@PostMapping("/analyze")
	public ResponseEntity<ResearchReport> analyzeMarket(@Valid @RequestBody ApiResearchRequest request) {
		// In production this would delegate to the AgentPlatform to run the agent.
		// For demonstration purposes the endpoint is defined for API visibility.
		ResearchRequest researchRequest = request.toDomain();
		return ResponseEntity.accepted()
			.body(new ResearchReport(researchRequest.requestId(), researchRequest.productCategory(),
					"(pending — agent runs async)", "(pending)", "(pending)", "(pending)", 0.0));
	}

}
