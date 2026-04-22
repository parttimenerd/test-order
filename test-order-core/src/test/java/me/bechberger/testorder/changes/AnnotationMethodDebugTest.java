package me.bechberger.testorder.changes;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

class AnnotationMethodDebugTest {

	@Test
	void parsesRepeatableAnnotationElementMethodsDeterministically() {
		String source = """
				package p;

				import java.lang.annotation.Repeatable;

				@Repeatable(Tags.class)
				@interface Tag {
				    String value();
				}

				@interface Tags {
				    Tag[] value();
				}

				class Regular {
				    void run() {
				    }
				}
				""";

		SourceFileModel.Model model = SourceFileModel.parse(source, "p", SourceFileModel.Detail.METHODS);

		assertTrue(model.typeNames().containsAll(Set.of("p.Tag", "p.Tags", "p.Regular")));

		Set<String> methodNames = model.methods().stream().map(SourceFileModel.MethodNode::name)
				.collect(Collectors.toSet());
		assertTrue(methodNames.containsAll(Set.of("value", "run")));

		var annotationElementMethods = model.methods().stream()
				.filter(m -> Set.of("p.Tag", "p.Tags").contains(m.enclosingFqcn()))
				.filter(m -> m.name().equals("value")).toList();

		assertFalse(annotationElementMethods.isEmpty());
		assertTrue(annotationElementMethods.stream().allMatch(SourceFileModel.MethodNode::isAbstract));
		assertTrue(annotationElementMethods.stream().noneMatch(SourceFileModel.MethodNode::isConstructor));
	}
}
