package com.patbaumgartner.embabel.research;

import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.EmbabelComponent;
import com.embabel.agent.api.common.OperationContext;
import com.patbaumgartner.embabel.research.ResearchModels.MarketData;
import com.patbaumgartner.embabel.research.ResearchModels.ResearchRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared research utilities — demonstrates {@code @EmbabelComponent}.
 *
 * <p>
 * {@code @EmbabelComponent} marks this class as a shared Embabel component. Unlike
 * {@code @Agent}, an {@code @EmbabelComponent} is not an agent itself — it contributes
 * reusable {@code @Action} steps that other agents can depend on. The framework scans for
 * and registers its actions alongside the host agent's own actions.
 *
 * <p>
 * In this sample, {@code ResearchUtils} provides the {@link #gatherMarketData} action
 * which is used by the {@link ProductResearchAgent} rather than duplicating the logic
 * inside the agent class itself. This makes the market data gathering step reusable
 * across multiple potential agents.
 */
@EmbabelComponent
public class ResearchUtils {

	private static final Logger log = LoggerFactory.getLogger(ResearchUtils.class);

	/**
	 * Gathers high-level market data for a product category.
	 *
	 * <p>
	 * This action is declared in an {@link EmbabelComponent} rather than the agent,
	 * demonstrating how shared, reusable @Action steps can be extracted from agents for
	 * reuse across multiple agent definitions.
	 *
	 * <p>
	 * {@code outputBinding = "marketData"} — explicitly names the blackboard binding for
	 * the produced {@link MarketData} object. By default the framework uses the simple
	 * class name as the binding; providing an explicit name is useful when the same type
	 * could appear multiple times on the blackboard with different semantic roles.
	 */
	@Action(description = "Gather high-level market size, growth rate, and trend data for the requested product category.",
			outputBinding = "marketData")
	public MarketData gatherMarketData(ResearchRequest request, OperationContext context) {
		log.info("Gathering market data for category='{}' market='{}'", request.productCategory(),
				request.targetMarket());

		var prompt = """
				You are a market research analyst. Provide a market snapshot for the following:
				Product Category: %s
				Target Market: %s

				Return a JSON object with these fields:
				- requestId: "%s"
				- marketSizeUsd: estimated market size in USD billions (numeric)
				- annualGrowthRate: estimated compound annual growth rate percent (numeric)
				- keyTrends: array of 3–5 key market trends (strings)
				- confidenceScore: your confidence in this data from 0.0 to 1.0
				- dataSummary: 2–3 sentence narrative overview

				Base your confidence on data availability. Well-established markets score higher.
				""".formatted(request.productCategory(), request.targetMarket(), request.requestId());

		return context.ai().withDefaultLlm().creating(MarketData.class).fromPrompt(prompt);
	}

}
