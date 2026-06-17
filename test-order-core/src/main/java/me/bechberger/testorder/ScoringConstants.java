package me.bechberger.testorder;

/**
 * Shared scoring constants used by {@link TestScorer} and {@link MethodScorer}.
 */
public final class ScoringConstants {

	/**
	 * Geometric decline factor applied to set-cover bonuses for each successive
	 * pick.
	 */
	public static final double SET_COVER_DECLINE = 0.8;

	/**
	 * Minimum set-cover bonus after decline, preventing bonuses from reaching zero.
	 * Class-level scoring ({@link TestScorer}) rounds this to {@code (int) 1};
	 * method-level scoring ({@link MethodScorer}) uses it as a {@code double}.
	 */
	public static final double SET_COVER_FLOOR = 0.1;

	private ScoringConstants() {
	}
}
