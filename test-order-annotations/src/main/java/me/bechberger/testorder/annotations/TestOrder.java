package me.bechberger.testorder.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Overrides or adjusts the score-based ordering produced by
 * {@code PriorityClassOrderer} / {@code PriorityMethodOrderer}.
 *
 * <p>
 * Can be placed on a test class or an individual test method. All three
 * attributes are independent and may be combined.
 *
 * <h3>Priority pinning</h3>
 *
 * <pre>
 * {@code
 * &#64;TestOrder(priority = Priority.FIRST)
 * class CriticalSmokeTest { … }
 *
 * &#64;TestOrder(priority = Priority.LAST)
 * class SlowIntegrationTest { … }
 * }
 * </pre>
 *
 * <h3>Score adjustment</h3>
 *
 * <pre>{@code
 * // Always add 200 to the computed score
 * &#64;TestOrder(scoreBonus = 200)
 * class ImportantTest { … }
 *
 * // Extra 100 points only when changes are detected for this class
 * &#64;TestOrder(changeBonus = 100)
 * class PaymentServiceTest { … }
 *
 * // Combined: flat boost + change-triggered boost
 * &#64;TestOrder(scoreBonus = 50, changeBonus = 150)
 * class AuthTest { … }
 * }
 * </pre>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface TestOrder {

	/**
	 * Absolute position override.
	 *
	 * <ul>
	 * <li>{@link Priority#FIRST} — runs before all score-driven tests
	 * <li>{@link Priority#HIGH} — adds {@value Priority#BOOST} to the computed
	 * score
	 * <li>{@link Priority#NORMAL} — no override (default)
	 * <li>{@link Priority#LOW} — subtracts {@value Priority#BOOST} from the
	 * computed score
	 * <li>{@link Priority#LAST} — runs after all score-driven tests
	 * </ul>
	 */
	Priority priority() default Priority.NORMAL;

	/**
	 * Flat value added to the computed score unconditionally. May be negative to
	 * demote a test without pinning it to the very end.
	 */
	int scoreBonus() default 0;

	/**
	 * Additional score added only when test-order detects that the application code
	 * touched by this test (or the test class itself) has changed in the current
	 * run. Requires change-detection to be active.
	 */
	int changeBonus() default 0;

	/**
	 * <b>Note:</b> {@code @TestOrder} is not {@code @Inherited}; subclasses of an
	 * annotated test class must be annotated independently.
	 */

	/** Priority levels for {@link TestOrder#priority()}. */
	enum Priority {
		/**
		 * Pinned before all score-driven tests. Does not affect affected-mode inclusion
		 * — use {@link AlwaysRun} if the test must also be guaranteed to run in
		 * {@code affected} or {@code auto} mode.
		 */
		FIRST,

		/**
		 * Large positive score boost ({@value #BOOST} points). Stays in normal
		 * score-based sort but almost always ends up near the front.
		 */
		HIGH,

		/** Default: no position override. */
		NORMAL,

		/**
		 * Large negative score penalty ({@value #BOOST} points subtracted). Stays in
		 * normal score-based sort but almost always ends up near the back.
		 */
		LOW,

		/** Pinned after all score-driven tests. */
		LAST;

		/**
		 * Score delta applied by {@link #HIGH} (positive) and {@link #LOW} (negative).
		 * Chosen to dominate typical computed scores (which rarely exceed ~50) while
		 * still leaving room for {@link TestOrder#scoreBonus()} stacking. This value is
		 * part of the public API; do not change it without a major version bump.
		 */
		public static final int BOOST = 1_000;
	}
}
