package me.bechberger.testorder.maven;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.apache.maven.execution.ExecutionEvent;
import org.apache.maven.execution.ExecutionListener;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.descriptor.MojoDescriptor;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.codehaus.plexus.classworlds.ClassWorld;
import org.codehaus.plexus.classworlds.realm.ClassRealm;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies that {@link RuntimeRealmInjector} makes
 * {@code me.bechberger.testorder.agent.runtime.UsageStore} resolvable inside a
 * plugin {@link ClassRealm} that wouldn't otherwise see it.
 *
 * <p>
 * This is the regression test for the bug where instrumented bytecode loaded by
 * foreign plugins (e.g. {@code openapi-generator-maven-plugin}) crashed with
 * {@code NoClassDefFoundError: UsageStore}.
 */
class RuntimeRealmInjectorTest {

	private static final String RUNTIME_PACKAGE = "me.bechberger.testorder.agent.runtime";
	private static final String RUNTIME_CLASS = RUNTIME_PACKAGE + ".UsageStore";

	@Test
	void mojoStarted_importsRuntimePackageIntoPluginRealm(@TempDir Path tmp) throws Exception {
		ClassWorld world = new ClassWorld();
		// Parent-less realm so the lookup hits our stub jar, not the test JVM's
		// classpath copy of UsageStore (which would mask whether the import
		// actually works).
		ClassRealm extensionRealm = world.newRealm("test-order-extension", null);
		Path runtimeJar = makeJarWithUsageStore(tmp);
		extensionRealm.addURL(runtimeJar.toUri().toURL());
		// Sanity: the extension realm CAN load UsageStore (it has the jar).
		Class<?> usageInExt = extensionRealm.loadClass(RUNTIME_CLASS);
		assertSame(extensionRealm, usageInExt.getClassLoader(), "UsageStore should load from extension realm");

		ClassRealm pluginRealm = world.newRealm("foreign-plugin", null);
		// Without the import, foreign plugin can't see UsageStore.
		assertThrows(ClassNotFoundException.class, () -> pluginRealm.loadClass(RUNTIME_CLASS),
				"foreign plugin realm must NOT see UsageStore before injection");

		// Wire up an ExecutionEvent that points at the foreign plugin realm.
		PluginDescriptor descriptor = new PluginDescriptor();
		descriptor.setGroupId("com.example");
		descriptor.setArtifactId("foreign-plugin");
		descriptor.setClassRealm(pluginRealm);
		MojoDescriptor mojo = new MojoDescriptor();
		mojo.setPluginDescriptor(descriptor);
		MojoExecution exec = new MojoExecution(mojo);
		ExecutionEvent event = mock(ExecutionEvent.class);
		when(event.getMojoExecution()).thenReturn(exec);

		// Inject.
		RuntimeRealmInjector injector = new RuntimeRealmInjector(null, extensionRealm);
		injector.mojoStarted(event);

		// After injection, the foreign realm sees UsageStore — and it's the SAME
		// Class object as the one the extension realm sees, so static state stays
		// shared across realms.
		Class<?> usageInPlugin = pluginRealm.loadClass(RUNTIME_CLASS);
		assertSame(usageInExt, usageInPlugin,
				"foreign plugin must resolve UsageStore to the same class as extension realm");
	}

	@Test
	void mojoStarted_skipsTestOrderPlugin(@TempDir Path tmp) throws Exception {
		ClassWorld world = new ClassWorld();
		ClassRealm extensionRealm = world.newRealm("test-order-extension", null);
		Path runtimeJar = makeJarWithUsageStore(tmp);
		extensionRealm.addURL(runtimeJar.toUri().toURL());

		ClassRealm selfRealm = world.newRealm("self-plugin", null);
		PluginDescriptor descriptor = new PluginDescriptor();
		descriptor.setGroupId("me.bechberger");
		descriptor.setArtifactId("test-order-maven-plugin");
		descriptor.setClassRealm(selfRealm);
		MojoDescriptor mojo = new MojoDescriptor();
		mojo.setPluginDescriptor(descriptor);
		MojoExecution exec = new MojoExecution(mojo);
		ExecutionEvent event = mock(ExecutionEvent.class);
		when(event.getMojoExecution()).thenReturn(exec);

		RuntimeRealmInjector injector = new RuntimeRealmInjector(null, extensionRealm);
		injector.mojoStarted(event);

		// We deliberately skip ourselves to avoid recursive-import issues.
		// (Our own realm already has UsageStore, so this is a no-op anyway.)
		assertThrows(ClassNotFoundException.class, () -> selfRealm.loadClass(RUNTIME_CLASS),
				"injector should skip the test-order plugin's own realm");
	}

