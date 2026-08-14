package com.patbaumgartner.embabel.workflow.visualizer;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import static com.patbaumgartner.embabel.workflow.visualizer.EmbabelWorkflowVisualizerProperties.BASE_PATH_PLACEHOLDER;

/**
 * Serves the Embabel Workflow Visualizer page from
 * {@code embabel.workflow.visualizer.base-path} (default {@code /embabel-workflows}).
 *
 * <p>
 * The page is a private classpath resource rather than a {@code static/} one on purpose.
 * Spring Boot serves {@code classpath:/static/**} from every jar on the classpath, so a
 * page published there would stay reachable even with the visualizer disabled, and would
 * compete for file names with the consuming application's own assets.
 *
 * <p>
 * For the same reason the condition is repeated on the class. {@code @RestController} is
 * a {@code @Component} stereotype, so a consuming application whose base package encloses
 * this one component-scans this class and would otherwise serve the page whatever
 * {@code embabel.workflow.visualizer.enabled} says. Component scanning evaluates
 * {@code @Conditional} as well, so the property governs both routes to this bean.
 */
@RestController
@ConditionalOnProperty(prefix = "embabel.workflow.visualizer", name = "enabled", havingValue = "true")
@SuppressWarnings("unused") // instantiated by EmbabelWorkflowVisualizerAutoConfiguration
public class WorkflowVisualizerPageController {

	static final String PAGE_RESOURCE = "com/patbaumgartner/embabel/workflow/visualizer/workflow-visualizer.html";

	/** Token the page's script tags carry; each response substitutes its own nonce. */
	static final String NONCE_PLACEHOLDER = "__CSP_NONCE__";

	/**
	 * The page draws names, descriptions and class names supplied by the application it
	 * describes. It escapes all of them, and this policy is what makes a failure of that
	 * escaping survivable: naming a nonce in {@code script-src} makes the browser ignore
	 * {@code 'unsafe-inline'}, so injected markup cannot execute — while the page's own
	 * two script blocks, which carry the nonce, still can.
	 *
	 * <p>
	 * {@code style-src} has to keep {@code 'unsafe-inline'}: the legend colours its
	 * swatches with {@code style} attributes, which no nonce can cover. Everything else
	 * is denied outright, which is what turns the page's "no third-party scripts, fonts
	 * or styles, and exactly one request — to its own API" promise into something the
	 * browser enforces rather than something a reader has to verify by inspection.
	 */
	private static final String CONTENT_SECURITY_POLICY = "default-src 'none'; script-src 'nonce-%s'; "
			+ "style-src 'unsafe-inline'; img-src data:; connect-src 'self'; "
			+ "base-uri 'none'; form-action 'none'; frame-ancestors 'none'";

	private static final SecureRandom NONCE_SOURCE = new SecureRandom();

	private static final Base64.Encoder NONCE_ENCODER = Base64.getUrlEncoder().withoutPadding();

	private final String page;

	public WorkflowVisualizerPageController() {
		this.page = readPage();
	}

	@GetMapping(path = BASE_PATH_PLACEHOLDER, produces = MediaType.TEXT_HTML_VALUE)
	public ResponseEntity<String> index() {
		String nonce = newNonce();
		return ResponseEntity.ok()
			.contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
			// The body carries a single-use nonce, so it must not be stored and then
			// replayed against a later response's policy.
			.cacheControl(CacheControl.noStore())
			.header("Content-Security-Policy", CONTENT_SECURITY_POLICY.formatted(nonce))
			.header("X-Content-Type-Options", "nosniff")
			.header("Referrer-Policy", "no-referrer")
			.body(this.page.replace(NONCE_PLACEHOLDER, nonce));
	}

	/** 128 bits of URL-safe randomness, which is what the CSP nonce grammar accepts. */
	private static String newNonce() {
		byte[] bytes = new byte[16];
		NONCE_SOURCE.nextBytes(bytes);
		return NONCE_ENCODER.encodeToString(bytes);
	}

	private static String readPage() {
		ClassPathResource resource = new ClassPathResource(PAGE_RESOURCE,
				WorkflowVisualizerPageController.class.getClassLoader());
		try {
			return resource.getContentAsString(StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Embabel Workflow Visualizer page is missing from " + PAGE_RESOURCE, ex);
		}
	}

}
