package net.osmand.plus.routing

import androidx.appcompat.app.AlertDialog
import net.osmand.plus.R
import net.osmand.plus.activities.MapActivity
import java.lang.ref.WeakReference

/** Activity and dialogs are accessed only on the main thread. */
class OffRouteRecalculationUi(private val helper: RoutingHelper) {
    private var host = WeakReference<MapActivity>(null)
    private var dialog: AlertDialog? = null
    private var shownToken = 0L

    fun attach(activity: MapActivity) {
        host = WeakReference(activity)
        refresh()
    }

    fun detach(activity: MapActivity) {
        if (host.get() === activity) {
            dismiss()
            host.clear()
        }
    }

    private fun dismiss() {
        dialog?.setOnCancelListener(null)
        dialog?.dismiss()
        dialog = null
        shownToken = 0
    }

    fun refresh() {
        val token = helper.pendingOffRouteRecalculation
        if (shownToken != token) dismiss()
        val activity = host.get() ?: return
        if (token == 0L || dialog != null || activity.isFinishing || activity.isDestroyed) return
        shownToken = token
        dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.offroute_confirm_title)
            .setMessage(R.string.offroute_confirm_message)
            .setPositiveButton(R.string.offroute_recalculate) { _, _ ->
                helper.answerOffRouteRecalculation(token, true)
                dialog = null
            }
            .setNegativeButton(R.string.offroute_keep) { _, _ ->
                helper.answerOffRouteRecalculation(token, false)
                dialog = null
            }
            .setOnCancelListener {
                helper.answerOffRouteRecalculation(token, false)
                dialog = null
            }.create().also { it.show() }
    }
}
