package com.patbaumgartner.embabel.workflow.visualizer;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class EmbabelWorkflowVisualizerPropertiesTests {

	private final EmbabelWorkflowVisualizerProperties properties = new EmbabelWorkflowVisualizerProperties();

	@Test
	void isDisabledByDefaultAndMountedOnTheDocumentedPath() {
		assertThat(this.properties.isEnabled()).isFalse();
		assertThat(this.properties.getBasePath()).isEqualTo("/embabel-workflows");
	}

	@Test
	void acceptsAMultiSegmentBasePath() {
		this.properties.setBasePath("/internal/agent-flows");

		assertThat(this.properties.getBasePath()).isEqualTo("/internal/agent-flows");
	}

	/**
	 * A malformed base path would produce a broken or ambiguous request mapping (for
	 * example {@code /embabel-workflows//api}). Rejecting it at binding time turns a
	 * confusing 404 into a startup failure that names the offending property.
	 */
	@Test
	void rejectsBasePathsThatWouldProduceABrokenMapping() {
		assertThatIllegalArgumentException().isThrownBy(() -> this.properties.setBasePath("embabel-workflows"))
			.withMessageContaining("must start with '/'");
		assertThatIllegalArgumentException().isThrownBy(() -> this.properties.setBasePath("/embabel-workflows/"))
			.withMessageContaining("must not end with '/'");
		assertThatIllegalArgumentException().isThrownBy(() -> this.properties.setBasePath("/"))
			.withMessageContaining("must name a path segment");
		assertThatIllegalArgumentException().isThrownBy(() -> this.properties.setBasePath("  "))
			.withMessageContaining("must not be blank");
	}

}
