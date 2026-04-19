package me.bechberger.testorder.agent;

import javassist.*;
import javassist.expr.ExprEditor;
import javassist.expr.FieldAccess;

import me.bechberger.testorder.agent.runtime.AgentLogger;
import me.bechberger.testorder.agent.runtime.ClassIdMap;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Instruments classes to record usage via static UsageStore fast-path calls.
 * <p>
 * Supports configurable intelligent filtering:
 * <ul>
 *     <li><b>WHITELIST</b>: Only instrument explicitly listed packages</li>
 *     <li><b>BLACKLIST</b>: Instrument all except excluded packages</li>
 *     <li><b>SMART</b>: Combination of whitelist (if specified) + heuristics (default)</li>
 *     <li><b>WHITELIST_SMART</b>: Strict whitelist with heuristics (no fallback to all)</li>
 * </ul>
 * 
 * Supports multiple instrumentation modes: METHOD_ENTRY, FULL, FULL_METHOD, FULL_MEMBER.
 */
public class ClassTransformer implements ClassFileTransformer {

    enum FieldTrackingMode {
        NONE,
        STATIC_ONLY,
        ALL
    }

    private final IntelligentClassFilter filter;
    private final Agent.InstrumentationMode mode;
    private final FieldTrackingMode fieldTrackingMode;
    private final ClassIdMap classIdMap = ClassIdMap.getInstance();
    private final ClassPool baseClassPool;
    private final ConcurrentHashMap<ClassLoader, ClassPool> loaderPools = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> classCallCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> memberCallCache = new ConcurrentHashMap<>();

    public ClassTransformer(Agent options) {
        this.mode = options.getMode();
        this.fieldTrackingMode = fieldTrackingModeFor(mode);
        this.baseClassPool = new ClassPool();
        this.baseClassPool.appendSystemPath();

        // Build intelligent filter from options
        IntelligentClassFilter.Builder filterBuilder = new IntelligentClassFilter.Builder()
                .strategy(options.getFilterStrategy())
                .skipTestClasses(options.isSkipTestClasses())
                .useHeuristics(options.isUseHeuristics());
        
        // Add explicit includes (convert to slash-separated class names)
        for (String pkg : options.getIncludePackages()) {
            filterBuilder.explicitInclude(pkg);
        }
        
        // Add explicit excludes
        for (String pkg : options.getExcludePackages()) {
            filterBuilder.explicitExclude(pkg);
        }
        
        // Auto-detect packages from project structure if enabled and no explicit includes
        if (options.isAutoDetectPackages() && options.getIncludePackages().isEmpty()) {
            ProjectStructureAnalyzer analyzer = new ProjectStructureAnalyzer(options.getProjectRoot());
            
            // Use analysis result if we found user packages
            if (!analyzer.getUserPackages().isEmpty()) {
                AgentLogger.log("[ClassTransformer] Auto-detected user packages: " + analyzer.getUserPackages());
                
                // Add detected user packages as includes
                for (String pkg : analyzer.getUserPackages()) {
                    filterBuilder.explicitInclude(pkg);
                }
                
                // Add detected dependency packages as excludes
                for (String pkg : analyzer.getDependencyPackages()) {
                    filterBuilder.explicitExclude(pkg);
                }
                
                // Add detected test packages as excludes
                for (String pkg : analyzer.getTestPackages()) {
                    filterBuilder.explicitExclude(pkg);
                }
            }
        }
        
        this.filter = filterBuilder.build();
    }

    static FieldTrackingMode fieldTrackingModeFor(Agent.InstrumentationMode mode) {
        return switch (mode) {
            case METHOD_ENTRY -> FieldTrackingMode.NONE;
            case FULL, FULL_METHOD -> FieldTrackingMode.STATIC_ONLY;
            case FULL_MEMBER -> FieldTrackingMode.ALL;
        };
    }

    private ClassPool classPoolFor(ClassLoader loader) {
        if (loader == null) {
            return baseClassPool;
        }
        ClassPool existing = loaderPools.get(loader);
        if (existing != null) {
            return existing;
        }
        ClassPool cp = new ClassPool(baseClassPool);
        cp.appendClassPath(new LoaderClassPath(loader));
        ClassPool prev = loaderPools.putIfAbsent(loader, cp);
        return prev != null ? prev : cp;
    }

    private String recordClassCall(String fqcn) {
        String cached = classCallCache.get(fqcn);
        if (cached != null) return cached;
        int classId = classIdMap.getOrRegisterClass(fqcn);
        String result = (classId < 0) ? ""
                : "me.bechberger.testorder.agent.runtime.UsageStore.recordUsageIdFast(" + classId + ");";
        String prev = classCallCache.putIfAbsent(fqcn, result);
        return prev != null ? prev : result;
    }

