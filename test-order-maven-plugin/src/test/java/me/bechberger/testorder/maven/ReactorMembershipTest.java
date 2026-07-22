package me.bechberger.testorder.maven;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Guards multi-module inference against an unrelated ancestor reactor. A
 * standalone project that merely lives in a subdirectory of another Maven
 * project (which has its own {@code .mvn/} and {@code .test-order/} index) must
 * NOT inherit that ancestor's modules as siblings — doing so contaminated the
 * changed-class set with hundreds of unrelated classes (BUG-167).
 */
class ReactorMembershipTest {

	@TempDir
	Path tempDir;

	private void writePom(Path dir, String... modules) throws IOException {
		Files.createDirectories(dir);
		StringBuilder sb = new StringBuilder("<project><modules>");
		for (String m : modules) {
			sb.append("<module>").append(m).append("</module>");
		}
		sb.append("</modules></project>");
		Files.writeString(dir.resolve("pom.xml"), sb.toString());
	}

	@Test
	void projectListedInReactorModulesIsMember() throws IOException {
		Path reactor = tempDir.resolve("reactor");
		writePom(reactor, "mod-a", "mod-b");
		Path modA = reactor.resolve("mod-a");
		writePom(modA);

		assertThat(AbstractTestOrderMojo.reactorContainsProject(reactor, modA)).isTrue();
	}

	@Test
	void reactorRootItselfIsMember() throws IOException {
		Path reactor = tempDir.resolve("reactor");
		writePom(reactor, "mod-a");
		writePom(reactor.resolve("mod-a"));

		assertThat(AbstractTestOrderMojo.reactorContainsProject(reactor, reactor)).isTrue();
	}

	@Test
	void nestedSubmoduleIsMember() throws IOException {
		Path reactor = tempDir.resolve("reactor");
		writePom(reactor, "mod-a");
		Path modA = reactor.resolve("mod-a");
		writePom(modA, "mod-a-child");
		Path child = modA.resolve("mod-a-child");
		writePom(child);

		assertThat(AbstractTestOrderMojo.reactorContainsProject(reactor, child)).isTrue();
	}

	@Test
	void unrelatedDescendantNotInModulesIsNotMember() throws IOException {
		// BUG-167: reactor is an ancestor DIRECTORY of the project (e.g. the plugin's
		// own repo containing third-party/commons-lang), but commons-lang is NOT one of
		// the reactor's <modules>. It must NOT be treated as a reactor member.
		Path reactor = tempDir.resolve("reactor");
		writePom(reactor, "mod-a", "mod-b");
		writePom(reactor.resolve("mod-a"));
		writePom(reactor.resolve("mod-b"));
		// A standalone project vendored under the reactor tree but not declared as a
		// module.
		Path vendored = reactor.resolve("third-party/commons-lang");
		writePom(vendored);

		assertThat(AbstractTestOrderMojo.reactorContainsProject(reactor, vendored)).isFalse();
	}

	@Test
	void nullReactorRootIsNotMember() {
		assertThat(AbstractTestOrderMojo.reactorContainsProject(null, tempDir)).isFalse();
	}
}
