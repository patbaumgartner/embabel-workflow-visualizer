package com.patbaumgartner.embabel.workflow.visualizer;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.File;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the contents of the published starter artifact.
 *
 * <p>
 * A starter is a library: anything it places at the root of the classpath leaks into
 * every consuming application. These tests fail fast on the packaging mistakes that are
 * easy to make and hard to diagnose downstream.
 */
class StarterPackagingTests {

	/**
	 * Spring Boot resolves {@code classpath:/application.properties} to the
	 * <em>first</em> match on the classpath. A library that ships one can therefore
	 * shadow the consuming application's own configuration depending on classpath
	 * ordering.
	 */
	@Test
	void doesNotShipApplicationPropertiesAtTheClasspathRoot() {
		assertThat(artifactRoot().resolve("application.properties")).doesNotExist();
	}

	/**
	 * {@code classpath:/static/**} is served by Spring Boot from every jar on the
	 * classpath. A page published there would stay reachable with the visualizer
	 * disabled, and would compete for file names with the consuming application.
	 */
	@Test
	void doesNotShipStaticResources() {
		assertThat(artifactRoot().resolve("static")).doesNotExist();
		assertThat(artifactRoot().resolve("public")).doesNotExist();
		assertThat(artifactRoot().resolve("META-INF/resources")).doesNotExist();
	}

	@Test
	void shipsTheVisualizerPageOutsideTheStaticResourceNamespace() {
		assertThat(new ClassPathResource(WorkflowVisualizerPageController.PAGE_RESOURCE).exists()).isTrue();
	}

	/** Drives IDE completion and documentation for embabel.workflow.visualizer.*. */
	@Test
	void shipsConfigurationMetadataForItsProperties() throws Exception {
		Path metadata = artifactRoot().resolve("META-INF/spring-configuration-metadata.json");
		assertThat(metadata).exists();

		assertThat(Files.readString(metadata, StandardCharsets.UTF_8)).contains("embabel.workflow.visualizer.enabled")
			.contains("embabel.workflow.visualizer.base-path");
	}

	@Test
	void registersItsAutoConfigurationForSpringBoot() throws Exception {
		ClassPathResource imports = new ClassPathResource(
				"META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports");
		assertThat(imports.exists()).isTrue();

		List<String> registered = Files.readAllLines(imports.getFile().toPath(), StandardCharsets.UTF_8)
			.stream()
			.map(String::trim)
			.filter(line -> !line.isEmpty() && !line.startsWith("#"))
			.toList();

		assertThat(registered).containsExactly(EmbabelWorkflowVisualizerAutoConfiguration.class.getName());
	}

	/**
	 * Directory the starter's own classes are loaded from ({@code target/classes}). The
	 * jar is assembled from it verbatim, so asserting here also asserts the artifact.
	 */
	private Path artifactRoot() {
		URL location = EmbabelWorkflowVisualizerAutoConfiguration.class.getProtectionDomain()
			.getCodeSource()
			.getLocation();
		try {
			File root = new File(location.toURI());
			assertThat(root).isDirectory();
			return root.toPath();
		}
		catch (URISyntaxException ex) {
			throw new IllegalStateException("Cannot resolve the starter artifact root from " + location, ex);
		}
	}

}
