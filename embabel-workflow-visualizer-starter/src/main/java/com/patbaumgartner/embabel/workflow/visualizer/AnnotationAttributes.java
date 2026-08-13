package com.patbaumgartner.embabel.workflow.visualizer;

import org.springframework.util.StringUtils;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Reads attributes from annotations whose types are not on the compile classpath.
 *
 * <p>
 * The visualizer deliberately never imports Embabel types, so it cannot cast an
 * annotation to {@code @Action} and call {@code action.cost()}. Every attribute is
 * therefore looked up by name and matched by shape, and an attribute that an older or
 * newer Embabel release does not declare simply reads as "absent" instead of breaking the
 * scan.
 */
final class AnnotationAttributes {

	/**
	 * Values a {@code Class}-valued attribute uses to mean "nothing configured".
	 * {@code kotlin.Unit} is the default of {@code @Action(trigger)}.
	 */
	private static final Set<String> NO_CLASS_SENTINELS = Set.of("kotlin.Unit", "void", "java.lang.Void");

	private AnnotationAttributes() {
	}

	static Annotation find(AnnotatedElement element, String annotationTypeName) {
		for (Annotation annotation : element.getAnnotations()) {
			if (annotation.annotationType().getName().equals(annotationTypeName)) {
				return annotation;
			}
		}
		return null;
	}

	static boolean isPresent(AnnotatedElement element, String annotationTypeName) {
		return find(element, annotationTypeName) != null;
	}

	/** Empty string when the attribute is absent or not a string. */
	static String string(Annotation annotation, String attributeName) {
		return value(annotation, attributeName) instanceof String string ? string : "";
	}

	/** {@code null} when the attribute is absent, not a string, or blank. */
	static String stringOrNull(Annotation annotation, String attributeName) {
		String value = string(annotation, attributeName);
		return StringUtils.hasText(value) ? value : null;
	}

	/** Blank entries are dropped: Embabel treats them as unset. */
	static List<String> strings(Annotation annotation, String attributeName) {
		return value(annotation, attributeName) instanceof String[] values
				? Arrays.stream(values).filter(StringUtils::hasText).toList() : List.of();
	}

	static boolean flag(Annotation annotation, String attributeName) {
		return Boolean.TRUE.equals(value(annotation, attributeName));
	}

	/** Falls back to {@code fallback} for attributes whose Embabel default is true. */
	static boolean flag(Annotation annotation, String attributeName, boolean fallback) {
		return value(annotation, attributeName) instanceof Boolean flag ? flag : fallback;
	}

	/**
	 * {@code null} when the attribute is absent or left at {@code 0.0}. Embabel uses
	 * {@code 0.0} as the "not declared" default for every cost and value attribute.
	 */
	static Double nonZeroDouble(Annotation annotation, String attributeName) {
		return value(annotation, attributeName) instanceof Double number && number != 0.0 ? number : null;
	}

	/** Enum constant name, or empty string when the attribute is absent. */
	static String enumName(Annotation annotation, String attributeName) {
		Object value = value(annotation, attributeName);
		return value != null ? value.toString() : "";
	}

	/** Simple class name, or {@code null} for an absent or sentinel value. */
	static String classSimpleName(Annotation annotation, String attributeName) {
		if (!(value(annotation, attributeName) instanceof Class<?> type)) {
			return null;
		}
		return NO_CLASS_SENTINELS.contains(type.getName()) ? null : type.getSimpleName();
	}

	static List<String> classSimpleNames(Annotation annotation, String attributeName) {
		return value(annotation, attributeName) instanceof Class<?>[] types
				? Arrays.stream(types).map(Class::getSimpleName).toList() : List.of();
	}

	/** Nested annotation, e.g. {@code @AchievesGoal(export = @Export(...))}. */
	static Annotation nested(Annotation annotation, String attributeName) {
		return value(annotation, attributeName) instanceof Annotation nested ? nested : null;
	}

	/** Nested annotation array, e.g. {@code @LlmTool(metadata = {@Meta(...)})}. */
	static List<Annotation> nestedArray(Annotation annotation, String attributeName) {
		return value(annotation, attributeName) instanceof Annotation[] entries ? List.of(entries) : List.of();
	}

	private static Object value(Annotation annotation, String attributeName) {
		try {
			Method attribute = annotation.annotationType().getMethod(attributeName);
			return attribute.invoke(annotation);
		}
		catch (ReflectiveOperationException | RuntimeException ex) {
			return null;
		}
	}

}
