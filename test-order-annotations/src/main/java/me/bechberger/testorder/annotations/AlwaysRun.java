package me.bechberger.testorder.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Guarantees a test class or method is <b>always included</b> in affected-mode
 * subsets <em>and</em> <b>always runs first</b> within the ordered suite.
 *
 * <p>
 * Use this for smoke tests, critical fast tests, or any test that must never be
 * skipped when test-order is running in {@code affected} or {@code auto} mode.
 *
 * <p>
 * <b>How it differs from {@code @TestOrder(priority = Priority.FIRST)}:</b>
 * {@code Priority.FIRST} only affects <em>ordering</em> — the test still
 * participates in affected-mode filtering and can be omitted when running a
 * subset. {@code @AlwaysRun} adds a <em>selection guarantee</em>: the annotated
 * class is unconditionally included in the run set regardless of whether it is
 * affected by recent changes or scores highly enough to make the top-N cut.
 *
 * <h3>Class-level</h3>
 *
 * <pre>{@code
 * &#64;AlwaysRun
 * class CriticalSmokeTest { … }
 * }</pre>
 *
 * The class is pinned before all score-driven tests <b>and</b> is guaranteed to
 * be included when using {@code test-order:affected} or
 * {@code test-order:auto}.
 *
 * <h3>Method-level</h3>
 *
 * <pre>
 * {@code
 * class PaymentTest {
 *     &#64;AlwaysRun
 *     &#64;Test
 *     void criticalPaymentFlow() { … }
 * }
 * }
 * </pre>
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
