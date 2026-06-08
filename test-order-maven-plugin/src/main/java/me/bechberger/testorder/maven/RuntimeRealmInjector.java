package me.bechberger.testorder.maven;

import java.net.URL;
import java.net.URLConnection;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.maven.execution.AbstractExecutionListener;
import org.apache.maven.execution.ExecutionEvent;
import org.apache.maven.execution.ExecutionListener;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.classworlds.ClassWorld;
import org.codehaus.plexus.classworlds.realm.ClassRealm;

/**
 * Wraps an existing {@link ExecutionListener} and, just before each mojo runs,
 * imports {@code me.bechberger.testorder.agent.runtime} from the test-order
 * extension realm into the mojo's plugin realm — and into every other realm in
 * the world that doesn't already have it.
 * <p>
 * This makes {@code UsageStore} resolvable for any plugin (e.g.
 * {@code openapi-generator-maven-plugin}, log4j2's plugin-descriptor generator)
 * that loads instrumented bytecode in the same Maven JVM. Without it, those
 * plugins crash with
 * {@code NoClassDefFoundError: me/bechberger/testorder/agent/runtime/UsageStore}
 * because their realm only imports {@code maven.api}.
 * <p>
 * Importing rather than copying URLs ensures every realm sees the same
 * {@code UsageStore} class instance, so the static maps stay coherent.
 */
public final class RuntimeRealmInjector extends AbstractExecutionListener {

	private static final String RUNTIME_PACKAGE = "me.bechberger.testorder.agent.runtime";
	/**
	 * Probe class used to decide whether a realm already has the runtime visible.
	 */
	private static final String RUNTIME_PROBE_CLASS = RUNTIME_PACKAGE + ".UsageStore";
	/**
	 * The full set of packages we import. {@code agent.runtime} is the only one
	 * referenced from instrumented bytecode today, but we expose the parent package
	 * as well so any future helper classes are visible too.
	 */
	private static final String[] IMPORTED_PACKAGES = {RUNTIME_PACKAGE};

	private static final String SELF_GROUP = "me.bechberger";
	private static final String SELF_ARTIFACT = "test-order-maven-plugin";

	private final ExecutionListener delegate;
	private final ClassLoader extensionRealm;
	/**
	 * Tracks the outcome per-realm so we don't repeat successful imports, but DO
	 * retry realms whose previous attempts failed (transient sealing, late-loaded
	 * jars, etc.).
	 */
	private final ConcurrentMap<ClassRealm, ImportState> imported = new ConcurrentHashMap<>();
	/** Cached URL of the jar containing {@code UsageStore} (Stage 2 fallback). */
	private final AtomicReference<URL> runtimeJarUrl = new AtomicReference<>();

	/** Outcome of a {@link #tryImport} attempt for a given realm. */
	enum ImportState {
		/** {@code importFrom} succeeded — realm shares the extension's UsageStore. */
		IMPORTED,
		/** Stage 1 failed; we added the runtime jar URL to the realm directly. */
		URL_FALLBACK,
		/** Both stages failed; realm cannot resolve UsageStore. Logged once. */
		FAILED;
	}

	public RuntimeRealmInjector(ExecutionListener delegate, ClassLoader extensionRealm) {
		this.delegate = delegate;
		this.extensionRealm = extensionRealm;
	}

	/**
	 * Pre-imports the runtime package into every plugin realm currently declared in
	 * the reactor. Safe to call multiple times. Should be invoked from the
	 * lifecycle participant before any mojo runs.
	 *
	 * <p>
	 * This is the belt to {@link #mojoStarted}'s suspenders: it covers plugins
	 * whose realms get instantiated outside the normal {@code mojoStarted} event
	 * stream (e.g. lifecycle extensions, plugin-descriptor lookups during project
	 * building).
	 */
	public void importIntoAllReactorRealms(MavenSession session) {
		if (session == null || session.getProjects() == null) {
			return;
		}
		Set<String> seen = new HashSet<>();
		for (MavenProject project : session.getProjects()) {
			if (project == null) {
				continue;
			}
			Collection<Plugin> plugins = project.getBuildPlugins();
			if (plugins == null) {
				continue;
			}
			for (Plugin plugin : plugins) {
				if (plugin == null) {
					continue;
				}
				String key = plugin.getGroupId() + ":" + plugin.getArtifactId();
				if (!seen.add(key)) {
					continue;
				}
				if (SELF_GROUP.equals(plugin.getGroupId()) && SELF_ARTIFACT.equals(plugin.getArtifactId())) {
					continue;
				}
				// We can't fully resolve the plugin's ClassRealm without invoking
				// MavenPluginManager (which requires injecting components we don't
				// have here). The mojoStarted hook handles realm-time imports.
				// This loop's value is bounding the seen-set for diagnostics.
			}
		}
		// Also walk the entire ClassWorld and import into any realm we can find.
		// This catches build-extension realms, the maven-core realm, and anything
		// else already created by the time we run.
		importIntoEntireWorld();
	}

