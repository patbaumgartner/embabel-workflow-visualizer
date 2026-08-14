package com.patbaumgartner.embabel.workflow.visualizer;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.Assert;

/**
 * Configuration for the Embabel Workflow Visualizer.
 *
 * <p>
 * The REST API and the UI page are off by default: they describe an application's
 * internal structure — agent class names, method names, prompts' goal descriptions — and
 * a starter should not publish that on a consumer's behalf without an explicit opt-in.
 */
@ConfigurationProperties("embabel.workflow.visualizer")
public class EmbabelWorkflowVisualizerProperties {

	/**
	 * Path the UI and REST API are mounted under. Also used as the compile-time default
	 * of the request-mapping placeholder, so both stay in sync.
	 */
	public static final String DEFAULT_BASE_PATH = "/embabel-workflows";

	/**
	 * Placeholder resolved by Spring MVC when mapping the visualizer controllers. Must be
	 * a constant expression to be usable in an annotation.
	 */
	static final String BASE_PATH_PLACEHOLDER = "${embabel.workflow.visualizer.base-path:" + DEFAULT_BASE_PATH + "}";

	/**
	 * Whether to serve the visualization UI and its REST API. The /actuator/embabel
	 * endpoint is governed by the usual actuator exposure properties instead.
	 */
	private boolean enabled = false;

	/**
	 * Path the UI is served from; the REST API is served from that path plus /api. Change
	 * it to avoid a collision with an application's own routes.
	 */
	private String basePath = DEFAULT_BASE_PATH;

	public boolean isEnabled() {
		return this.enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public String getBasePath() {
		return this.basePath;
	}

	public void setBasePath(String basePath) {
		Assert.hasText(basePath, "'embabel.workflow.visualizer.base-path' must not be blank");
		Assert.isTrue(basePath.startsWith("/"), "'embabel.workflow.visualizer.base-path' must start with '/'");
		Assert.isTrue(basePath.length() > 1 && !basePath.endsWith("/"),
				"'embabel.workflow.visualizer.base-path' must name a path segment and must not end with '/'");
		this.basePath = basePath;
	}

}
