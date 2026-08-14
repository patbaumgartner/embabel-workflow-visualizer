package com.patbaumgartner.embabel.workflow.visualizer;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

import java.io.File;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

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

	/**
	 * The processor copies the property Javadoc into the metadata verbatim, so an inline
	 * tag such as {@literal {@code x}} reaches the IDE tooltip as literal markup rather
	 * than as the word it was meant to render.
	 */
	@Test
	void configurationMetadataDocumentationIsFreeOfJavadocMarkup() throws Exception {
		String metadata = Files.readString(artifactRoot().resolve("META-INF/spring-configuration-metadata.json"),
				StandardCharsets.UTF_8);

		assertThat(metadata).doesNotContain("{@");
	}

	/**
	 * Without a declared default the IDE offers the property with no indication of what
	 * leaving it alone does, which for {@code enabled} is the difference between an
	 * exposed endpoint and a disabled one.
	 */
	@Test
	void configurationMetadataDeclaresTheDefaultOfEveryProperty() throws Exception {
		String metadata = Files.readString(artifactRoot().resolve("META-INF/spring-configuration-metadata.json"),
				StandardCharsets.UTF_8);

		assertThat(propertyIn(metadata, "embabel.workflow.visualizer.enabled")).containsEntry("defaultValue", false);
		assertThat(propertyIn(metadata, "embabel.workflow.visualizer.base-path")).containsEntry("defaultValue",
				EmbabelWorkflowVisualizerProperties.DEFAULT_BASE_PATH);
	}

	/**
	 * Apache License 2.0 section 4(a) requires giving every recipient of the work a copy
	 * of the licence, and the jar is the only artifact most consumers ever receive.
	 */
	@Test
	void shipsItsLicence() {
		assertThat(artifactRoot().resolve("META-INF/LICENSE")).exists();
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> propertyIn(String metadata, String propertyName) {
		List<Map<String, Object>> properties = (List<Map<String, Object>>) JsonMapper.builder()
			.build()
			.readValue(metadata, Map.class)
			.get("properties");
		return properties.stream()
			.filter(property -> propertyName.equals(property.get("name")))
			.findFirst()
			.orElseThrow(() -> new AssertionError("No metadata for property " + propertyName));
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
