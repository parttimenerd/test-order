package me.bechberger.testorder.ci;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Interface for downloading test-order dependency files from CI systems.
 * Implementations exist for GitHub Actions, HTTP endpoints, and local files.
 */
public interface DepDownloader {
	/**
	 * Download the dependency file from the CI system.
	 *
	 * @param outputPath
	 *            where to save the downloaded file
	 * @return true if download was successful
	 * @throws IOException
	 *             if download fails
	 * @throws DepDownloadException
	 *             if there's a specific download issue
	 */
	boolean download(Path outputPath) throws IOException, DepDownloadException;

	/**
	 * Get a human-readable name for this downloader
	 */
	String getName();

	/**
	 * Validate that the configuration is correct before attempting download
	 *
	 * @throws DepDownloadException
	 *             if configuration is invalid
	 */
	void validate() throws DepDownloadException;

	/**
	 * Exception for download-specific errors
	 */
	class DepDownloadException extends Exception {
		public DepDownloadException(String message) {
			super(message);
		}

		public DepDownloadException(String message, Throwable cause) {
			super(message, cause);
		}
	}
}
