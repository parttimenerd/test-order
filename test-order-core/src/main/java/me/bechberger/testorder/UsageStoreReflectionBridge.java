package me.bechberger.testorder;

import java.lang.reflect.Method;

/**
 * Reflection bridge to the test-order agent's {@code UsageStore}.
 * <p>
 * Both JUnit and TestNG listeners need to communicate test class/method
 * boundaries to the agent via reflection (since the agent is loaded via
 * {@code -javaagent} and classes live in the bootstrap/system classloader).
 * This class encapsulates that reflection logic.
 */
public final class UsageStoreReflectionBridge {

	private Object usageStoreInstance;
	private Method startTestClassMethod;
	private Method endTestClassMethod;
	private Method startTestMethodMethod;
	private Method endTestMethodMethod;

	private final boolean fullMethodMode;

	public UsageStoreReflectionBridge(boolean fullMethodMode) {
		this.fullMethodMode = fullMethodMode;
	}

	/**
	 * Returns {@code true} if the bridge was initialized successfully and the
	 * UsageStore instance is available.
	 */
	public boolean isAvailable() {
		return usageStoreInstance != null;
	}

	/**
	 * Initializes reflection access to UsageStore. Must be called once before using
	 * the bridge.
	 */
	public void init() {
		try {
			Class<?> usageStoreClass = resolveUsageStoreClass();
			Object instance = usageStoreClass.getMethod("getInstance").invoke(null);
			Method startClass = usageStoreClass.getMethod("startTestClass", String.class);
			Method endClass = usageStoreClass.getMethod("endTestClass", String.class);
			// Only assign fields once all required methods resolved successfully
			usageStoreInstance = instance;
			startTestClassMethod = startClass;
			endTestClassMethod = endClass;
			if (fullMethodMode) {
				startTestMethodMethod = usageStoreClass.getMethod("startTestMethod", String.class, String.class);
				endTestMethodMethod = usageStoreClass.getMethod("endTestMethod");
			}
		} catch (Exception e) {
			usageStoreInstance = null; // ensure bridge reports as unavailable on partial failure
			TestOrderLogger.error("Failed to initialize UsageStore reflection: {}", e.getMessage());
		}
	}

	/**
	 * Resolves the UsageStore class. In online (agent) mode, it's on the bootstrap
	 * classloader. In offline mode, it's on the regular test classpath.
	 */
	private static Class<?> resolveUsageStoreClass() throws ClassNotFoundException {
		try {
			// Online mode: agent places runtime jar on bootstrap classpath
			return Class.forName("me.bechberger.testorder.agent.runtime.UsageStore", true, null);
		} catch (ClassNotFoundException e) {
			// Offline mode: runtime jar on test classpath (context or system classloader)
			ClassLoader cl = Thread.currentThread().getContextClassLoader();
			if (cl != null) {
				try {
					return Class.forName("me.bechberger.testorder.agent.runtime.UsageStore", true, cl);
				} catch (ClassNotFoundException ignored) {
				}
			}
			return Class.forName("me.bechberger.testorder.agent.runtime.UsageStore");
		}
	}

	public void callStartTestClass(String testClassName) {
		if (usageStoreInstance == null)
			return;
		try {
			startTestClassMethod.invoke(usageStoreInstance, testClassName);
		} catch (Exception e) {
			TestOrderLogger.debug("Failed to call startTestClass: {}", e.getMessage());
		}
	}

	public void callEndTestClass(String testClassName) {
		if (usageStoreInstance == null)
			return;
		try {
			endTestClassMethod.invoke(usageStoreInstance, testClassName);
		} catch (Exception e) {
			TestOrderLogger.debug("Failed to call endTestClass: {}", e.getMessage());
		}
	}

	public void callStartTestMethod(String className, String methodName) {
		if (usageStoreInstance == null || startTestMethodMethod == null)
			return;
		try {
			startTestMethodMethod.invoke(usageStoreInstance, className, methodName);
		} catch (Exception e) {
			TestOrderLogger.debug("Failed to call startTestMethod: {}", e.getMessage());
		}
	}

	public void callEndTestMethod() {
		if (usageStoreInstance == null || endTestMethodMethod == null)
			return;
		try {
			endTestMethodMethod.invoke(usageStoreInstance);
		} catch (Exception e) {
			TestOrderLogger.debug("Failed to call endTestMethod: {}", e.getMessage());
		}
	}
}
