package com.patbaumgartner.embabel.kyc;

import com.patbaumgartner.embabel.kyc.KycModels.KycScreening;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link KycVerificationAgent}.
 *
 * <p>
 * Covers pure-Java @Condition methods that do not require LLM calls:
 * <ul>
 * <li>{@code requiresEnhancedDueDiligence()} — true when PEP or adverse media (but NOT
 * when sanctionsMatch is true — sanctioned customers go to direct assessment)</li>
 * <li>{@code canAssessDirectly()} — true when customer is clean OR when sanctioned
 * (sanctions trigger auto-reject via the direct path, bypassing EDD)</li>
 * </ul>
 */
@DisplayName("KycVerificationAgent — @Condition unit tests")
class KycVerificationAgentTest {

	private KycVerificationAgent agent;

	@BeforeEach
	void setUp() {
		agent = new KycVerificationAgent();
	}

	@Nested
	@DisplayName("requiresEnhancedDueDiligence()")
	class RequiresEnhancedDueDiligence {

		@Test
		@DisplayName("returns true when pepMatch is true")
		void trueForPepMatch() {
			var screening = new KycScreening("Jane Doe", "DE", true, false, false, "PEP match found");
			assertThat(agent.requiresEnhancedDueDiligence(screening)).isTrue();
		}

		@Test
		@DisplayName("returns false when sanctionsMatch is true alone (sanctions bypass EDD, go to direct path)")
		void falseForSanctionsMatchAlone() {
			var screening = new KycScreening("John Doe", "IR", false, true, false, "Sanctions hit");
			assertThat(agent.requiresEnhancedDueDiligence(screening)).isFalse();
		}

		@Test
		@DisplayName("returns true when adverseMediaMatch is true")
		void trueForAdverseMediaMatch() {
			var screening = new KycScreening("Bob Smith", "US", false, false, true, "Adverse media found");
			assertThat(agent.requiresEnhancedDueDiligence(screening)).isTrue();
		}

		@Test
		@DisplayName("returns false when all flags set (sanctionsMatch takes priority over EDD)")
		void falseWhenSanctionedEvenWithOtherFlags() {
			var screening = new KycScreening("Risk Person", "RU", true, true, true, "All flags");
			assertThat(agent.requiresEnhancedDueDiligence(screening)).isFalse();
		}

		@Test
		@DisplayName("returns false when no risk flags are set")
		void falseWhenNoFlags() {
			var screening = new KycScreening("Alice Clean", "CH", false, false, false, "Clear");
			assertThat(agent.requiresEnhancedDueDiligence(screening)).isFalse();
		}

	}

	@Nested
	@DisplayName("canAssessDirectly()")
	class CanAssessDirectly {

		@Test
		@DisplayName("returns true when no risk flags are set")
		void trueWhenClean() {
			var screening = new KycScreening("Alice Clean", "CH", false, false, false, "Clear");
			assertThat(agent.canAssessDirectly(screening)).isTrue();
		}

		@Test
		@DisplayName("returns false when pepMatch is true")
		void falseForPepMatch() {
			var screening = new KycScreening("PEP Person", "DE", true, false, false, "PEP");
			assertThat(agent.canAssessDirectly(screening)).isFalse();
		}

		@Test
		@DisplayName("returns true when sanctionsMatch is true (sanctions go to direct auto-reject path)")
		void trueForSanctionsMatch() {
			var screening = new KycScreening("Sanctioned", "IR", false, true, false, "Sanctions");
			assertThat(agent.canAssessDirectly(screening)).isTrue();
		}

		@Test
		@DisplayName("returns false when adverseMediaMatch is true")
		void falseForAdverseMediaMatch() {
			var screening = new KycScreening("Adverse", "US", false, false, true, "Media");
			assertThat(agent.canAssessDirectly(screening)).isFalse();
		}

		@Test
		@DisplayName("requiresEnhancedDueDiligence and canAssessDirectly are mutually exclusive")
		void mutuallyExclusive() {
			var cleanScreening = new KycScreening("Clean", "CH", false, false, false, "ok");
			var riskyScreening = new KycScreening("Risky", "IR", true, false, false, "pep");

			assertThat(agent.requiresEnhancedDueDiligence(cleanScreening)).as("Clean customer should not require EDD")
				.isFalse();
			assertThat(agent.canAssessDirectly(cleanScreening)).as("Clean customer can be assessed directly").isTrue();

			assertThat(agent.requiresEnhancedDueDiligence(riskyScreening)).as("Risky customer requires EDD").isTrue();
			assertThat(agent.canAssessDirectly(riskyScreening)).as("Risky customer cannot be assessed directly")
				.isFalse();
		}

	}

}