    private String recordMemberCall(String memberKey) {
        String cached = memberCallCache.get(memberKey);
        if (cached != null) return cached;
        int memberId = classIdMap.getOrRegisterMember(memberKey);
        String result = (memberId < 0) ? ""
                : "me.bechberger.testorder.agent.runtime.UsageStore.recordMemberUsageIdFast(" + memberId + ");";
        String prev = memberCallCache.putIfAbsent(memberKey, result);
        return prev != null ? prev : result;
    }

    @Override
    public byte[] transform(Module module, ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (className == null) {
            return classfileBuffer;
        }
        if (!shouldInstrument(className)) {
            return classfileBuffer;
        }
        try {
            ClassPool cp = classPoolFor(loader);
            synchronized (cp) {
                CtClass cc = null;
                try {
                    cc = cp.makeClass(new ByteArrayInputStream(classfileBuffer));
                    if (cc.isFrozen()) {
                        return classfileBuffer;
                    }
                    // skip classes without a SourceFile attribute (generated at runtime)
                    if (cc.getClassFile().getAttribute("SourceFile") == null) {
                        return classfileBuffer;
                    }
                    instrument(className, cc);
                    byte[] result = cc.toBytecode();
                    if (AgentLogger.isVerbose()) {
                        AgentLogger.log("[transform] OK: " + className + " (" + classfileBuffer.length + " -> " + result.length + " bytes)");
                    }
                    return result;
                } finally {
                    if (cc != null) {
                        cc.detach(); // release from the scoped pool to avoid unbounded memory growth
                    }
                }
            }
        } catch (CannotCompileException | IOException | RuntimeException e) {
            if (AgentLogger.isVerbose()) {
                AgentLogger.log("[transform] FAIL: " + className + " -> " + e);
            }
            return classfileBuffer;
        }
    }

    boolean shouldInstrument(String className) {
        // module descriptors are not instrumentable classes
        if (className.equals("module-info") || className.endsWith("/module-info")) {
            return false;
        }
        // Use intelligent filter with caching
        return filter.shouldInstrument(className);
    }

    private final ConcurrentHashMap<String, String> slashToDotCache = new ConcurrentHashMap<>();

    private void instrument(String className, CtClass cc) throws CannotCompileException {
        String fqcn = slashToDotCache.get(className);
        if (fqcn == null) {
            fqcn = className.replace('/', '.');
            String prev = slashToDotCache.putIfAbsent(className, fqcn);
            if (prev != null) fqcn = prev;
        }
        String initialCall = recordClassCall(fqcn);
        if (initialCall.isEmpty()) {
            return;
        }
        StringBuilder callBuilder = new StringBuilder(initialCall);

        // Also record: (a) interfaces this class directly implements, and (b) interface-typed
        // declared fields — when their names match includePackages. This ensures that
        // changes to interface-only application types (e.g. Spring Data JPA repositories)
        // are captured in the dependency index even though those interfaces cannot be
        // directly instrumented (no method bodies to inject into).
        Set<String> extra = null;
        try {
            CtClass[] interfaces = cc.getInterfaces();
            if (interfaces.length > 0) {
                for (CtClass iface : interfaces) {
                    String ifaceSlash = iface.getName().replace('.', '/');
                    if (!ifaceSlash.equals(className) && shouldInstrument(ifaceSlash)) {
                        if (extra == null) extra = new HashSet<>();
                        extra.add(iface.getName());
                    }
                }
            }
        } catch (NotFoundException ignored) {}
        try {
            CtField[] fields = cc.getDeclaredFields();
            if (fields.length > 0) {
                for (CtField field : fields) {
                    try {
                        CtClass ft = field.getType();
                        if (ft.isInterface()) {
                            String ftSlash = ft.getName().replace('.', '/');
                            if (!ftSlash.equals(className) && shouldInstrument(ftSlash)) {
                                if (extra == null) extra = new HashSet<>();
                                extra.add(ft.getName());
                            }
                        }
                    } catch (NotFoundException ignored) {}
                }
            }
        } catch (RuntimeException ignored) {}
        if (extra != null) {
            for (String extraFqcn : extra) {
                String extraCall = recordClassCall(extraFqcn);
                if (!extraCall.isEmpty()) {
                    callBuilder.append(extraCall);
                }
            }
        }

        String call = callBuilder.toString();
        switch (mode) {
            case FULL_MEMBER -> instrumentMethodEntry(cc, fqcn, call, true);
            case FULL, FULL_METHOD -> instrumentMethodEntry(cc, fqcn, call, false);
            case METHOD_ENTRY -> instrumentMethodEntry(cc, fqcn, call, false);
        }
    }

