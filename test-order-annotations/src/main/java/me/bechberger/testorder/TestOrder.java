package me.bechberger.testorder;

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
@Target({ ElementType.TYPE, ElementType.METHOD })
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
	 * Additional score added only when test-order detects that this test class (or
	 * the class that contains the annotated method) is in the set of
	 * change-affected tests for the current run.
	 */
	int changeBonus() default 0;

	/** Priority levels for {@link TestOrder#priority()}. */
	enum Priority {
		/** Pinned before all score-driven tests. */
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
		 */
		public static final int BOOST = 1_000;
	}
}
