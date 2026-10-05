package net.osmand.util;

/** One decision per deviation episode. The caller owns synchronization. */
public final class OffRouteRecalculationGate {

	public static final int AUTOMATIC = 0;
	public static final int ASK = 1;
	public static final int DISABLED = 2;
	private Object route;
	private int policy = -1;
	private long generation;
	private boolean active;
	private boolean answered;
	private boolean approved;

	public boolean allow(Object currentRoute, int currentPolicy, boolean deviated, boolean requested) {
		if (route != currentRoute || policy != currentPolicy || !deviated) {
			reset();
			route = currentRoute;
			policy = currentPolicy;
		}
		if (!requested) {
			return false;
		}
		if (currentPolicy == AUTOMATIC) {
			return true;
		}
		if (currentPolicy == DISABLED) {
			return false;
		}
		if (!active) {
			active = true;
			generation++;
		}
		return approved;
	}

	public long pendingToken() {
		return active && !answered ? generation : 0;
	}

	public boolean answer(long token, boolean approve) {
		if (token == 0 || token != pendingToken()) {
			return false;
		}
		answered = true;
		approved = approve;
		return true;
	}

	public void reset() {
		generation++;
		active = false;
		answered = false;
		approved = false;
	}
}
