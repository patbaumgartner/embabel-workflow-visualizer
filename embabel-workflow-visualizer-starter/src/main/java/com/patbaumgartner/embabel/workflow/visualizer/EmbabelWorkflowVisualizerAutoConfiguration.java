package com.patbaumgartner.embabel.workflow.visualizer;

import org.springframework.boot.actuate.autoconfigure.endpoint.condition.ConditionalOnAvailableEndpoint;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;

/**
 * Spring Boot auto-configuration for the Embabel Workflow Visualizer.
 *
 * <p>
 * Activated automatically when:
 * <ul>
 * <li>A Servlet-based web application is detected on the classpath</li>
 * <li>Spring Boot Actuator is present</li>
 * </ul>
 *
 * <p>
 * Compatible with Spring Boot 3.5.x and 4.0.x.
 *
 * <p>
 * Registers the following beans:
 * <ul>
 * <li>{@link EmbabelWorkflowCatalogService} — discovers Embabel agents via
 * reflection</li>
 * <li>{@link EmbabelWorkflowActuatorEndpoint} — exposes the catalog as an actuator
 * endpoint ({@code /actuator/embabel})</li>
 * <li>{@link EmbabelWorkflowApiController} — REST API at {@code /embabel-workflows/api};
 * enabled by setting {@code embabel.workflow.visualizer.enabled=true}</li>
 * <li>{@link WorkflowVisualizerPageController} — serves the visualizer HTML page; enabled
 * by setting {@code embabel.workflow.visualizer.enabled=true}</li>
 * </ul>
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = "org.springframework.boot.actuate.endpoint.annotation.Endpoint")
public class EmbabelWorkflowVisualizerAutoConfiguration {

	@Bean
	@ConditionalOnMissingBean
	public EmbabelWorkflowCatalogService embabelWorkflowCatalogService(ApplicationContext applicationContext) {
		return new EmbabelWorkflowCatalogService(applicationContext);
	}

	@Bean
	@ConditionalOnMissingBean
	@ConditionalOnAvailableEndpoint
	public EmbabelWorkflowActuatorEndpoint embabelWorkflowActuatorEndpoint(
			EmbabelWorkflowCatalogService catalogService) {
		return new EmbabelWorkflowActuatorEndpoint(catalogService);
	}

	@Bean
	@ConditionalOnMissingBean
	@ConditionalOnProperty(prefix = "embabel.workflow.visualizer", name = "enabled", havingValue = "true")
	public EmbabelWorkflowApiController embabelWorkflowApiController(EmbabelWorkflowCatalogService catalogService) {
		return new EmbabelWorkflowApiController(catalogService);
	}

	@Bean
	@ConditionalOnMissingBean
	@ConditionalOnProperty(prefix = "embabel.workflow.visualizer", name = "enabled", havingValue = "true")
	public WorkflowVisualizerPageController workflowVisualizerPageController() {
		return new WorkflowVisualizerPageController();
	}

}