    private void instrumentMethodEntry(CtClass cc, String selfFqcn, String selfCall, boolean recordMembers)
            throws CannotCompileException {
        boolean includeFieldAccess = fieldTrackingMode != FieldTrackingMode.NONE;
        // Reuse a single FieldAccessCollector per class; reset() between methods.
        FieldAccessCollector fieldCollector = includeFieldAccess
                ? new FieldAccessCollector(selfFqcn, recordMembers) : null;

        // instrument all declared methods
        for (CtMethod method : cc.getDeclaredMethods()) {
            if (Modifier.isAbstract(method.getModifiers()) || Modifier.isNative(method.getModifiers())) {
                continue;
            }
            try {
                String entryCall = buildEntryCall(selfCall,
                        recordMembers ? recordMemberCall(selfFqcn + "#" + method.getName()) : "");
                method.insertBefore(entryCall);
                if (fieldCollector != null) {
                    fieldCollector.reset();
                    method.instrument(fieldCollector);
                }
            } catch (CannotCompileException e) {
                // skip methods that can't be instrumented (e.g., synthetic bridge methods)
            }
        }
        // instrument constructors
        for (CtConstructor ctor : cc.getDeclaredConstructors()) {
            if (Modifier.isAbstract(ctor.getModifiers())) {
                continue;
            }
            try {
                String entryCall = buildEntryCall(selfCall,
                        recordMembers ? recordMemberCall(selfFqcn + "#<init>") : "");
                ctor.insertBefore(entryCall);
                if (fieldCollector != null) {
                    fieldCollector.reset();
                    ctor.instrument(fieldCollector);
                }
            } catch (CannotCompileException e) {
                // skip
            }
        }
        // also instrument static initializer if present
        CtConstructor clinit = cc.getClassInitializer();
        if (clinit != null) {
            String entryCall = buildEntryCall(selfCall,
                    recordMembers ? recordMemberCall(selfFqcn + "#<clinit>") : "");
            clinit.insertBefore(entryCall);
            if (fieldCollector != null) {
                try {
                    fieldCollector.reset();
                    clinit.instrument(fieldCollector);
                } catch (CannotCompileException e) {
                    // skip
                }
            }
        }
    }

    private String buildEntryCall(String classCall, String memberCall) {
        if (memberCall == null || memberCall.isEmpty()) {
            return classCall;
        }
        return classCall + memberCall;
    }

    /**
     * ExprEditor that instruments field accesses to record the declaring class
     * of the accessed field. Only instruments accesses to classes other than the
     * current class (self-accesses are already recorded by method-entry instrumentation)
     * and skips JDK/framework classes.
     */
    private class FieldAccessCollector extends ExprEditor {
        private final String selfFqcn;
        private final boolean recordMembers;
        private final Set<String> seenClasses = new HashSet<>(64);
        private final Set<String> seenMembers = new HashSet<>(64);

        FieldAccessCollector(String selfFqcn, boolean recordMembers) {
            this.selfFqcn = selfFqcn;
            this.recordMembers = recordMembers;
        }

        /** Clear dedup sets between methods so the collector can be reused per class. */
        void reset() {
            seenClasses.clear();
            seenMembers.clear();
        }

        @Override
        public void edit(FieldAccess fa) throws CannotCompileException {
            String declaringClass;
            String fieldName;
            try {
                declaringClass = fa.getClassName();
                fieldName = fa.getFieldName();
            } catch (Exception e) {
                return;
            }
            // skip self — already recorded by method-entry instrumentation
            if (declaringClass.equals(selfFqcn)) {
                return;
            }
            // skip JDK/framework and generated classes using intelligent filter
            String internalName = declaringClass.replace('.', '/');
            if (!filter.shouldInstrument(internalName)) {
                return;
            }
            if (fieldTrackingMode == FieldTrackingMode.STATIC_ONLY && !fa.isStatic()) {
                return;
            }
            String classCall = "";
            // class-level recording (deduplicated per declaring class per method)
            if (seenClasses.add(declaringClass)) {
                classCall = recordClassCall(declaringClass);
            }
            String memberCall = "";
            // member-level recording (deduplicated per declaring class#field per method)
            if (recordMembers) {
                String memberKey = declaringClass + "#" + fieldName;
                if (seenMembers.add(memberKey)) {
                    memberCall = recordMemberCall(memberKey);
                }
            }
            if (classCall.isEmpty() && memberCall.isEmpty()) {
                return;
            }
            StringBuilder replacement = new StringBuilder("{ ");
            if (!classCall.isEmpty()) {
                replacement.append(classCall).append(' ');
            }
            if (!memberCall.isEmpty()) {
                replacement.append(memberCall).append(' ');
            }
            replacement.append("$_ = $proceed($$); }");
            fa.replace(replacement.toString());
        }
    }
}
