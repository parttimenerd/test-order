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
		try {
			delegate.validateChangeMode(changeMode);
		} catch (IllegalArgumentException e) {
			throw new MojoExecutionException(e.getMessage(), e);
		}
	}

	public void validateInstrumentationMode(String instrumentationMode) throws MojoExecutionException {
		try {
			delegate.validateInstrumentationMode(instrumentationMode);
		} catch (IllegalArgumentException e) {
			throw new MojoExecutionException(e.getMessage(), e);
		}
	}

	public void validateFilePath(String filePath, String parameterName) throws MojoExecutionException {
		try {
			delegate.validateFilePath(filePath, parameterName);
		} catch (IllegalArgumentException e) {
			throw new MojoExecutionException(e.getMessage(), e);
		}
	}

	public void validateOutputDirectory(String dirPath, String parameterName) throws MojoExecutionException {
		try {
			delegate.validateOutputDirectory(dirPath, parameterName);
		} catch (IllegalArgumentException e) {
			throw new MojoExecutionException(e.getMessage(), e);
		}
	}

	public void validateIntRange(int value, int min, int max, String parameterName) throws MojoExecutionException {
		try {
			delegate.validateIntRange(value, min, max, parameterName);
		} catch (IllegalArgumentException e) {
			throw new MojoExecutionException(e.getMessage(), e);
		}
	}

	public void validateNonNegative(int value, String parameterName) throws MojoExecutionException {
		try {
			delegate.validateNonNegative(value, parameterName);
		} catch (IllegalArgumentException e) {
			throw new MojoExecutionException(e.getMessage(), e);
		}
	}

	public void validateMinValue(int value, int min, String parameterName) throws MojoExecutionException {
		try {
			delegate.validateMinValue(value, min, parameterName);
		} catch (IllegalArgumentException e) {
			throw new MojoExecutionException(e.getMessage(), e);
		}
	}

	public void validateSelectParameters(int topN, int randomM) throws MojoExecutionException {
		try {
			delegate.validateSelectParameters(topN, randomM);
		} catch (IllegalArgumentException e) {
			throw new MojoExecutionException(e.getMessage(), e);
		}
	}

	public void validateExplicitModeRequirements(String changeMode, String changedClasses)
			throws MojoExecutionException {
		validateExplicitModeRequirements(changeMode, changedClasses, null);
	}

	public void validateExplicitModeRequirements(String changeMode, String changedClasses,
			String changedTestClasses) throws MojoExecutionException {
		try {
			delegate.validateExplicitModeRequirements(changeMode, changedClasses, changedTestClasses);
		} catch (IllegalArgumentException e) {
			throw new MojoExecutionException(e.getMessage(), e);
		}
	}

	public void warnNegativeWeights(Map<String, Integer> weights) {
		delegate.warnNegativeWeights(weights);
	}
}
