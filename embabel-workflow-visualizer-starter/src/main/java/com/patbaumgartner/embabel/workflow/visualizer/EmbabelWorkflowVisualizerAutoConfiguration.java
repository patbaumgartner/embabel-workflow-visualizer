package com.patbaumgartner.embabel.workflow.visualizer;

import org.springframework.boot.actuate.autoconfigure.endpoint.condition.ConditionalOnAvailableEndpoint;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
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
 * Registers the following beans:
 * <ul>
 * <li>{@link EmbabelWorkflowCatalogService} — discovers Embabel agents via
 * reflection</li>
 * <li>{@link EmbabelWorkflowActuatorEndpoint} — exposes the catalog as an actuator
 * endpoint ({@code /actuator/embabel})</li>
 * <li>{@link EmbabelWorkflowApiController} — REST API at {@code <base-path>/api}; enabled
 * by setting {@code embabel.workflow.visualizer.enabled=true}</li>
 * <li>{@link WorkflowVisualizerPageController} — serves the visualizer HTML page at
 * {@code <base-path>}; enabled by setting
 * {@code embabel.workflow.visualizer.enabled=true}</li>
 * </ul>
 *
 * <p>
 * See {@link EmbabelWorkflowVisualizerProperties} for the supported configuration.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = "org.springframework.boot.actuate.endpoint.annotation.Endpoint")
@EnableConfigurationProperties(EmbabelWorkflowVisualizerProperties.class)
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

	/**
	 * Both controllers map through the {@code base-path} placeholder, and take the
	 * properties bean purely so that its binding — and therefore its validation of that
	 * same property — is guaranteed to have run before the mapping is resolved. An
	 * invalid base path then fails at startup, naming the property, instead of producing
	 * a puzzling mapping.
	 */
	@Bean
	@ConditionalOnMissingBean
	@ConditionalOnProperty(prefix = "embabel.workflow.visualizer", name = "enabled", havingValue = "true")
	public EmbabelWorkflowApiController embabelWorkflowApiController(EmbabelWorkflowCatalogService catalogService,
			EmbabelWorkflowVisualizerProperties properties) {
		return new EmbabelWorkflowApiController(catalogService);
	}

	@Bean
	@ConditionalOnMissingBean
	@ConditionalOnProperty(prefix = "embabel.workflow.visualizer", name = "enabled", havingValue = "true")
	public WorkflowVisualizerPageController workflowVisualizerPageController(
			EmbabelWorkflowVisualizerProperties properties) {
		return new WorkflowVisualizerPageController();
	}

}