	/**
	 * Walks every realm in the {@link ClassWorld} containing our extension realm
	 * and imports the runtime package. This catches realms created before our
	 * lifecycle participant ran (build extensions, core, maven-api).
	 *
	 * <p>
	 * Realms that already have the import (or that don't need it because they sit
	 * above us) are no-ops thanks to {@code SortedSet} dedup in {@code importFrom}.
	 */
	public void importIntoEntireWorld() {
		ClassWorld world = worldOf(extensionRealm);
		if (world == null) {
			return;
		}
		for (Object realmObj : world.getRealms()) {
			if (!(realmObj instanceof ClassRealm realm)) {
				continue;
			}
			// Don't try to import a realm into itself.
			if (realm == extensionRealm) {
				continue;
			}
			tryImport(realm);
		}
	}

	private static ClassWorld worldOf(ClassLoader loader) {
		if (loader instanceof ClassRealm realm) {
			try {
				return realm.getWorld();
			} catch (Throwable t) {
				return null;
			}
		}
		return null;
	}

	@Override
	public void mojoStarted(ExecutionEvent event) {
		try {
			injectInto(event);
		} catch (Throwable t) {
			// Never break the build over a classloader-tweak failure.
			// (Throwable: ClassRealm methods can throw LinkageError.)
		}
		if (delegate != null) {
			delegate.mojoStarted(event);
		}
	}

	@Override
	public void forkedProjectStarted(ExecutionEvent event) {
		// Forked lifecycle (e.g. the @execute phase="..." pattern) creates new
		// MojoExecutions. The wrapped mojoStarted hook already fires for each
		// nested mojo, so we don't need to re-sweep here — and a world-walk
		// sweep is unsafe because importing into ancestor realms (maven-core,
		// plexus) creates circular import chains that StackOverflow on lookup.
		if (delegate != null) {
			delegate.forkedProjectStarted(event);
		}
	}

	private void injectInto(ExecutionEvent event) {
		MojoExecution exec = event.getMojoExecution();
		if (exec == null) {
			return;
		}
		// Skip ourselves — our realm already has UsageStore.
		if (SELF_GROUP.equals(exec.getGroupId()) && SELF_ARTIFACT.equals(exec.getArtifactId())) {
			return;
		}
		PluginDescriptor descriptor = exec.getMojoDescriptor() != null
				? exec.getMojoDescriptor().getPluginDescriptor()
				: null;
		if (descriptor == null) {
			return;
		}
		ClassRealm realm = descriptor.getClassRealm();
		tryImport(realm);
	}

	private void tryImport(ClassRealm realm) {
		if (realm == null) {
			return;
		}
		// Skip realms that already see UsageStore through their own classpath or
		// imports. Importing into them creates either redundant work or, worse,
		// a circular import chain (parent realm imports from us, child imports
		// from parent, etc.) that causes StackOverflowError on class lookup.
		if (alreadyHasRuntime(realm)) {
			imported.putIfAbsent(realm, ImportState.IMPORTED);
			return;
		}
		ImportState prior = imported.get(realm);
		if (prior == ImportState.IMPORTED || prior == ImportState.URL_FALLBACK) {
			return; // already handled successfully — never retry
		}
		// FAILED or null → attempt(s) below. We re-attempt on FAILED so that a
		// realm whose state changed mid-build (e.g. late URL added by another
		// participant) can still be recovered.

		Throwable stage1Failure = null;
		// ── Stage 1: importFrom — preferred, keeps a single UsageStore class
		// across all realms so static state stays coherent.
		for (String pkg : IMPORTED_PACKAGES) {
			try {
				realm.importFrom(extensionRealm, pkg);
			} catch (Throwable t) {
				stage1Failure = t;
				break;
			}
		}
		if (stage1Failure == null && alreadyHasRuntime(realm)) {
			imported.put(realm, ImportState.IMPORTED);
			return;
		}

		// ── Stage 2: addURL fallback — gives the sealed/conflicting realm its
		// own copy of UsageStore. Static state diverges from the extension
		// realm's copy, but plugins that hit this path generally only LOAD
		// instrumented classes (they don't run tests), so divergent recordings
		// are discarded. Better than crashing the build.
		URL jarUrl = resolveRuntimeJarUrl();
		if (jarUrl != null) {
			try {
				realm.addURL(jarUrl);
				if (alreadyHasRuntime(realm)) {
					imported.put(realm, ImportState.URL_FALLBACK);
					System.err.println("[test-order] sealed-realm fallback: realm '" + safeRealmId(realm)
							+ "' rejected importFrom (" + describe(stage1Failure) + "), added " + RUNTIME_PACKAGE
							+ " via addURL — this plugin's UsageStore"
							+ " recordings will not aggregate with test forks.");
					return;
				}
			} catch (Throwable t) {
				// fall through to Stage 3
				stage1Failure = stage1Failure != null ? stage1Failure : t;
			}
		}

		// ── Stage 3: give up, mark FAILED so we don't keep logging on every
		// mojoStarted, and emit a diagnostic so users can act.
		if (imported.put(realm, ImportState.FAILED) != ImportState.FAILED) {
			System.err.println("[test-order] realm '" + safeRealmId(realm) + "' cannot resolve " + RUNTIME_PACKAGE
					+ " — instrumented classes loaded by this plugin will throw" + " NoClassDefFoundError. Cause: "
					+ describe(stage1Failure));
		}
	}

