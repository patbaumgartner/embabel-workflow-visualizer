package com.patbaumgartner.embabel.workflow.visualizer;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.endpoint.EndpointAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class EmbabelWorkflowVisualizerAutoConfigurationTests {

	private final WebApplicationContextRunner webRunner = new WebApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(EmbabelWorkflowVisualizerAutoConfiguration.class,
				WebMvcAutoConfiguration.class, DispatcherServletAutoConfiguration.class,
				EndpointAutoConfiguration.class, WebEndpointAutoConfiguration.class));

	@Test
	void apiControllerAndUiAreAbsentByDefault() {
		webRunner.run(ctx -> {
			assertThat(ctx).hasSingleBean(EmbabelWorkflowCatalogService.class);
			assertThat(ctx).doesNotHaveBean(EmbabelWorkflowApiController.class);
			assertThat(ctx).doesNotHaveBean(WorkflowVisualizerPageController.class);
		});
	}

	@Test
	void apiAndUiAreRegisteredWhenEnabled() {
		webRunner.withPropertyValues("embabel.workflow.visualizer.enabled=true").run(ctx -> {
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
		webRunner.run(ctx -> {
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

	@Test
	void backsOffWhenUserBeansArePresent() {
		webRunner.withUserConfiguration(CustomBeansConfig.class).run(ctx -> {
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
