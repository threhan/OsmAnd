package net.osmand.plus.plugins.mountainview

import android.content.Intent
import android.net.Uri
import net.osmand.plus.OsmandApplication
import net.osmand.plus.R
import net.osmand.plus.activities.MapActivity
import net.osmand.plus.plugins.OsmandPlugin
import net.osmand.plus.widgets.ctxmenu.ContextMenuAdapter
import net.osmand.plus.widgets.ctxmenu.data.ContextMenuItem

class MountainViewPlugin(app: OsmandApplication) : OsmandPlugin(app) {
    override fun getId() = "osmand.mountainview"
    override fun getName(): String = app.getString(R.string.mountain_view_name)
    override fun getDescription(linksEnabled: Boolean): CharSequence = app.getString(R.string.mountain_view_description)
    override fun isEnableByDefault() = true
    override fun getLogoResourceId() = R.drawable.ic_action_altitude

    override fun registerMapContextMenuActions(activity: MapActivity, latitude: Double, longitude: Double,
        adapter: ContextMenuAdapter, selectedObj: Any?, configureMenu: Boolean) {
        // Keep the four standard actions, including the overflow menu, in the first row.
        if (isActive) adapter.addItem(item(activity, latitude, longitude, "map_context_menu.mountain_view").setOrder(50))
    }

    override fun registerOptionsMenuItems(activity: MapActivity, helper: ContextMenuAdapter) {
        if (isActive) {
            val map = activity.mapView
            helper.addItem(item(activity, map.latitude, map.longitude, "drawer.mountain_view").setOrder(19))
        }
    }

    private fun item(activity: MapActivity, latitude: Double, longitude: Double, id: String) =
        ContextMenuItem(id).setTitleId(R.string.mountain_view_name, activity)
            .setIcon(R.drawable.ic_action_altitude)
            .setListener { _, _, _, _ ->
                if (latitude.isFinite() && longitude.isFinite() && latitude in -85.0..85.0 && longitude in -180.0..180.0) {
                    activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$latitude,$longitude"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .setClassName(activity, "com.peaknav.views.AndroidLauncher"))
                }
                true
            }
}
