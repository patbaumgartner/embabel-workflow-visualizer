package com.patbaumgartner.embabel.workflow.visualizer;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.StaticWebApplicationContext;

import java.util.LinkedHashMap;
import java.util.Map;
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

	/**
	 * The page draws every step name, type and badge in a brand colour, and it ships two
	 * palettes because one cannot serve both surfaces: the hues tuned for the dark card
	 * drop to as little as 1.6:1 on the white one. Each theme therefore has to be checked
	 * against the card <em>it</em> paints, which is what stops a future colour tweak from
	 * quietly reintroducing unreadable text for half the users.
	 */
	@Test
	void everyBrandColourIsLegibleOnTheCardItsOwnThemePaints() {
		String page = new WorkflowVisualizerPageController().index().getBody();

		Map<String, Map<String, String>> themes = themedColourBlocks(page);
		assertThat(themes).describedAs("no theme block declares the brand palette").isNotEmpty();
		themes.forEach((selector, tokens) -> {
			String card = tokens.get("card");
			assertThat(card).describedAs("%s declares brand colours but no --card to read them against", selector)
				.isNotNull();
			tokens.forEach((token, colour) -> {
				if (token.startsWith("brand-")) {
					assertThat(contrastRatio(colour, card))
						.describedAs("--%s (%s) on --card (%s) in %s", token, colour, card, selector)
						.isGreaterThanOrEqualTo(4.5);
				}
			});
		});
	}

	/**
	 * The filter reads this notice on every keystroke, including before the catalog has
	 * loaded and after a load that produced no agents or failed outright. Declaring it in
	 * the markup — outside the container each render clears — is what keeps it present on
	 * all of those paths rather than only the one that renders agents.
	 */
	@Test
	void theNoMatchNoticeIsDeclaredInMarkupRatherThanBuiltByARenderPath() {
		String page = new WorkflowVisualizerPageController().index().getBody();

		assertThat(page).contains("id=\"no-match\"").doesNotContain("noMatch.id = 'no-match'");
		assertThat(page.indexOf("id=\"no-match\"")).isLessThan(page.indexOf("function applyFilter"));
	}

	/**
	 * Every declaration block that sets the brand palette, keyed by its selector, with
	 * the {@code --card} it is meant to be read against.
	 */
	private Map<String, Map<String, String>> themedColourBlocks(String page) {
		Map<String, Map<String, String>> blocks = new LinkedHashMap<>();
		Matcher block = Pattern.compile("([^{}/*]+?)\\{([^{}]*?)}", Pattern.DOTALL).matcher(page);
		while (block.find()) {
			Map<String, String> tokens = new LinkedHashMap<>();
			Matcher token = Pattern.compile("--(brand-[a-z]+|card)\\s*:\\s*(#[0-9a-fA-F]{6})\\s*;")
				.matcher(block.group(2));
			while (token.find()) {
				tokens.put(token.group(1), token.group(2));
			}
			if (tokens.keySet().stream().anyMatch(name -> name.startsWith("brand-"))) {
				blocks.put(block.group(1).trim(), tokens);
			}
		}
		return blocks;
	}

	/** WCAG 2.1 contrast ratio: (L1 + 0.05) / (L2 + 0.05) over relative luminance. */
	private double contrastRatio(String foreground, String background) {
		double lighter = Math.max(relativeLuminance(foreground), relativeLuminance(background));
		double darker = Math.min(relativeLuminance(foreground), relativeLuminance(background));
		return (lighter + 0.05) / (darker + 0.05);
	}

	/** WCAG 2.1 relative luminance of an {@code #rrggbb} colour. */
	private double relativeLuminance(String hex) {
		double[] weights = { 0.2126, 0.7152, 0.0722 };
		double luminance = 0;
		for (int channel = 0; channel < 3; channel++) {
			double value = Integer.parseInt(hex.substring(1 + channel * 2, 3 + channel * 2), 16) / 255.0;
			luminance += weights[channel] * (value <= 0.04045 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4));
		}
		return luminance;
	}

	/**
	 * The actuator endpoint's URL depends on the servlet context path, the management
	 * base path, the management port and whether {@code embabel} is exposed — none of
	 * which this page can see. It used to print a fixed {@code /actuator/embabel}, which
	 * 404s behind a context path, so it now names only the API it derives itself.
	 */
	@Test
	void pageDoesNotAdvertiseAnActuatorPathItCannotResolve() {
		String page = new WorkflowVisualizerPageController().index().getBody();

		assertThat(page).doesNotContain("/actuator/embabel");
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
