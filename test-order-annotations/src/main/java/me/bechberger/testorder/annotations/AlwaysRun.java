package me.bechberger.testorder.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a test class or method to <b>always run first</b> and to be <b>always
 * included</b> in select-mode subsets.
 *
 * <p>
 * Use this for smoke tests, critical fast tests, or any test that should never
 * be deferred to a later CI step.
 *
 * <h3>Class-level</h3>
 *
 * <pre>{@code
 * &#64;AlwaysRun
 * class CriticalSmokeTest { … }
 * }</pre>
 *
 * The class is pinned before all score-driven tests (same position as
 * {@link TestOrder.Priority#FIRST}) <b>and</b> is guaranteed to be included
 * when using {@code test-order:affected} or {@code test-order:auto}.
 *
 * <h3>Method-level</h3>
 *
 * <pre>{@code
 * class PaymentTest {
 *     &#64;AlwaysRun
 *     &#64;Test
 *     void criticalPaymentFlow() { … }
 * }
 * }</pre>
 *
 * The method is pinned before all score-driven methods within its class.
 *
 * <p>
 * {@code @AlwaysRun} can be combined with {@link TestOrder}: the pinning from
 * {@code @AlwaysRun} takes precedence, while {@link TestOrder#scoreBonus()} and
 * {@link TestOrder#changeBonus()} still apply for relative ordering within the
 * pinned group.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface AlwaysRun {
}
