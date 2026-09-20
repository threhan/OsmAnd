package net.osmand.plus.plugins.publictracks

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AlertDialog
import net.osmand.PlatformUtil
import net.osmand.core.android.MapRendererView
import net.osmand.core.jni.PointI
import net.osmand.core.jni.QVectorPointI
import net.osmand.core.jni.VectorLineBuilder
import net.osmand.core.jni.VectorLinesCollection
import net.osmand.data.RotatedTileBox
import net.osmand.plus.R
import net.osmand.plus.base.ContextMenuFragment.MenuState
import net.osmand.plus.settings.backend.ApplicationMode
import net.osmand.plus.utils.NativeUtilities
import net.osmand.plus.views.layers.base.OsmandMapLayer
import net.osmand.plus.views.layers.geometry.GeometryWayDrawer
import net.osmand.shared.gpx.GpxFile
import net.osmand.shared.gpx.GpxUtilities
import net.osmand.shared.gpx.primitives.Track
import net.osmand.shared.gpx.primitives.TrkSegment
import net.osmand.shared.gpx.primitives.WptPt
import net.osmand.shared.io.KFile
import net.osmand.util.MapUtils
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

class PublicTracksLayer(context: Context) : OsmandMapLayer(context) {
    private val log = PlatformUtil.getLog(PublicTracksLayer::class.java)
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val store by lazy { PublicTracksStore(application.getAppPath("public-tracks/public-tracks.sqlite")) }
    @Volatile private var visible: List<PublicTrackSegment> = emptyList()
    @Volatile private var selected: List<PublicTrackSegment> = emptyList()
    @Volatile private var pending = false
    @Volatile private var closed = false
    internal val isClosed: Boolean get() = closed
    @Volatile private var revision = 0
    @Volatile private var loadedBounds: DoubleArray? = null
    @Volatile private var crowded = false
    @Volatile private var loadedZoom = -1
    private var renderedRevision = -1
    private var provider: VectorLinesCollection? = null
    private var owner: MapRendererView? = null
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY; textSize = 14 * context.resources.displayMetrics.density }
    private val normalColor = Color.argb(170, 53, 64, 75)
    private val selectedColor = Color.rgb(239, 108, 0)

    override fun drawInScreenPixels() = true

    private fun bounds(box: RotatedTileBox): DoubleArray {
        val points = listOf(0 to 0, box.pixWidth to 0, 0 to box.pixHeight, box.pixWidth to box.pixHeight).map {
            NativeUtilities.getLatLonFromPixel(mapRenderer, box, it.first.toFloat(), it.second.toFloat())
        }
        return doubleArrayOf(points.minOf { it.longitude }, points.minOf { it.latitude }, points.maxOf { it.longitude }, points.maxOf { it.latitude })
    }

    override fun onPrepareBufferImage(canvas: Canvas, tileBox: RotatedTileBox, settings: DrawSettings) {
        super.onPrepareBufferImage(canvas, tileBox, settings)
        if (closed) return
        val current = bounds(tileBox)
        val previous = loadedBounds
        if (tileBox.zoom >= 11 && !pending && (previous == null || (crowded && tileBox.zoom > loadedZoom) || current[0] < previous[0] || current[1] < previous[1] || current[2] > previous[2] || current[3] > previous[3])) {
            val dx = (current[2] - current[0]) * 0.2
            val dy = (current[3] - current[1]) * 0.2
            val expanded = doubleArrayOf(current[0]-dx, current[1]-dy, current[2]+dx, current[3]+dy)
            val requestedZoom = tileBox.zoom
            pending = true
            worker.execute {
                try {
                    val records = store.query(expanded)
                    if (!closed) {
                        crowded = records.size > 2000
                        visible = if (crowded) emptyList() else records
                        loadedBounds = expanded
                        loadedZoom = requestedZoom
                        revision++
                    }
                } catch (e: Exception) {
                    log.error("Public track viewport query failed", e)
                    loadedBounds = expanded
                    main.post { if (!closed) application.showToastMessage(R.string.public_tracks_error) }
                } finally {
                    pending = false
                    main.post { if (!closed) view.refreshMap() }
                }
            }
        }
        val renderer = mapRenderer
        val version = revision * 2 + if (tileBox.zoom >= 11) 1 else 0
        if (renderer != owner || renderedRevision != version) {
            provider?.let { owner?.removeSymbolsProvider(it) }
            provider = null
            owner = renderer
            if (renderer != null && tileBox.zoom >= 11) {
                val collection = VectorLinesCollection()
                var line = 1
                fun add(segment: PublicTrackSegment, selectedLine: Boolean) {
                    val coordinates = QVectorPointI()
                    val p = segment.coordinates
                    for (i in p.indices step 2) coordinates.add(PointI(MapUtils.get31TileNumberX(p[i]), MapUtils.get31TileNumberY(p[i+1])))
                    VectorLineBuilder().setPoints(coordinates).setLineId(line++)
                        .setIsHidden(false).setLineWidth((if (selectedLine) 3.0 else 1.0) * GeometryWayDrawer.getVectorLineScale(context))
                        .setFillColor(NativeUtilities.createFColorARGB(if (selectedLine) selectedColor else normalColor))
                        .setApproximationEnabled(false).setBaseOrder(baseOrder - if (selectedLine) 1 else 0)
                        .buildAndAddToCollection(collection)
                }
                visible.forEach { add(it, false) }
                selected.forEach { add(it, true) }
                renderer.addSymbolsProvider(collection)
                provider = collection
            }
            renderedRevision = version
        }
    }

    override fun onDraw(canvas: Canvas, tileBox: RotatedTileBox, settings: DrawSettings) {
        if (tileBox.zoom < 11 || crowded) {
            if (application.getAppPath("public-tracks/public-tracks.sqlite").exists())
                canvas.drawText(context.getString(R.string.public_tracks_zoom), 16f, tileBox.pixHeight * 0.7f, label)
            return
        }
        if (mapRenderer != null) return
        fun draw(segment: PublicTrackSegment, highlight: Boolean) {
            val path = Path()
            val points = segment.coordinates
            for (i in points.indices step 2) {
                val x = tileBox.getPixXFromLatLon(points[i+1], points[i])
                val y = tileBox.getPixYFromLatLon(points[i+1], points[i])
                if (i == 0) path.moveTo(x,y) else path.lineTo(x,y)
            }
            stroke.color = if (highlight) selectedColor else normalColor
            stroke.strokeWidth = if (highlight) 3f else 1f
            canvas.drawPath(path,stroke)
        }
        visible.forEach { draw(it,false) }
        selected.forEach { draw(it,true) }
    }

    override fun onSingleTap(point: PointF, tileBox: RotatedTileBox): Boolean {
        if (closed || tileBox.zoom < 11 || crowded) return false
        val radius = 10 * context.resources.displayMetrics.density
        val corners = listOf(-1 to -1, -1 to 1, 1 to -1, 1 to 1).map {
            NativeUtilities.getLatLonFromPixel(mapRenderer, tileBox, point.x+it.first*radius, point.y+it.second*radius)
        }
        val west = corners.minOf { it.longitude }; val east = corners.maxOf { it.longitude }
        val south = corners.minOf { it.latitude }; val north = corners.maxOf { it.latitude }
        val hits = mutableMapOf<String,Double>()
        for (segment in visible) {
            if (segment.west>east || segment.east<west || segment.south>north || segment.north<south) continue
            val coordinates = segment.coordinates
            var best = Double.MAX_VALUE
            for (i in 2 until coordinates.size step 2) {
                if (min(coordinates[i-2],coordinates[i])>east || max(coordinates[i-2],coordinates[i])<west ||
                    min(coordinates[i-1],coordinates[i+1])>north || max(coordinates[i-1],coordinates[i+1])<south) continue
                val a = NativeUtilities.getPixelFromLatLon(mapRenderer,tileBox,coordinates[i-1],coordinates[i-2])
                val b = NativeUtilities.getPixelFromLatLon(mapRenderer,tileBox,coordinates[i+1],coordinates[i])
                val dx = (b.x-a.x).toDouble(); val dy = (b.y-a.y).toDouble()
                val length = dx*dx+dy*dy
                val t = if (length==0.0) 0.0 else (((point.x-a.x)*dx+(point.y-a.y)*dy)/length).coerceIn(0.0,1.0)
                val ex=point.x-a.x-t*dx; val ey=point.y-a.y-t*dy
                best=min(best,ex*ex+ey*ey)
            }
            if (best <= radius*radius) hits[segment.trackId] = min(best,hits[segment.trackId] ?: Double.MAX_VALUE)
        }
        if (hits.isEmpty()) return false
        val activity = mapActivity ?: return false
        val ids = hits.entries.sortedBy { it.value }.map { it.key }
        AlertDialog.Builder(activity).setTitle(R.string.public_tracks_choose)
            .setItems(ids.map { context.getString(R.string.public_tracks_title,it) }.toTypedArray()) { _, which -> select(ids[which]) }
            .setNegativeButton(R.string.shared_string_cancel,null).show()
        return true
    }

    private fun select(id: String) {
        worker.execute {
            try {
                val segments = store.query(track=id)
                main.post {
                    if (closed) return@post
                    selected=segments; revision++; view.refreshMap()
                    val activity=mapActivity ?: return@post
                    AlertDialog.Builder(activity).setTitle(context.getString(R.string.public_tracks_title,id))
                        .setMessage(context.getString(R.string.public_tracks_details,segments.sumOf { it.length }/1000,segments.size))
                        .setPositiveButton(R.string.public_tracks_navigate) { _, _ -> chooseSegment(segments) }
                        .setNeutralButton(R.string.public_tracks_export) { _, _ -> save(segments,false) }
                        .setNegativeButton(R.string.public_tracks_clear) { _, _ -> selected=emptyList(); revision++; view.refreshMap() }.show()
                }
            } catch (e: Exception) { failed(e) }
        }
    }

    private fun chooseSegment(segments: List<PublicTrackSegment>) {
        val activity=mapActivity ?: return
        if (segments.size==1) { save(segments,true); return }
        AlertDialog.Builder(activity).setTitle(R.string.public_tracks_segment)
            .setItems(segments.mapIndexed { i,s -> context.getString(R.string.public_tracks_segment_item,i+1,s.length/1000) }.toTypedArray()) { _,which ->
                selected=listOf(segments[which]); revision++; view.refreshMap(); save(selected,true)
            }.setNegativeButton(R.string.shared_string_cancel,null).show()
    }

    private fun save(segments: List<PublicTrackSegment>, navigate: Boolean) {
        if (segments.isEmpty()) return
        worker.execute {
            try {
                val gpx=GpxFile("OsmAnd public track network")
                val track=Track().apply { name=context.getString(R.string.public_tracks_title,segments.first().trackId) }
                for (source in segments) {
                    val segment=TrkSegment()
                    for (i in source.coordinates.indices step 2) segment.points.add(WptPt().apply {
                        lon=source.coordinates[i]; lat=source.coordinates[i+1]
                    })
                    track.segments.add(segment)
                }
                gpx.tracks.add(track)
                val directory=application.getAppPath("tracks/public-tracks").apply { mkdirs() }
                val file=File(directory,"public-track-${segments.first().trackId}-${System.currentTimeMillis()}.gpx")
                gpx.path=file.absolutePath
                val error=GpxUtilities.writeGpxFile(KFile(file.absolutePath),gpx)
                check(error==null) { error.toString() }
                main.post {
                    if (closed) return@post
                    val activity=mapActivity
                    if (navigate && activity!=null) {
                        val plan = Runnable {
                            activity.mapActions.enterRoutePlanningModeGivenGpx(gpx, ApplicationMode.PEDESTRIAN,
                                null, null, false, true, MenuState.HEADER_ONLY)
                            application.routingHelper.currentGPXRoute?.apply {
                                setCalculateOsmAndRoute(false)
                                setCalculateOsmAndRouteParts(false)
                                setApproximationParams(null)
                                setSelectedSegment(0)
                            }
                            application.routingHelper.onSettingsChanged(true)
                        }
                        if (application.routingHelper.isFollowingMode) activity.mapActions.stopNavigationActionConfirm(null,plan)
                        else plan.run()
                    }
                    else application.showToastMessage(context.getString(R.string.public_tracks_saved,file.name))
                }
            } catch (e: Exception) { failed(e) }
        }
    }

    private fun failed(e: Exception) {
        log.error("Public track operation failed",e)
        main.post { if (!closed) application.showToastMessage(R.string.public_tracks_error) }
    }

    override fun destroyLayer() {
        closed=true
        worker.shutdownNow()
        provider?.let { owner?.removeSymbolsProvider(it) }
        provider=null; owner=null
        super.destroyLayer()
    }
}
