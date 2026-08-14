package com.patbaumgartner.embabel.workflow.visualizer;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.endpoint.EndpointAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class EmbabelWorkflowVisualizerAutoConfigurationTests {

	private final WebApplicationContextRunner webRunner = new WebApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(EmbabelWorkflowVisualizerAutoConfiguration.class,
				EndpointAutoConfiguration.class, WebEndpointAutoConfiguration.class));

	@Test
	void apiControllerAndUiAreAbsentByDefault() {
		this.webRunner.run(ctx -> {
			assertThat(ctx).hasSingleBean(EmbabelWorkflowCatalogService.class);
			assertThat(ctx).doesNotHaveBean(EmbabelWorkflowApiController.class);
			assertThat(ctx).doesNotHaveBean(WorkflowVisualizerPageController.class);
		});
	}

	@Test
	void apiAndUiAreRegisteredWhenEnabled() {
		this.webRunner.withPropertyValues("embabel.workflow.visualizer.enabled=true").run(ctx -> {
			assertThat(ctx).hasSingleBean(EmbabelWorkflowApiController.class);
			assertThat(ctx).hasSingleBean(WorkflowVisualizerPageController.class);
		});
	}

	@Test
	void allBeansRegisteredWhenFullyEnabled() {
		webRunner
			.withPropertyValues("management.endpoints.web.exposure.include=embabel",
					"embabel.workflow.visualizer.enabled=true")
			.run(ctx -> {
				assertThat(ctx).hasSingleBean(EmbabelWorkflowCatalogService.class);
				assertThat(ctx).hasSingleBean(EmbabelWorkflowApiController.class);
				assertThat(ctx).hasSingleBean(WorkflowVisualizerPageController.class);
				assertThat(ctx).hasSingleBean(EmbabelWorkflowActuatorEndpoint.class);
			});
	}

	@Test
	void actuatorEndpointIsAbsentWhenNotExposed() {
		this.webRunner.run(ctx -> {
			assertThat(ctx).hasSingleBean(EmbabelWorkflowCatalogService.class);
			assertThat(ctx).doesNotHaveBean(EmbabelWorkflowActuatorEndpoint.class);
		});
	}

	@Test
	void doesNotActivateInNonWebContext() {
		new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(EmbabelWorkflowVisualizerAutoConfiguration.class))
			.run(ctx -> {
				assertThat(ctx).doesNotHaveBean(EmbabelWorkflowCatalogService.class);
				assertThat(ctx).doesNotHaveBean(EmbabelWorkflowApiController.class);
				assertThat(ctx).doesNotHaveBean(WorkflowVisualizerPageController.class);
			});
	}

	@Test
	void doesNotActivateInReactiveWebContext() {
		new ReactiveWebApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(EmbabelWorkflowVisualizerAutoConfiguration.class))
			.run(ctx -> assertThat(ctx).doesNotHaveBean(EmbabelWorkflowCatalogService.class));
	}

	private final WebApplicationContextRunner mvcRunner = this.webRunner
		.withConfiguration(AutoConfigurations.of(WebMvcAutoConfiguration.class,
				HttpMessageConvertersAutoConfiguration.class, JacksonAutoConfiguration.class));

	@Test
	void mountsTheUiAndApiOnACustomBasePath() {
		this.mvcRunner
			.withPropertyValues("embabel.workflow.visualizer.enabled=true",
					"embabel.workflow.visualizer.base-path=/internal/agent-flows")
			.run(ctx -> {
				MockMvc mockMvc = MockMvcBuilders
					.webAppContextSetup((WebApplicationContext) ctx.getSourceApplicationContext())
					.build();

				mockMvc.perform(get("/internal/agent-flows")).andExpect(status().isOk());
				mockMvc.perform(get("/internal/agent-flows/api")).andExpect(status().isOk());
				mockMvc.perform(get("/embabel-workflows/api")).andExpect(status().isNotFound());
			});
	}

	@Test
	void refusesToStartOnABasePathThatWouldBreakTheMapping() {
		this.webRunner
			.withPropertyValues("embabel.workflow.visualizer.enabled=true",
					"embabel.workflow.visualizer.base-path=agent-flows/")
			.run(ctx -> assertThat(ctx).hasFailed()
				.getFailure()
				.hasStackTraceContaining("embabel.workflow.visualizer.base-path")
				.hasStackTraceContaining("must start with '/'"));
	}

	/**
	 * Disabled has to mean unreachable, not merely unmapped. This runs with Spring MVC's
	 * static resource handling active, so it would also fail if the page were ever moved
	 * into an auto-served location such as {@code static/} or
	 * {@code META-INF/resources/}, where the resource handler would publish it whatever
	 * this property says.
	 */
	@Test
	void servesNothingAtAllWhileTheVisualizerIsDisabled() {
		this.mvcRunner.run(ctx -> {
			MockMvc mockMvc = MockMvcBuilders
				.webAppContextSetup((WebApplicationContext) ctx.getSourceApplicationContext())
				.build();

			mockMvc.perform(get("/embabel-workflows")).andExpect(status().isNotFound());
			mockMvc.perform(get("/embabel-workflows/api")).andExpect(status().isNotFound());
			mockMvc.perform(get("/workflow-visualizer.html")).andExpect(status().isNotFound());
		});
	}

	/**
	 * The mapping is declared as a raw {@code ${...}} placeholder, so it is worth pinning
	 * that it still honours a relaxed spelling: Spring Boot attaches its configuration
	 * property sources to the environment, which makes placeholder resolution
	 * relaxed-binding aware. Were that to stop holding, the properties bean would report
	 * one path while the UI answered on another.
	 */
	@Test
	void mountsTheUiOnABasePathWrittenInARelaxedSpelling() {
		this.mvcRunner
			.withPropertyValues("embabel.workflow.visualizer.enabled=true",
					"embabel.workflow.visualizer.basePath=/internal/agent-flows")
			.run(ctx -> {
				MockMvc mockMvc = MockMvcBuilders
					.webAppContextSetup((WebApplicationContext) ctx.getSourceApplicationContext())
					.build();

				mockMvc.perform(get("/internal/agent-flows")).andExpect(status().isOk());
				assertThat(ctx.getBean(EmbabelWorkflowVisualizerProperties.class).getBasePath())
					.isEqualTo("/internal/agent-flows");
			});
	}

	@Test
	void acceptsTheCanonicalBasePathSpelling() {
		this.webRunner
			.withPropertyValues("embabel.workflow.visualizer.enabled=true",
					"embabel.workflow.visualizer.base-path=/internal/agent-flows")
			.run(ctx -> assertThat(ctx).hasNotFailed()
				.hasSingleBean(EmbabelWorkflowApiController.class)
				.hasSingleBean(WorkflowVisualizerPageController.class));
	}

	@Test
	void backsOffWhenUserBeansArePresent() {
		this.webRunner.withUserConfiguration(CustomBeansConfig.class).run(ctx -> {
			assertThat(ctx).hasSingleBean(EmbabelWorkflowCatalogService.class);
			assertThat(ctx.getBean(EmbabelWorkflowCatalogService.class)).isSameAs(ctx.getBean("customCatalogService"));
		});
	}

	/**
	 * A consuming application whose base package encloses this one component-scans the
	 * starter's own classes. If either controller carried a {@code @Component} stereotype
	 * the scan would register it directly, sailing past {@code @ConditionalOnProperty}
	 * and serving the UI on an application that had switched it off — which is exactly
	 * what the sample application did.
	 */
	@Test
	void componentScanningTheStarterCannotResurrectTheDisabledControllers() {
		this.webRunner.withUserConfiguration(ScanningConsumer.class).run(ctx -> {
			assertThat(ctx).hasNotFailed();
			assertThat(ctx).doesNotHaveBean(EmbabelWorkflowApiController.class);
			assertThat(ctx).doesNotHaveBean(WorkflowVisualizerPageController.class);
		});
	}

	@Test
	void componentScanningTheStarterStillHonoursAnExplicitOptIn() {
		this.webRunner.withUserConfiguration(ScanningConsumer.class)
			.withPropertyValues("embabel.workflow.visualizer.enabled=true")
			.run(ctx -> {
				assertThat(ctx).hasSingleBean(EmbabelWorkflowApiController.class);
				assertThat(ctx).hasSingleBean(WorkflowVisualizerPageController.class);
			});
	}

	@Configuration
	@ComponentScan("com.patbaumgartner.embabel.workflow.visualizer")
	static class ScanningConsumer {

	}

	@Configuration
	static class CustomBeansConfig {

		@Bean
		EmbabelWorkflowCatalogService customCatalogService(ApplicationContext applicationContext) {
			return new EmbabelWorkflowCatalogService(applicationContext);
		}

	}

}
