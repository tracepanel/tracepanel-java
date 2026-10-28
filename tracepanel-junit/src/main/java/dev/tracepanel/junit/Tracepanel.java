package dev.tracepanel.junit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Each test a trace, failed by what the agent finds in it: on a test class (its tests, its nested classes' too) or on
 * a test method, it turns TracepanelExtension on there, and says what fails the test.
 *
 *     @Tracepanel(maxQueries = 10, allow = "n+1")
 *     void feed() throws Exception { ... }
 *
 * The nearest says first: a method's, then its class's, then the classes around it. Each attribute it leaves out is
 * the next one's, and in the end the run's: `tracepanel.failOn`, n+1 and duplicates by default.
 */
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
@ExtendWith(TracepanelExtension.class)
public @interface Tracepanel {
    /** An attribute left out: the next annotation's, else the run's. */
    String INHERITED = "(inherited)";

    /** The kinds of issues that fail the test, instead of the run's `tracepanel.failOn` ({}: none). */
    String[] failOn() default INHERITED;

    /** Kinds of issues taken out of those that fail the test. */
    String[] allow() default INHERITED;

    /** Fail the test when it and its requests make more queries than this (-1: the next annotation's limit, if any). */
    int maxQueries() default -1;
}
