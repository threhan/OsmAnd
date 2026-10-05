package net.osmand.plus.routing

import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.osmand.Location
import net.osmand.data.LatLon
import net.osmand.plus.OsmandApplication
import net.osmand.plus.activities.MapActivity
import net.osmand.plus.settings.backend.ApplicationMode
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OffRouteConfirmationTest {
    private fun set(target: Any, name: String, value: Any) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }

    private fun dialog(helper: RoutingHelper): AlertDialog? {
        helper.offRouteRecalculationUi.refresh()
        return OffRouteRecalculationUi::class.java.getDeclaredField("dialog").apply { isAccessible = true }
            .get(helper.offRouteRecalculationUi) as AlertDialog?
    }

    @Test fun deviationKeepsGeometryAndRejectsStaleDialog() {
        ActivityScenario.launch(MapActivity::class.java).use { scenario ->
            lateinit var app: OsmandApplication
            lateinit var helper: RoutingHelper
            lateinit var original: RouteCalculationResult
            val mode = ApplicationMode.PEDESTRIAN
            var oldPolicy = 0
            var oldDisabled = false
            var first = 0L
            var second = 0L
            val off = Location("test", 40.003, 116.41).apply { accuracy = 5f }
            val on = Location("test", 40.003, 116.4).apply { accuracy = 5f }
            var initialized = false
            try {
                scenario.onActivity { activity ->
                    app = activity.application as OsmandApplication
                    check(!app.routingHelper.isFollowingMode) { "Do not interrupt active user navigation" }
                    oldPolicy = app.settings.OFF_ROUTE_RECALCULATION.getModeValue(mode)
                    oldDisabled = app.settings.DISABLE_OFFROUTE_RECALC.get()
                    helper = RoutingHelper(app)
                    initialized = true
                    app.settings.OFF_ROUTE_RECALCULATION.setModeValue(mode, 1)
                    app.settings.DISABLE_OFFROUTE_RECALC.set(false)
                    helper.appMode = mode
                    val params = RouteCalculationParams().apply { ctx = app; this.mode = mode }
                    val points = (0..10).map { Location("test", 40.0 + it * .001, 116.4) }
                    original = RouteCalculationResult(points, emptyList(), params, null, false)
                    helper.setRoute(original)
                    set(helper, "finalLocation", LatLon(40.01, 116.4))
                    set(helper, "intermediatePoints", emptyList<LatLon>())
                    set(helper, "isFollowingMode", true)
                    helper.offRouteRecalculationUi.attach(activity)
                    helper.setCurrentLocation(off, false)
                    first = helper.pendingOffRouteRecalculation
                    assertTrue(first > 0)
                    assertSame(original, helper.route)
                    assertFalse(helper.isRouteBeingCalculated)
                    assertTrue(dialog(helper)?.isShowing == true)
                    repeat(10) { helper.setCurrentLocation(off, false) }
                    assertEquals(first, helper.pendingOffRouteRecalculation)
                    helper.offRouteRecalculationUi.detach(activity)
                    assertNull(dialog(helper))
                    helper.offRouteRecalculationUi.attach(activity)
                    assertTrue(dialog(helper)?.isShowing == true)
                    dialog(helper)!!.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
                }
                scenario.onActivity {
                    helper.setCurrentLocation(off, false)
                    assertEquals(0L, helper.pendingOffRouteRecalculation)
                    assertSame(original, helper.route)
                    assertFalse(helper.isRouteBeingCalculated)
                    helper.setCurrentLocation(on, false)
                    helper.setCurrentLocation(off, false)
                    second = helper.pendingOffRouteRecalculation
                    assertTrue(second > first)
                    helper.setCurrentLocation(on, false)
                    helper.answerOffRouteRecalculation(second, true)
                    assertEquals(0L, helper.pendingOffRouteRecalculation)
                    assertNull(dialog(helper))
                    helper.setCurrentLocation(off, false)
                    assertTrue(helper.pendingOffRouteRecalculation > second)
                    dialog(helper)!!.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                }
                scenario.onActivity {
                    assertEquals(0L, helper.pendingOffRouteRecalculation)
                    assertSame(original, helper.route)
                    // The next fix must recheck deviation, including returning before calculation.
                    helper.setCurrentLocation(on, false)
                    assertFalse(helper.isRouteBeingCalculated)
                }
            } finally {
                if (initialized) scenario.onActivity { activity ->
                    helper.offRouteRecalculationUi.detach(activity)
                    app.transportRoutingHelper.setRoutingHelper(app.routingHelper)
                    app.settings.OFF_ROUTE_RECALCULATION.setModeValue(mode, oldPolicy)
                    app.settings.DISABLE_OFFROUTE_RECALC.set(oldDisabled)
                }
            }
        }
    }
}
