package me.bechberger.testorder.maven;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

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
	/** Tracks realms we've already imported into to avoid redundant work. */
	private final ConcurrentMap<ClassRealm, Boolean> imported = new ConcurrentHashMap<>();

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
		if (imported.putIfAbsent(realm, Boolean.TRUE) != null) {
			return;
		}
		// Skip realms that already see UsageStore through their own classpath or
		// imports. Importing into them creates either redundant work or, worse,
		// a circular import chain (parent realm imports from us, child imports
		// from parent, etc.) that causes StackOverflowError on class lookup.
		if (alreadyHasRuntime(realm)) {
			return;
		}
		for (String pkg : IMPORTED_PACKAGES) {
			try {
				realm.importFrom(extensionRealm, pkg);
			} catch (Throwable t) {
				// Realm might be sealed or have a conflicting import — ignore.
				// We've already marked it imported above; one failed package
				// shouldn't trigger retry storms on every mojo invocation.
			}
		}
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
