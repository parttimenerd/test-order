package me.bechberger.testorder.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;

import org.junit.jupiter.api.Test;

class AsmClassTransformerSelectiveTest {

	private AsmClassTransformer createTransformerWithUncertain(Set<String> uncertain) {
		Agent agent = Agent.parse("includePackages=com.example");
		return new AsmClassTransformer(agent, uncertain);
	}

	@Test
	void nullUncertainSetInstrumentsAllFilteredClasses() {
		AsmClassTransformer t = createTransformerWithUncertain(null);
		assertTrue(t.shouldInstrument("com/example/Foo"));
		assertTrue(t.shouldInstrument("com/example/Bar"));
	}

	@Test
	void emptyUncertainSetBlocksAllInstrumentation() {
		AsmClassTransformer t = createTransformerWithUncertain(Set.of());
		assertFalse(t.shouldInstrument("com/example/Foo"));
		assertFalse(t.shouldInstrument("com/example/Bar"));
	}

	@Test
	void uncertainSetGatesOnlyMatchingClasses() {
		AsmClassTransformer t = createTransformerWithUncertain(Set.of("com.example.Foo"));
		assertTrue(t.shouldInstrument("com/example/Foo"));
		assertFalse(t.shouldInstrument("com/example/Bar"));
	}

	@Test
	void filterStillAppliesBeforeUncertainCheck() {
		// com/other is not in includePackages=com.example, so filter rejects it
		// even if uncertain set contains it
		AsmClassTransformer t = createTransformerWithUncertain(Set.of("com.other.Excluded"));
		assertFalse(t.shouldInstrument("com/other/Excluded"));
	}

	@Test
	void moduleInfoAlwaysSkipped() {
		AsmClassTransformer t = createTransformerWithUncertain(null);
		assertFalse(t.shouldInstrument("module-info"));
		assertFalse(t.shouldInstrument("com/example/module-info"));
	}
}
