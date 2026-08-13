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
			.run(ctx -> assertThat(ctx).hasFailed());
	}

	@Test
	void backsOffWhenUserBeansArePresent() {
		this.webRunner.withUserConfiguration(CustomBeansConfig.class).run(ctx -> {
			assertThat(ctx).hasSingleBean(EmbabelWorkflowCatalogService.class);
			assertThat(ctx.getBean(EmbabelWorkflowCatalogService.class)).isSameAs(ctx.getBean("customCatalogService"));
		});
	}

	@Configuration
	static class CustomBeansConfig {

		@Bean
		EmbabelWorkflowCatalogService customCatalogService(ApplicationContext applicationContext) {
			return new EmbabelWorkflowCatalogService(applicationContext);
		}

	}

}
