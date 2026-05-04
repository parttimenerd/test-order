package com.example.fields;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests StateMachine - exercises complex field interdependencies. Tests
 * transitions and state changes.
 */
class StateMachineTest {

	private StateMachine machine;

	@BeforeEach
	void setUp() {
		machine = new StateMachine();
	}

	@Test
	void testInitialState() {
		assertEquals(StateMachine.State.IDLE, machine.getState());
		assertEquals(0, machine.getStateCounter());
	}

	@Test
	void testStart() {
		machine.start();
		assertEquals(StateMachine.State.RUNNING, machine.getState());
		assertEquals(1, machine.getStateCounter());
		assertEquals("start", machine.getLastTransition());
	}

	@Test
	void testStartStop() {
		machine.start();
		assertTrue(machine.isRunning());
		machine.stop();
		assertEquals(StateMachine.State.STOPPED, machine.getState());
		assertEquals(2, machine.getStateCounter());
		assertEquals("stop", machine.getLastTransition());
	}

	@Test
	void testReset() {
		machine.start();
		machine.stop();
		machine.reset();
		assertEquals(StateMachine.State.IDLE, machine.getState());
		assertEquals(0, machine.getStateCounter());
	}

	@Test
	void testError() {
		machine.start();
		machine.error("test error");
		assertEquals(StateMachine.State.ERROR, machine.getState());
		assertTrue(machine.getLastTransition().contains("error"));
	}

	@Test
	void testStateTransitions() {
		assertEquals(0, machine.getStateCounter());
		machine.start();
		machine.stop();
		machine.start(); // Should not transition from STOPPED
		assertEquals(2, machine.getStateCounter());
	}
}
