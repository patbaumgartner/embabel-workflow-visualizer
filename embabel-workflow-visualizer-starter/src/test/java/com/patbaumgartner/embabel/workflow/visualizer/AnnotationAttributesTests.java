package com.patbaumgartner.embabel.workflow.visualizer;

import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import static org.assertj.core.api.Assertions.assertThat;

class AnnotationAttributesTests {

	private final Annotation sample = Sample.class.getAnnotation(Everything.class);

	@Test
	void readsEachAttributeShape() {
		assertThat(AnnotationAttributes.string(this.sample, "text")).isEqualTo("hello");
		assertThat(AnnotationAttributes.strings(this.sample, "texts")).containsExactly("a", "b");
		assertThat(AnnotationAttributes.flag(this.sample, "on")).isTrue();
		assertThat(AnnotationAttributes.nonZeroDouble(this.sample, "amount")).isEqualTo(0.75);
		assertThat(AnnotationAttributes.enumName(this.sample, "mode")).isEqualTo("SECOND");
		assertThat(AnnotationAttributes.classSimpleName(this.sample, "type")).isEqualTo("String");
		assertThat(AnnotationAttributes.classSimpleNames(this.sample, "types")).containsExactly("Integer", "Long");
		assertThat(AnnotationAttributes.nested(this.sample, "one")).isNotNull();
		assertThat(AnnotationAttributes.nestedArray(this.sample, "many")).hasSize(2);
	}

	/**
	 * The visualizer reads Embabel annotations without compiling against them, so an
	 * attribute an older or newer release does not declare must read as "absent" rather
	 * than abort the whole scan.
	 */
	@Test
	void anUnknownAttributeReadsAsAbsentForEveryShape() {
		assertThat(AnnotationAttributes.string(this.sample, "noSuchAttribute")).isEmpty();
		assertThat(AnnotationAttributes.stringOrNull(this.sample, "noSuchAttribute")).isNull();
		assertThat(AnnotationAttributes.strings(this.sample, "noSuchAttribute")).isEmpty();
		assertThat(AnnotationAttributes.flag(this.sample, "noSuchAttribute")).isFalse();
		assertThat(AnnotationAttributes.nonZeroDouble(this.sample, "noSuchAttribute")).isNull();
		assertThat(AnnotationAttributes.enumName(this.sample, "noSuchAttribute")).isEmpty();
		assertThat(AnnotationAttributes.classSimpleName(this.sample, "noSuchAttribute")).isNull();
		assertThat(AnnotationAttributes.classSimpleNames(this.sample, "noSuchAttribute")).isEmpty();
		assertThat(AnnotationAttributes.nested(this.sample, "noSuchAttribute")).isNull();
		assertThat(AnnotationAttributes.nestedArray(this.sample, "noSuchAttribute")).isEmpty();
	}

	/** An attribute read with the wrong shape must not be coerced into a wrong value. */
	@Test
	void anAttributeOfAnotherShapeReadsAsAbsent() {
		assertThat(AnnotationAttributes.string(this.sample, "on")).isEmpty();
		assertThat(AnnotationAttributes.nonZeroDouble(this.sample, "text")).isNull();
		assertThat(AnnotationAttributes.classSimpleName(this.sample, "texts")).isNull();
	}

	@Test
	void aFlagFallsBackForAttributesWhoseFrameworkDefaultIsTrue() {
		assertThat(AnnotationAttributes.flag(this.sample, "off", true)).isFalse();
		assertThat(AnnotationAttributes.flag(this.sample, "noSuchAttribute", true)).isTrue();
	}

	@Test
	void doubleAttributesLeftAtZeroCountAsNotDeclared() {
		assertThat(AnnotationAttributes.nonZeroDouble(this.sample, "zero")).isNull();
	}

	@Test
	void blankArrayEntriesAreDropped() {
		assertThat(AnnotationAttributes.strings(this.sample, "textsWithBlanks")).containsExactly("kept");
	}

	/** {@code kotlin.Unit} is the "no trigger" default of {@code @Action(trigger)}. */
	@Test
	void classSentinelsMeanNotDeclared() {
		assertThat(AnnotationAttributes.classSimpleName(this.sample, "voidType")).isNull();
	}

	@Test
	void findsAndDetectsAnnotationsByName() {
		assertThat(AnnotationAttributes.find(Sample.class, Everything.class.getName())).isNotNull();
		assertThat(AnnotationAttributes.find(Sample.class, "com.example.Absent")).isNull();
		assertThat(AnnotationAttributes.isPresent(Sample.class, Everything.class.getName())).isTrue();
		assertThat(AnnotationAttributes.isPresent(Sample.class, "com.example.Absent")).isFalse();
	}

	enum Mode {

		FIRST, SECOND

	}

	@Retention(RetentionPolicy.RUNTIME)
	@interface Nested {

		String key() default "";

	}

	@Retention(RetentionPolicy.RUNTIME)
	@interface Everything {

		String text() default "";

		String[] texts() default {};

		String[] textsWithBlanks() default {};

		boolean on() default false;

		boolean off() default true;

		double amount() default 0.0;

		double zero() default 0.0;

		Mode mode() default Mode.FIRST;

		Class<?> type() default Object.class;

		Class<?> voidType() default Void.class;

		Class<?>[] types() default {};

		Nested one() default @Nested;

		Nested[] many() default {};

	}

	@Everything(text = "hello", texts = { "a", "b" }, textsWithBlanks = { "kept", "", "  " }, on = true, off = false,
			amount = 0.75, mode = Mode.SECOND, type = String.class, types = { Integer.class, Long.class },
			one = @Nested(key = "k"), many = { @Nested(key = "one"), @Nested(key = "two") })
	static class Sample {

	}

}
