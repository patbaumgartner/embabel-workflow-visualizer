package com.patbaumgartner.embabel.workflow.visualizer;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.StaticWebApplicationContext;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WorkflowVisualizerPageControllerTests {

	private static final String CSP = "Content-Security-Policy";

	@Test
	void servesTheVisualizerPageAsHtml() throws Exception {
		mockMvcWithBasePath(null).perform(get("/embabel-workflows"))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith("text/html"))
			.andExpect(content().encoding("UTF-8"))
			.andExpect(content().string(containsString("Embabel Workflow Visualizer")))
			.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
	}

	@Test
	void servesTheVisualizerPageFromACustomBasePath() throws Exception {
		MockMvc mockMvc = mockMvcWithBasePath("/internal/agent-flows");

		mockMvc.perform(get("/internal/agent-flows")).andExpect(status().isOk());
		mockMvc.perform(get("/embabel-workflows")).andExpect(status().isNotFound());
	}

	@Test
	void sendsHardeningHeadersWithThePage() throws Exception {
		mockMvcWithBasePath(null).perform(get("/embabel-workflows"))
			.andExpect(header().string("X-Content-Type-Options", "nosniff"))
			.andExpect(header().string("Referrer-Policy", "no-referrer"))
			.andExpect(header().string(CSP, containsString("default-src 'none'")))
			.andExpect(header().string(CSP, containsString("connect-src 'self'")))
			.andExpect(header().string(CSP, containsString("frame-ancestors 'none'")))
			.andExpect(header().string(CSP, containsString("base-uri 'none'")));
	}

	/**
	 * The page escapes every value it draws from the application it describes. Naming a
	 * nonce in {@code script-src} is what makes a lapse in that escaping survivable:
	 * because a nonce is present the browser ignores {@code 'unsafe-inline'}, so injected
	 * markup cannot execute even though the page's own script blocks still can.
	 */
	@Test
	void authorisesOnlyTheScriptsCarryingThisResponseNonce() throws Exception {
		MvcResult result = mockMvcWithBasePath(null).perform(get("/embabel-workflows")).andReturn();

		String nonce = nonceFrom(result.getResponse().getHeader(CSP));
		String body = result.getResponse().getContentAsString();
		assertThat(body).doesNotContain(WorkflowVisualizerPageController.NONCE_PLACEHOLDER)
			.contains("<script nonce=\"" + nonce + "\">");
		assertThat(countOccurrences(body, "<script")).isEqualTo(countOccurrences(body, "<script nonce=\"" + nonce));
	}

	/** A nonce replayed across responses authorises nothing the previous one did not. */
	@Test
	void mintsAFreshNoncePerResponse() throws Exception {
		MockMvc mockMvc = mockMvcWithBasePath(null);

		String first = nonceFrom(mockMvc.perform(get("/embabel-workflows")).andReturn().getResponse().getHeader(CSP));
		String second = nonceFrom(mockMvc.perform(get("/embabel-workflows")).andReturn().getResponse().getHeader(CSP));

		assertThat(first).isNotEqualTo(second).isNotEmpty();
	}

	/**
	 * The page resolves its API URL from the browser's own location, so it keeps working
	 * behind a servlet context path or a reverse-proxy prefix. A hard-coded absolute path
	 * would silently 404 in those deployments.
	 */
	@Test
	void pageDoesNotHardCodeAnAbsoluteApiPath() {
		String page = new WorkflowVisualizerPageController().index().getBody();

		assertThat(page).doesNotContain("fetch('/embabel-workflows/api')").contains("window.location.pathname");
	}

	private String nonceFrom(String contentSecurityPolicy) {
		Matcher matcher = Pattern.compile("'nonce-([^']+)'").matcher(contentSecurityPolicy);
		assertThat(matcher.find()).describedAs("no nonce in %s", contentSecurityPolicy).isTrue();
		return matcher.group(1);
	}

	private int countOccurrences(String text, String needle) {
		return text.split(Pattern.quote(needle), -1).length - 1;
	}

	private MockMvc mockMvcWithBasePath(String basePath) {
		StaticWebApplicationContext context = new StaticWebApplicationContext();
		context.setServletContext(new MockServletContext());
		if (basePath != null) {
			MockEnvironment environment = new MockEnvironment();
			environment.setProperty("embabel.workflow.visualizer.base-path", basePath);
			context.setEnvironment(environment);
		}
		context.registerSingleton("pageController", WorkflowVisualizerPageController.class);
		context.refresh();
		return MockMvcBuilders.webAppContextSetup(context).build();
	}

}
