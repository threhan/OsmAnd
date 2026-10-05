package net.osmand.util;

import org.junit.Test;
import static org.junit.Assert.*;
import static net.osmand.util.OffRouteRecalculationGate.*;

public class OffRouteRecalculationGateTest {
	@Test public void unattendedAndDeclinedDeviationNeverRecalculates() {
		OffRouteRecalculationGate gate = new OffRouteRecalculationGate();
		Object route = new Object();
		assertFalse(gate.allow(route, ASK, true, true));
		long token = gate.pendingToken();
		for (int i = 0; i < 100; i++) assertFalse(gate.allow(route, ASK, true, true));
		assertEquals(token, gate.pendingToken());
		assertTrue(gate.answer(token, false));
		assertFalse(gate.allow(route, ASK, true, true));
		assertEquals(0, gate.pendingToken());
		assertFalse(gate.answer(token, true));
	}

	@Test public void confirmationOnlyAppliesToCurrentRouteAndEpisode() {
		OffRouteRecalculationGate gate = new OffRouteRecalculationGate();
		Object route = new Object();
		gate.allow(route, ASK, true, true);
		long old = gate.pendingToken();
		gate.allow(route, ASK, false, false);
		assertFalse(gate.answer(old, true));
		gate.allow(route, ASK, true, true);
		assertNotEquals(old, gate.pendingToken());
		assertTrue(gate.answer(gate.pendingToken(), true));
		assertTrue(gate.allow(route, ASK, true, true));
		assertFalse(gate.allow(new Object(), ASK, true, true));
	}

	@Test public void modesAndNavigationResetInvalidatePendingAnswers() {
		OffRouteRecalculationGate gate = new OffRouteRecalculationGate();
		Object route = new Object();
		gate.allow(route, ASK, true, true);
		long old = gate.pendingToken();
		assertFalse(gate.allow(route, DISABLED, true, true));
		assertFalse(gate.answer(old, true));
		assertTrue(gate.allow(route, AUTOMATIC, true, true));
		assertEquals(0, gate.pendingToken());
		gate.allow(route, ASK, true, true);
		old = gate.pendingToken();
		gate.reset();
		assertFalse(gate.answer(old, true));
	}
}