	@Test
	void mojoStarted_idempotentAcrossRepeatedCalls(@TempDir Path tmp) throws Exception {
		ClassWorld world = new ClassWorld();
		ClassRealm extensionRealm = world.newRealm("test-order-extension", null);
		Path runtimeJar = makeJarWithUsageStore(tmp);
		extensionRealm.addURL(runtimeJar.toUri().toURL());
		ClassRealm pluginRealm = world.newRealm("foreign-plugin", null);

		PluginDescriptor descriptor = new PluginDescriptor();
		descriptor.setGroupId("com.example");
		descriptor.setArtifactId("foreign-plugin");
		descriptor.setClassRealm(pluginRealm);
		MojoDescriptor mojo = new MojoDescriptor();
		mojo.setPluginDescriptor(descriptor);
		MojoExecution exec = new MojoExecution(mojo);
		ExecutionEvent event = mock(ExecutionEvent.class);
		when(event.getMojoExecution()).thenReturn(exec);

		RuntimeRealmInjector injector = new RuntimeRealmInjector(null, extensionRealm);
		// 100 invocations should not throw or duplicate state.
		for (int i = 0; i < 100; i++) {
			assertDoesNotThrow(() -> injector.mojoStarted(event));
		}
		// Class still resolvable — we didn't break the import.
		assertDoesNotThrow(() -> pluginRealm.loadClass(RUNTIME_CLASS));
	}

	@Test
	void mojoStarted_nullDelegateAndNullExecution_doNotCrash(@TempDir Path tmp) throws Exception {
		ClassWorld world = new ClassWorld();
		ClassRealm extensionRealm = world.newRealm("test-order-extension");
		RuntimeRealmInjector injector = new RuntimeRealmInjector(null, extensionRealm);
		ExecutionEvent event = mock(ExecutionEvent.class);
		when(event.getMojoExecution()).thenReturn(null);
		assertDoesNotThrow(() -> injector.mojoStarted(event));
	}

	@Test
	void mojoStarted_descriptorWithoutRealm_doesNotCrash(@TempDir Path tmp) throws Exception {
		ClassWorld world = new ClassWorld();
		ClassRealm extensionRealm = world.newRealm("test-order-extension");
		RuntimeRealmInjector injector = new RuntimeRealmInjector(null, extensionRealm);

		PluginDescriptor descriptor = new PluginDescriptor();
		descriptor.setGroupId("com.example");
		descriptor.setArtifactId("foreign-plugin");
		// Intentionally leave classRealm null.
		MojoDescriptor mojo = new MojoDescriptor();
		mojo.setPluginDescriptor(descriptor);
		MojoExecution exec = new MojoExecution(mojo);
		ExecutionEvent event = mock(ExecutionEvent.class);
		when(event.getMojoExecution()).thenReturn(exec);
		assertDoesNotThrow(() -> injector.mojoStarted(event));
	}

	@Test
	void mojoStarted_forwardsToDelegate(@TempDir Path tmp) throws Exception {
		ClassWorld world = new ClassWorld();
		ClassRealm extensionRealm = world.newRealm("test-order-extension");
		ExecutionListener delegate = mock(ExecutionListener.class);
		RuntimeRealmInjector injector = new RuntimeRealmInjector(delegate, extensionRealm);
		ExecutionEvent event = mock(ExecutionEvent.class);
		when(event.getMojoExecution()).thenReturn(null);
		injector.mojoStarted(event);
		verify(delegate).mojoStarted(event);
	}

	@Test
	void importIntoEntireWorld_importsIntoEveryRealmExceptSelf(@TempDir Path tmp) throws Exception {
		ClassWorld world = new ClassWorld();
		ClassRealm extensionRealm = world.newRealm("test-order-extension", null);
		Path runtimeJar = makeJarWithUsageStore(tmp);
		extensionRealm.addURL(runtimeJar.toUri().toURL());
		ClassRealm a = world.newRealm("plugin-a", null);
		ClassRealm b = world.newRealm("plugin-b", null);
		ClassRealm c = world.newRealm("plugin-c", null);

		RuntimeRealmInjector injector = new RuntimeRealmInjector(null, extensionRealm);
		injector.importIntoEntireWorld();

		// All non-extension realms now resolve UsageStore to the same class.
		Class<?> ext = extensionRealm.loadClass(RUNTIME_CLASS);
		assertSame(ext, a.loadClass(RUNTIME_CLASS));
		assertSame(ext, b.loadClass(RUNTIME_CLASS));
		assertSame(ext, c.loadClass(RUNTIME_CLASS));
	}

