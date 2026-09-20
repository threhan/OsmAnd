package net.osmand.plus.plugins.publictracks

import android.content.Context
import net.osmand.plus.OsmandApplication
import net.osmand.plus.R
import net.osmand.plus.activities.MapActivity
import net.osmand.plus.plugins.OsmandPlugin

class PublicTracksPlugin(app: OsmandApplication) : OsmandPlugin(app) {
    private var layer: PublicTracksLayer? = null
    override fun getId() = "osmand.publictracks"
    override fun getName(): String = app.getString(R.string.public_tracks_name)
    override fun getDescription(linksEnabled: Boolean): CharSequence = app.getString(R.string.public_tracks_description)
    override fun isEnableByDefault() = true
    override fun registerLayers(context: Context, mapActivity: MapActivity?) {
        updateLayers(context, mapActivity)
    }
    override fun updateLayers(context: Context, mapActivity: MapActivity?) {
        val map = app.osmandMap.mapView
        if (isActive) {
            val current = layer?.takeUnless { it.isClosed } ?: PublicTracksLayer(context).also { layer = it }
            if (mapActivity != null) current.setMapActivity(mapActivity)
            if (!map.layers.contains(current)) map.addLayer(current, 8.1f)
        } else {
            layer?.let { map.removeLayer(it) }
            layer = null
        }
    }
}
