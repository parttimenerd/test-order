package me.bechberger.testorder.maven;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import me.bechberger.testorder.changes.StaticCallGraphAnalyzer;
import me.bechberger.testorder.changes.StructuralChangeAnalyzer.ChangedMembers;
import me.bechberger.testorder.ops.PluginContext;
import me.bechberger.testorder.ops.workflows.ChangeAnalysis;

/**
 * Diagnostic goal: prints the result of static call-graph analysis without
 * running tests. Shows which members were initially detected as changed and
 * which additional members were pulled in by transitive call-graph expansion.
 *
 * <p>
 * Usage: {@code mvn test-order:show-static-analysis}
 */
@Mojo(name = "show-static-analysis", defaultPhase = LifecyclePhase.PROCESS_TEST_CLASSES, aggregator = true)
public class ShowStaticAnalysisMojo extends AbstractTestOrderMojo {

	/** Print every discovered caller (verbose). */
	@Parameter(property = "testorder.showStaticAnalysis.verbose", defaultValue = "false")
	private boolean verbose;

	@Override
	protected void validateParameters() throws MojoExecutionException {
		ParameterValidator validator = new ParameterValidator(getLog());
		validator.validateChangeMode(changeMode);
	}

	@Override
	public void execute() throws MojoExecutionException {
		initContext();
		if (skip)
			return;

		PluginContext pctx = buildPluginContextBuilder().build();

		ChangeAnalysis.Result analysis;
		try {
			analysis = ChangeAnalysis.analyze(pctx, ChangeAnalysis.Options.FULL);
		} catch (IOException e) {
			throw new MojoExecutionException("Failed to analyze changes", e);
		}

		ChangedMembers expanded = analysis.changedMembers();
		ChangedMembers seed = analysis.preSaChangedMembers();
		Set<String> changedClasses = analysis.changedClasses();

		System.out.println("─── test-order static call-graph analysis ───");
		System.out.println("staticAnalysis.enabled = " + pctx.staticAnalysisEnabled());
		System.out.println("staticAnalysis.depth   = " + pctx.staticAnalysisDepth());
		System.out.println("classesDir             = " + pctx.classesDir());
		System.out.println("testClassesDir         = " + pctx.testClassesDir());
		System.out.println();

		if (changedClasses.isEmpty()) {
			System.out.println("(no changed classes detected — nothing to expand)");
			return;
		}

		System.out.println("changed classes (" + changedClasses.size() + "):");
		for (String c : changedClasses) {
			System.out.println("  " + c);
		}
		System.out.println();

		if (expanded == null) {
			System.out.println("(no structural / member-level info available — only class-level changes)");
			return;
		}

		List<Path> classDirs = new ArrayList<>();
		if (pctx.classesDir() != null)
			classDirs.add(pctx.classesDir());
		if (pctx.testClassesDir() != null)
			classDirs.add(pctx.testClassesDir());

		// Use the pre-SA snapshot as the seed. Fall back to synthesized class markers
		// if SA wasn't enabled (seed == null means SA ran but changedMembers was null
		// before expansion, which shouldn't happen; null seed also means SA was off).
		if (seed == null) {
			seed = synthesizeSeed(changedClasses, expanded.changedStaticFieldKeys());
		}

		Set<String> addedKeys = new LinkedHashSet<>(expanded.changedMemberKeys());
		addedKeys.removeAll(seed.changedMemberKeys());
		Set<String> addedClasses = new LinkedHashSet<>(expanded.changedClasses());
		addedClasses.removeAll(seed.changedClasses());

		System.out.println("after expansion (depth " + pctx.staticAnalysisDepth() + "):");
		System.out.println("  seed members:   " + seed.changedMemberKeys().size());
		System.out.println("  total members:  " + expanded.changedMemberKeys().size());
		System.out.println("  newly added:    " + addedKeys.size());
		System.out.println("  total classes:  " + expanded.changedClasses().size());
		System.out.println("  classes added:  " + addedClasses.size());
		System.out.println("  classDirs:      " + classDirs);

		if (!seed.changedMemberKeys().isEmpty()) {
			System.out.println();
			System.out.println("seed members (directly changed):");
			Map<String, List<String>> seedByClass = new LinkedHashMap<>();
			for (String k : seed.changedMemberKeys()) {
				int hash = k.lastIndexOf('#');
				String cls = hash > 0 ? k.substring(0, hash) : k;
				seedByClass.computeIfAbsent(cls, x -> new ArrayList<>()).add(k);
			}
			for (var e : seedByClass.entrySet()) {
				System.out.println("  " + e.getKey());
				for (String k : e.getValue()) {
					System.out.println("    " + k.substring(k.lastIndexOf('#') + 1));
				}
			}
		}

		if (!addedKeys.isEmpty()) {
			System.out.println();
			System.out.println("newly discovered callers:");
			Map<String, List<String>> byClass = new LinkedHashMap<>();
			for (String k : addedKeys) {
				int hash = k.lastIndexOf('#');
				String cls = hash > 0 ? k.substring(0, hash) : k;
				byClass.computeIfAbsent(cls, x -> new ArrayList<>()).add(k);
			}
			int shown = 0;
			int limit = verbose ? Integer.MAX_VALUE : 40;
			outer : for (var e : byClass.entrySet()) {
				System.out.println("  " + e.getKey());
				for (String k : e.getValue()) {
					if (shown >= limit) {
						System.out.println("  ... (" + (addedKeys.size() - shown)
								+ " more, use -Dtestorder.showStaticAnalysis.verbose=true)");
						break outer;
					}
					System.out.println("    " + k.substring(k.lastIndexOf('#') + 1));
					shown++;
				}
			}
		}
	}

	private static ChangedMembers synthesizeSeed(Set<String> classes, Set<String> staticFields) {
		Set<String> keys = new LinkedHashSet<>();
		Map<String, Set<String>> byClass = new LinkedHashMap<>();
		for (String cls : classes) {
			keys.add(cls + "#" + StaticCallGraphAnalyzer.CLASS_MARKER);
			byClass.put(cls, Collections.singleton(StaticCallGraphAnalyzer.CLASS_MARKER));
		}
		return new ChangedMembers(new LinkedHashSet<>(classes), Collections.unmodifiableSet(keys),
				Collections.unmodifiableMap(byClass), Collections.emptySet(),
				staticFields != null ? staticFields : Set.of());
	}
}