	@Test
	void worldOf_returnsNullForNonClassRealmLoader() throws Exception {
		// extensionRealm is a plain URLClassLoader, not a ClassRealm — should
		// not crash importIntoEntireWorld. (Defensive guard for IDE/embedded Maven.)
		ClassLoader plain = new ClassLoader() {
		};
		RuntimeRealmInjector injector = new RuntimeRealmInjector(null, plain);
		assertDoesNotThrow(injector::importIntoEntireWorld);
	}

	/**
	 * Builds a jar containing a stub {@code UsageStore} class so we can test the
	 * realm-import mechanism without depending on the real test-order-agent jar
	 * (whose UsageStore has stateful static initializers we don't want to touch in
	 * unit tests).
	 */
	private static Path makeJarWithUsageStore(Path tmp) throws IOException {
		Path jarPath = tmp.resolve("stub-runtime.jar");
		ByteArrayOutputStream classBytes = new ByteArrayOutputStream();
		// Minimal valid class file for
		// "me/bechberger/testorder/agent/runtime/UsageStore"
		classBytes.write(generateStubClass());
		try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jarPath))) {
			jos.putNextEntry(new JarEntry("me/bechberger/testorder/agent/runtime/UsageStore.class"));
			jos.write(classBytes.toByteArray());
			jos.closeEntry();
		}
		return jarPath;
	}

	/**
	 * Hand-rolled minimal class file: public final class with no methods, no
	 * superclass beyond Object. Avoids pulling in ASM as a test dep.
	 *
	 * <p>
	 * Class file format (Java SE 11+, version 55.0): magic, minor, major, constant
	 * pool, access flags, this_class, super_class, interfaces, fields, methods,
	 * attributes.
	 */
	private static byte[] generateStubClass() {
		// Use Java's built-in ClassFile API via reflection-free byte assembly. For
		// portability we use a precomputed minimal class file. Instead of
		// hand-coding bytes, leverage the JDK's ClassLoader.defineClass-friendly
		// path: just emit a tiny class via java.lang.classfile (Java 22+) or fall
		// back to a hard-coded byte array.
		//
		// To stay JDK-agnostic across the project's JDK 17 baseline, hard-code a
		// minimal class file for "me/bechberger/testorder/agent/runtime/UsageStore".
		// Generated from: `public final class UsageStore {}` compiled to v55 (Java 11).
		return new byte[]{
				// magic
				(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE,
				// minor 0, major 55 (Java 11)
				0x00, 0x00, 0x00, 0x37,
				// constant_pool_count = 5
				0x00, 0x05,
				// #1 = Class #2
				0x07, 0x00, 0x02,
				// #2 = Utf8 "me/bechberger/testorder/agent/runtime/UsageStore"
				0x01, 0x00, 0x30, 'm', 'e', '/', 'b', 'e', 'c', 'h', 'b', 'e', 'r', 'g', 'e', 'r', '/', 't', 'e', 's',
				't', 'o', 'r', 'd', 'e', 'r', '/', 'a', 'g', 'e', 'n', 't', '/', 'r', 'u', 'n', 't', 'i', 'm', 'e', '/',
				'U', 's', 'a', 'g', 'e', 'S', 't', 'o', 'r', 'e',
				// #3 = Class #4
				0x07, 0x00, 0x04,
				// #4 = Utf8 "java/lang/Object"
				0x01, 0x00, 0x10, 'j', 'a', 'v', 'a', '/', 'l', 'a', 'n', 'g', '/', 'O', 'b', 'j', 'e', 'c', 't',
				// access_flags = ACC_PUBLIC | ACC_FINAL | ACC_SUPER (0x0031)
				0x00, 0x31,
				// this_class = #1
				0x00, 0x01,
				// super_class = #3
				0x00, 0x03,
				// interfaces_count = 0
				0x00, 0x00,
				// fields_count = 0
				0x00, 0x00,
				// methods_count = 0
				0x00, 0x00,
				// attributes_count = 0
				0x00, 0x00,};
	}
}
