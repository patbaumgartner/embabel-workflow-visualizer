package com.patbaumgartner.embabel.workflow.visualizer;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.annotation.Provided;
import com.embabel.agent.api.common.OperationContext;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Optional;

/**
 * Test fixture for inputs the planner may leave unfilled.
 *
 * <p>
 * Embabel binds a {@code @Nullable} parameter to {@code null} when nothing on the
 * blackboard matches, and does not care which {@code @Nullable}. This declares one of its
 * own — a parameter annotation from a package nobody has heard of — beside JSpecify's
 * type-use one and an {@code Optional<T>}, which is the third way of saying the same.
 */
@Agent(name = "optional-input-agent", description = "Agent with inputs the planner may leave unfilled")
public class OptionalInputSampleAgent {

	@Target(ElementType.PARAMETER)
	@Retention(RetentionPolicy.RUNTIME)
	public @interface Nullable {

	}

	public record Request(String text) {
	}

	public record Draft(String text) {
	}

	public record Notes(String text) {
	}

	public record Hints(String text) {
	}

	public record Review(String text) {
	}

	public record Summary(String text) {
	}

	@Action(description = "Draft from the request")
	public Draft draft(Request request) {
		return new Draft(request.text());
	}

	@Action(description = "Review the draft when asked to")
	public Review review(Draft draft, @Provided Notes notes) {
		return new Review(draft.text());
	}

	@AchievesGoal(description = "Summarise the draft with whatever else is around")
	@Action
	public Summary summarize(Draft draft, @Nullable Notes notes, Optional<Hints> hints,
			@org.jspecify.annotations.Nullable Review review, OperationContext context) {
		return new Summary(draft.text());
	}

}
