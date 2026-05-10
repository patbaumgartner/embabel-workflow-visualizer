package com.patbaumgartner.embabel.story;

import com.patbaumgartner.embabel.story.StoryModels.ApiStoryRequest;
import com.patbaumgartner.embabel.story.StoryModels.FinalStory;
import com.patbaumgartner.embabel.story.StoryModels.StoryRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

/**
 * REST controller for the Story Writer agent.
 *
 * <p>
 * Triggers the Story Writer flow which demonstrates:
 * <ul>
 * <li>{@code @Action(canRerun = true)} — iterative draft-revise loop</li>
 * <li>{@code @LlmTool} on domain and agent methods</li>
 * <li>{@code PersonaSpec} as a PromptContributor</li>
 * <li>{@code LlmOptions.withTemperature()} — per-action temperature tuning</li>
 * <li>{@code ActionException.Transient / Permanent} — retry classification</li>
 * </ul>
 *
 * <p>
 * See {@code src/main/resources/requests/story.http} for sample HTTP requests.
 */
@RestController
@RequestMapping("/api/story")
public class StoryController {

	@PostMapping("/write")
	public ResponseEntity<FinalStory> writeStory(@Valid @RequestBody ApiStoryRequest request) {
		// In production this would delegate to the AgentPlatform to run the agent.
		// For demonstration purposes the endpoint is defined for API visibility.
		StoryRequest storyRequest = request.toDomain();
		return ResponseEntity.accepted()
			.body(new FinalStory(storyRequest.storyId(), "(pending)", "(pending — agent runs async)", 0, 0, 0));
	}

}
