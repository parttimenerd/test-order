package me.bechberger.testorder.maven;

import java.util.Map;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;

/**
 * Maven-specific wrapper around the shared
 * {@link me.bechberger.testorder.ops.ParameterValidator}. Translates
 * {@link IllegalArgumentException} into {@link MojoExecutionException}.
 */
public class ParameterValidator {

	private final me.bechberger.testorder.ops.ParameterValidator delegate;

	public ParameterValidator(Log log) {
		this.delegate = new me.bechberger.testorder.ops.ParameterValidator(MavenPluginLog.wrap(log));
	}

	public void validateChangeMode(String changeMode) throws MojoExecutionException {
		exec(() -> delegate.validateChangeMode(changeMode));
	}

	public void validateInstrumentationMode(String instrumentationMode) throws MojoExecutionException {
		exec(() -> delegate.validateInstrumentationMode(instrumentationMode));
	}

	public void validateFilePath(String filePath, String parameterName) throws MojoExecutionException {
		exec(() -> delegate.validateFilePath(filePath, parameterName));
	}

	public void validateOutputDirectory(String dirPath, String parameterName) throws MojoExecutionException {
		exec(() -> delegate.validateOutputDirectory(dirPath, parameterName));
	}

	public void validateIntRange(int value, int min, int max, String parameterName) throws MojoExecutionException {
		exec(() -> delegate.validateIntRange(value, min, max, parameterName));
	}

	public void validateNonNegative(int value, String parameterName) throws MojoExecutionException {
		exec(() -> delegate.validateNonNegative(value, parameterName));
	}

	public void validateMinValue(int value, int min, String parameterName) throws MojoExecutionException {
		exec(() -> delegate.validateMinValue(value, min, parameterName));
	}

	public void validateSelectParameters(int topN, int randomM) throws MojoExecutionException {
		exec(() -> delegate.validateSelectParameters(topN, randomM));
	}

	public void validateExplicitModeRequirements(String changeMode, String changedClasses)
			throws MojoExecutionException {
		validateExplicitModeRequirements(changeMode, changedClasses, null);
	}

	public void validateExplicitModeRequirements(String changeMode, String changedClasses, String changedTestClasses)
			throws MojoExecutionException {
		exec(() -> delegate.validateExplicitModeRequirements(changeMode, changedClasses, changedTestClasses));
	}

	public void warnChangedClassesFormat(String changedClasses) {
		delegate.warnChangedClassesFormat(changedClasses);
	}

	public void warnChangedClassesFormat(String changedClasses, String propertyName) {
		delegate.warnChangedClassesFormat(changedClasses, propertyName);
	}

	public void warnNegativeWeights(Map<String, Integer> weights) {
		delegate.warnNegativeWeights(weights);
	}

	private static void exec(Runnable fn) throws MojoExecutionException {
		try {
			fn.run();
		} catch (IllegalArgumentException e) {
			throw new MojoExecutionException(e.getMessage(), e);
		}
	}
}
