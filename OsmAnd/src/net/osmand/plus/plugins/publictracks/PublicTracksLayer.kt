package net.osmand.plus.plugins.publictracks

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.os.SystemClock
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
import java.util.concurrent.Future
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min

class PublicTracksLayer(context: Context) : OsmandMapLayer(context) {
    private val log = PlatformUtil.getLog(PublicTracksLayer::class.java)
    private val worker = Executors.newSingleThreadExecutor()
    private val drawingWorker = Executors.newFixedThreadPool(1) as ThreadPoolExecutor
    private val loadLock = Any()
    private var loadGeneration = 0
    private var loadTask: Future<*>? = null
    private var loadCancellation: CancellationSignal? = null
    private var requestedBounds: DoubleArray? = null
    private var requestedZoom = -1
    private var retryAfter = 0L
    private val main = Handler(Looper.getMainLooper())
    private val store by lazy { PublicTracksStore(application.getAppPath("public-tracks/public-tracks.sqlite")) }
    @Volatile private var visible: List<PublicTrackSegment> = emptyList()
    @Volatile private var selected: List<PublicTrackSegment> = emptyList()
    @Volatile private var pending = false
    @Volatile private var closed = false
    internal val isClosed: Boolean get() = closed
    private val revision = AtomicInteger()
    @Volatile private var loadedBounds: DoubleArray? = null
    @Volatile private var crowded = false
    @Volatile private var loadedZoom = -1
    @Volatile private var loadedViewport: DoubleArray? = null
    @Volatile private var tapGeneration = 0
    private var renderedRevision = -1
    private var provider: VectorLinesCollection? = null
    private var selectionProvider: VectorLinesCollection? = null
    private var renderedBackground: List<PublicTrackSegment> = emptyList()
    private var renderedSelection: List<PublicTrackSegment> = emptyList()
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
        synchronized(loadLock) {
            val previous = loadedBounds
            fun contains(area: DoubleArray?) = area != null && current[0] >= area[0] && current[1] >= area[1] && current[2] <= area[2] && current[3] <= area[3]
            if (pending && (tileBox.zoom < 11 || tileBox.zoom != requestedZoom || !contains(requestedBounds))) {
                cancelDrawing()
            }
            if (!closed && tileBox.zoom >= 11 && (tileBox.zoom != loadedZoom || !contains(previous)) &&
                !(pending && tileBox.zoom == requestedZoom && contains(requestedBounds)) && SystemClock.elapsedRealtime() >= retryAfter) {
                cancelDrawing()
                val dx = (current[2] - current[0]) * 0.2
                val dy = (current[3] - current[1]) * 0.2
                val expanded = doubleArrayOf(current[0]-dx, current[1]-dy, current[2]+dx, current[3]+dy)
                val zoom = tileBox.zoom
                requestedZoom = zoom
                requestedBounds = expanded
                val width = (tileBox.pixWidth * 1.4).toInt()
                val height = (tileBox.pixHeight * 1.4).toInt()
                val token = loadGeneration
                val cancellation = CancellationSignal()
                loadCancellation = cancellation
                pending = true
                loadTask = drawingWorker.submit {
                    fun publish(drawing: PublicTracksDisplay, coverage: DoubleArray?, complete: Boolean) {
                        val snapshot = ArrayList(drawing.segments)
                        val thinned = drawing.thinned
                        main.post {
                            synchronized(loadLock) {
                                if (closed || token != loadGeneration) return@post
                                visible = snapshot
                                crowded = thinned
                                if (coverage != null) {
                                    loadedBounds = coverage
                                    loadedViewport = current
                                    loadedZoom = zoom
                                }
                                if (complete) pending = false
                                revision.incrementAndGet()
                            }
                            view.refreshMap()
                        }
                    }
                    try {
                        val drawing = store.displayViewport(expanded, current, width, height, cancellation) { partial, viewportReady ->
                            publish(partial, if (viewportReady) current else null, false)
                        }
                        cancellation.throwIfCanceled()
                        publish(drawing, expanded, true)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                    } catch (_: OperationCanceledException) {
                        // A newer viewport owns publication now.
                    } catch (e: Exception) {
                        log.error("Public track viewport query failed", e)
                        main.post {
                            synchronized(loadLock) {
                                if (closed || token != loadGeneration) return@post
                                retryAfter = SystemClock.elapsedRealtime() + 2000
                                application.showToastMessage(R.string.public_tracks_error)
                            }
                            main.postDelayed({
                                synchronized(loadLock) {
                                    if (!closed && token == loadGeneration) view.refreshMap()
                                }
                            }, 2000)
                        }
                    } finally {
                        main.post {
                            synchronized(loadLock) {
                                if (closed || token != loadGeneration) return@post
                                pending = false
                            }
                            view.refreshMap()
                        }
                    }
                }
            }
        }
        val renderer = mapRenderer
        val version = revision.get() * 2 + if (tileBox.zoom >= 11) 1 else 0
        if (renderer != owner || renderedRevision != version) {
            val background = visible
            val highlight = selected
            val enabled = renderer != null && tileBox.zoom >= 11
            val appendOnly = renderer == owner && enabled && provider != null &&
                background.size >= renderedBackground.size &&
                renderedBackground.indices.all { background[it] === renderedBackground[it] }
            // Progressive snapshots share immutable segments. Reuse their native prefix.
            if (!appendOnly) {
                provider?.let { owner?.removeSymbolsProvider(it) }
                provider = null
                renderedBackground = emptyList()
            }
            if (renderer != owner || !enabled || highlight !== renderedSelection) {
                selectionProvider?.let { owner?.removeSymbolsProvider(it) }
                selectionProvider = null
                renderedSelection = emptyList()
            }
            owner = renderer
            if (renderer != null && tileBox.zoom >= 11) {
                fun add(collection: VectorLinesCollection, segment: PublicTrackSegment, line: Int, selectedLine: Boolean) {
                    val coordinates = QVectorPointI()
                    val p = segment.coordinates
                    for (i in p.indices step 2) coordinates.add(PointI(MapUtils.get31TileNumberX(p[i]), MapUtils.get31TileNumberY(p[i+1])))
                    VectorLineBuilder().setPoints(coordinates).setLineId(line)
                        .setIsHidden(false).setLineWidth((if (selectedLine) 3.0 else 1.0) * GeometryWayDrawer.getVectorLineScale(context))
                        .setFillColor(NativeUtilities.createFColorARGB(if (selectedLine) selectedColor else normalColor))
                        .setApproximationEnabled(false).setBaseOrder(baseOrder - if (selectedLine) 1 else 0)
                        .buildAndAddToCollection(collection)
                }
                val collection = provider ?: VectorLinesCollection()
                for (i in renderedBackground.size until background.size) add(collection, background[i], i+1, false)
                if (provider == null) {
                    renderer.addSymbolsProvider(collection)
                    provider = collection
                }
                renderedBackground = background
                if (selectionProvider == null && highlight.isNotEmpty()) {
                    val selection = VectorLinesCollection()
                    highlight.forEachIndexed { i, segment -> add(selection, segment, i+1, true) }
                    renderer.addSymbolsProvider(selection)
                    selectionProvider = selection
                }
                renderedSelection = highlight
            }
            renderedRevision = version
        }
    }

    override fun onDraw(canvas: Canvas, tileBox: RotatedTileBox, settings: DrawSettings) {
        if (tileBox.zoom < 11) {
            if (application.getAppPath("public-tracks/public-tracks.sqlite").exists())
                canvas.drawText(context.getString(R.string.public_tracks_zoom), 16f, tileBox.pixHeight * 0.7f, label)
            return
        }
        if (crowded) {
            canvas.drawText(context.getString(R.string.public_tracks_partial), 16f, tileBox.pixHeight * 0.7f, label)
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
        if (closed || tileBox.zoom < 11) return false
        val radius = 10 * context.resources.displayMetrics.density
        val corners = listOf(-1 to -1, -1 to 1, 1 to -1, 1 to 1).map {
            NativeUtilities.getLatLonFromPixel(mapRenderer, tileBox, point.x+it.first*radius, point.y+it.second*radius)
        }
        val west = corners.minOf { it.longitude }; val east = corners.maxOf { it.longitude }
        val south = corners.minOf { it.latitude }; val north = corners.maxOf { it.latitude }
        if (mapActivity == null) return false
        val token = ++tapGeneration
        val viewport = bounds(tileBox)
        val snapshot = tileBox.copy()
        // Hit testing is independent of the bounded drawing snapshot.
        worker.execute {
            if (closed || token != tapGeneration) return@execute
            try {
                val candidates = store.query(doubleArrayOf(west,south,east,north), limit = null)
                main.post {
                    if (!closed && token == tapGeneration && bounds(view.currentRotatedTileBox).contentEquals(viewport)) {
                        if (!showCandidates(candidates, point, snapshot, radius, west, east, south, north))
                            view.dispatchSingleTapAfter(this, point)
                    }
                }
            } catch (e: Exception) {
                failed(e)
                main.post { if (!closed && token == tapGeneration && bounds(view.currentRotatedTileBox).contentEquals(viewport)) view.dispatchSingleTapAfter(this, point) }
            }
        }
        return true
    }

    private fun showCandidates(candidates: List<PublicTrackSegment>, point: PointF, tileBox: RotatedTileBox,
                               radius: Float, west: Double, east: Double, south: Double, north: Double): Boolean {
        val hits = mutableMapOf<String,Double>()
        for (segment in candidates) {
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
        val ids = hits.entries.sortedWith(compareBy<Map.Entry<String, Double>> { it.value }.thenBy { it.key }).map { it.key }
        val titles = candidates.associate { it.trackId to title(it) }
        AlertDialog.Builder(activity).setTitle(R.string.public_tracks_choose)
            .setItems(ids.map { titles.getValue(it) }.toTypedArray()) { _, which -> select(ids[which]) }
            .setNegativeButton(R.string.shared_string_cancel,null).show()
        return true
    }

    private fun title(segment: PublicTrackSegment): String =
        segment.name ?: context.getString(R.string.public_tracks_title, segment.trackId)

    private fun select(id: String) {
        worker.execute {
            try {
                val segments = store.query(track=id)
                if (segments.isEmpty()) return@execute
                main.post {
                    if (closed) return@post
                    selected=segments; revision.incrementAndGet(); view.refreshMap()
                    val activity=mapActivity ?: return@post
                    AlertDialog.Builder(activity).setTitle(title(segments.first()))
                        .setMessage(context.getString(R.string.public_tracks_details,segments.sumOf { it.length }/1000,segments.size))
                        .setPositiveButton(R.string.public_tracks_navigate) { _, _ -> chooseSegment(segments) }
                        .setNeutralButton(R.string.public_tracks_export) { _, _ -> save(segments,false) }
                        .setNegativeButton(R.string.public_tracks_clear) { _, _ -> selected=emptyList(); revision.incrementAndGet(); view.refreshMap() }.show()
                }
            } catch (e: Exception) { failed(e) }
        }
    }

    private fun chooseSegment(segments: List<PublicTrackSegment>) {
        val activity=mapActivity ?: return
        if (segments.size==1) { save(segments,true); return }
        AlertDialog.Builder(activity).setTitle(R.string.public_tracks_segment)
            .setItems(segments.mapIndexed { i,s -> context.getString(R.string.public_tracks_segment_item,i+1,s.length/1000) }.toTypedArray()) { _,which ->
                selected=listOf(segments[which]); revision.incrementAndGet(); view.refreshMap(); save(selected,true)
            }.setNegativeButton(R.string.shared_string_cancel,null).show()
    }

    private fun save(segments: List<PublicTrackSegment>, navigate: Boolean) {
        if (segments.isEmpty()) return
        worker.execute {
            try {
                val gpx=GpxFile("OsmAnd public track network")
                val track=Track().apply { name=title(segments.first()) }
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
        if (e is InterruptedException) {
            Thread.currentThread().interrupt()
            return
        }
        log.error("Public track operation failed",e)
        main.post { if (!closed) application.showToastMessage(R.string.public_tracks_error) }
    }

    override fun destroyLayer() {
        synchronized(loadLock) {
            closed=true
            cancelDrawing()
            drawingWorker.shutdownNow()
        }
        worker.shutdownNow()
        provider?.let { owner?.removeSymbolsProvider(it) }
        selectionProvider?.let { owner?.removeSymbolsProvider(it) }
        selectionProvider=null; renderedBackground=emptyList(); renderedSelection=emptyList()
        provider=null; owner=null
        super.destroyLayer()
    }

    /** Called under loadLock; stale callbacks cannot change the active request. */
    private fun cancelDrawing() {
        loadGeneration++
        loadCancellation?.cancel()
        loadTask?.cancel(true)
        drawingWorker.purge()
        loadCancellation = null
        loadTask = null
        pending = false
    }
}