	/**
	 * Locates the jar containing {@code UsageStore} so we can hand its URL to a
	 * sealed realm via {@link ClassRealm#addURL}. Caches the result.
	 */
	private URL resolveRuntimeJarUrl() {
		URL cached = runtimeJarUrl.get();
		if (cached != null) {
			return cached;
		}
		URL probe = extensionRealm.getResource(RUNTIME_PROBE_CLASS.replace('.', '/') + ".class");
		if (probe == null) {
			return null;
		}
		// jar:file:/.../test-order-agent.jar!/me/...UsageStore.class → file URL
		// of the jar itself. Use URLConnection to extract the underlying jar.
		try {
			if ("jar".equals(probe.getProtocol())) {
				URLConnection conn = probe.openConnection();
				if (conn instanceof java.net.JarURLConnection jarConn) {
					URL jarFile = jarConn.getJarFileURL();
					runtimeJarUrl.compareAndSet(null, jarFile);
					return jarFile;
				}
			}
		} catch (Throwable t) {
			// fall through — caller treats null as "no fallback available"
		}
		return null;
	}

	private static String safeRealmId(ClassRealm realm) {
		try {
			return realm.getId();
		} catch (Throwable t) {
			return "<unidentified-realm>";
		}
	}

	private static String describe(Throwable t) {
		if (t == null) {
			return "(no exception)";
		}
		String msg = t.getMessage();
		return t.getClass().getSimpleName() + (msg != null ? ": " + msg : "");
	}

	private static boolean alreadyHasRuntime(ClassRealm realm) {
		try {
			realm.loadClass(RUNTIME_PROBE_CLASS);
			return true;
		} catch (Throwable t) {
			return false;
		}
	}

	// ── Delegate forwarding ──────────────────────────────────────────────

	@Override
	public void projectDiscoveryStarted(ExecutionEvent event) {
		if (delegate != null)
			delegate.projectDiscoveryStarted(event);
	}

	@Override
	public void sessionStarted(ExecutionEvent event) {
		if (delegate != null)
			delegate.sessionStarted(event);
	}

	@Override
	public void sessionEnded(ExecutionEvent event) {
		if (delegate != null)
			delegate.sessionEnded(event);
	}

	@Override
	public void projectSkipped(ExecutionEvent event) {
		if (delegate != null)
			delegate.projectSkipped(event);
	}

	@Override
	public void projectStarted(ExecutionEvent event) {
		if (delegate != null)
			delegate.projectStarted(event);
	}

	@Override
	public void projectSucceeded(ExecutionEvent event) {
		if (delegate != null)
			delegate.projectSucceeded(event);
	}

	@Override
	public void projectFailed(ExecutionEvent event) {
		if (delegate != null)
			delegate.projectFailed(event);
	}

	@Override
	public void mojoSkipped(ExecutionEvent event) {
		if (delegate != null)
			delegate.mojoSkipped(event);
	}

	@Override
	public void mojoSucceeded(ExecutionEvent event) {
		if (delegate != null)
			delegate.mojoSucceeded(event);
	}

	@Override
	public void mojoFailed(ExecutionEvent event) {
		if (delegate != null)
			delegate.mojoFailed(event);
	}

	@Override
	public void forkStarted(ExecutionEvent event) {
		if (delegate != null)
			delegate.forkStarted(event);
	}

	@Override
	public void forkSucceeded(ExecutionEvent event) {
		if (delegate != null)
			delegate.forkSucceeded(event);
	}

	@Override
	public void forkFailed(ExecutionEvent event) {
		if (delegate != null)
			delegate.forkFailed(event);
	}

	@Override
	public void forkedProjectSucceeded(ExecutionEvent event) {
		if (delegate != null)
			delegate.forkedProjectSucceeded(event);
	}

	@Override
	public void forkedProjectFailed(ExecutionEvent event) {
		if (delegate != null)
			delegate.forkedProjectFailed(event);
	}
}
