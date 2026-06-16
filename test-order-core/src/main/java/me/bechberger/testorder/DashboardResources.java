package me.bechberger.testorder.dashboard;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Loads and assembles the dashboard template from classpath resources.
 *
 * <p>
 * The dashboard is composed from three resource files under
 * {@code /dashboard/}:
 * <ul>
 * <li>{@code template.html} — outer HTML skeleton with placeholders</li>
 * <li>{@code dist/dashboard.css} — Vite-built CSS bundle</li>
 * <li>{@code dist/dashboard.js} — Vite-built IIFE JS bundle (Vue + Chart.js +
 * D3 + app)</li>
 * </ul>
 *
 * <p>
 * Call {@link #assembleTemplate()} to get the combined template with only the
 * {@code /*DASHBOARD_DATA_PLACEHOLDER*}{@code /} token remaining for
 * {@code DashboardGenerator} to fill in.
 */
public final class DashboardResources {

	/** Placeholder replaced with the contents of {@code dist/dashboard.css}. */
	static final String CSS_PLACEHOLDER = "/*CSS_PLACEHOLDER*/";
	/** Placeholder replaced with the contents of {@code dist/dashboard.js}. */
	static final String APP_JS_PLACEHOLDER = "/*APP_JS_PLACEHOLDER*/";

	private static final String BASE = "/dashboard/";

	/**
	 * Names of the bundled JS libraries.
	 *
	 * @deprecated Web assets are now bundled by Vite into
	 *             {@code dist/dashboard.js}. This constant is kept only for
	 *             backward compatibility.
	 */
	@Deprecated
	public static final List<String> WEB_ASSETS = List.of("vue.global.prod.js", "chart.umd.min.js", "d3.min.js");

	private DashboardResources() {
	}

	/**
	 * Assembles the complete dashboard HTML template.
	 *
	 * <p>
	 * The returned string still contains the data placeholder
	 * ({@code /*DASHBOARD_DATA_PLACEHOLDER*}{@code /}) — that is filled in later by
	 * {@code DashboardGenerator} in the {@code test-order-core} module.
	 *
	 * @return assembled HTML template string
	 * @throws IOException
	 *             if any resource file is missing or unreadable
	 */
	public static String assembleTemplate() throws IOException {
		String skeleton = loadResource("template.html");
		String css = loadResource("dist/dashboard.css");
		String js = loadResource("dist/dashboard.js");

		return skeleton.replace(CSS_PLACEHOLDER, css).replace(APP_JS_PLACEHOLDER, js);
	}

	/**
	 * Loads a bundled JS library by name.
	 *
	 * @deprecated Web assets are now bundled by Vite. This method is kept for
	 *             backward compatibility but should no longer be called.
	 * @param name
	 *            one of {@link #WEB_ASSETS}
	 * @return the full JS source text
	 * @throws IOException
	 *             if the resource is missing or unreadable
	 */
	@Deprecated
	public static String loadWebAsset(String name) throws IOException {
		return loadResource("web-assets/" + name);
	}

	private static String loadResource(String relativePath) throws IOException {
		String path = BASE + relativePath;
		try (InputStream is = DashboardResources.class.getResourceAsStream(path)) {
			if (is == null) {
				throw new IOException("Dashboard resource not found on classpath: " + path
						+ " — rebuild the test-order-dashboard module.");
			}
			return new String(is.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
