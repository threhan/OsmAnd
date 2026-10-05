package net.osmand.plus.plugins.publictracks

import android.database.sqlite.SQLiteDatabase
import android.os.SystemClock
import android.os.CancellationSignal
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal data class PublicTrackSegment(val id: Long, val trackId: String, val coordinates: DoubleArray, val length: Double, val name: String? = null) {
    val west: Double
    val east: Double
    val south: Double
    val north: Double
    init {
        var w=180.0; var e=-180.0; var s=90.0; var n=-90.0
        for (i in coordinates.indices step 2) {
            val x=coordinates[i]; val y=coordinates[i+1]
            require(x in -180.0..180.0 && y in -90.0..90.0)
            w=kotlin.math.min(w,x); e=kotlin.math.max(e,x)
            s=kotlin.math.min(s,y); n=kotlin.math.max(n,y)
        }
        west=w; east=e; south=s; north=n
    }
}

internal class PublicTracksStore(private val file: File) {
    fun query(bounds: DoubleArray? = null, track: String? = null, limit: Int? = 26): List<PublicTrackSegment> {
        val result = ArrayList<PublicTrackSegment>()
        scan(bounds,track,limit) { result.add(it) }
        return result
    }

    @JvmOverloads
    fun display(bounds: DoubleArray, width: Int, height: Int, progress: ((PublicTracksDisplay) -> Unit)? = null): PublicTracksDisplay {
        val result = PublicTracksDisplay(bounds,width,height)
        var nextUpdate = SystemClock.elapsedRealtime() + 1000
        scan(bounds,null,null) {
            result.add(it)
            if (progress != null && SystemClock.elapsedRealtime() >= nextUpdate) {
                // The consumer copies this snapshot before scanning resumes.
                progress(result)
                nextUpdate = SystemClock.elapsedRealtime() + 5000
            }
        }
        return result
    }

    private fun scan(bounds: DoubleArray?, track: String?, limit: Int?, accept: (PublicTrackSegment) -> Unit) {
        scanCancellable(bounds, track, limit, null, null, accept)
    }

    /** Publish the visible area before reading the surrounding prefetch margin. */
    fun displayViewport(bounds: DoubleArray, viewport: DoubleArray, width: Int, height: Int,
                        cancellation: CancellationSignal, progress: (PublicTracksDisplay, Boolean) -> Unit): PublicTracksDisplay {
        val result = PublicTracksDisplay(bounds, width, height)
        var nextUpdate = SystemClock.elapsedRealtime() + 1000
        fun accept(segment: PublicTrackSegment) {
            result.add(segment)
            if (SystemClock.elapsedRealtime() >= nextUpdate) {
                progress(result, false)
                nextUpdate = SystemClock.elapsedRealtime() + 5000
            }
        }
        scanCancellable(viewport, null, null, cancellation, null, ::accept)
        cancellation.throwIfCanceled()
        progress(result, true)
        // Exclude visible candidates in SQL before sorting/materializing margin IDs.
        scanCancellable(bounds, null, null, cancellation, viewport, ::accept)
        return result
    }

    private fun scanCancellable(bounds: DoubleArray?, track: String?, limit: Int?,
                                cancellation: CancellationSignal?, excludeBounds: DoubleArray?, accept: (PublicTrackSegment) -> Unit) {
        checkInterrupted()
        cancellation?.throwIfCanceled()
        require(limit == null || limit > 0)
        if (!file.exists()) return
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            require(db.version == 1) { "Unsupported public track format" }
            val hasNames = db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='track_metadata'", null).use { it.moveToFirst() }
            val nameColumn = if (hasNames) "m.name" else "NULL"
            val metadataJoin = if (hasNames) " LEFT JOIN track_metadata m ON m.track_id=s.track_id" else ""
            val idSql: String
            val idArgs: Array<String>
            if (track != null) {
                idSql = "SELECT id FROM segments WHERE track_id=? ORDER BY id"
                idArgs = arrayOf(track)
            } else {
                require(bounds != null)
                // A covering B-tree also works on Android builds without the optional R-tree module.
                val exclusion = if (excludeBounds == null) "" else " AND NOT (b.min_x<=? AND b.max_x>=? AND b.min_y<=? AND b.max_y>=?)"
                idSql = "SELECT b.id FROM segment_bounds b INDEXED BY segment_bounds_cover WHERE b.min_x<=? AND b.max_x>=? AND b.min_y<=? AND b.max_y>=?" + exclusion + " ORDER BY b.id" + (limit?.let { " LIMIT $it" } ?: "")
                val arguments = arrayListOf(bounds[2], bounds[0], bounds[3], bounds[1])
                if (excludeBounds != null) arguments.addAll(listOf(excludeBounds[2], excludeBounds[0], excludeBounds[3], excludeBounds[1]))
                idArgs = arguments.map { it.toString() }.toTypedArray()
            }
            // Keep geometry out of the viewport sort and CursorWindow refills. Materialize
            // only IDs, then read bounded batches by primary key in the same stable order.
            val ids = ArrayList<Long>()
            db.rawQuery(idSql, idArgs, cancellation).use { cursor ->
                while (cursor.moveToNext()) {
                    checkInterrupted()
                    cancellation?.throwIfCanceled()
                    ids.add(cursor.getLong(0))
                }
            }
            for (batch in ids.asSequence().chunked(64)) {
                checkInterrupted()
                cancellation?.throwIfCanceled()
                val placeholders = batch.joinToString(",") { "?" }
                val sql = "SELECT s.id,s.track_id,s.geometry,s.length_m,$nameColumn FROM segments s$metadataJoin WHERE s.id IN ($placeholders) ORDER BY s.id"
                db.rawQuery(sql, batch.map { it.toString() }.toTypedArray(), cancellation).use { cursor ->
                    while (cursor.moveToNext()) {
                        checkInterrupted()
                        cancellation?.throwIfCanceled()
                        val bytes = cursor.getBlob(2)
                        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                        val count = buffer.int
                        require(count >= 2 && count <= 1_000_000 && bytes.size.toLong() == 4L + count * 16L)
                        val points = DoubleArray(count * 2) { buffer.double }
                        require(points.all { it.isFinite() })
                        accept(PublicTrackSegment(cursor.getLong(0), cursor.getString(1), points, cursor.getDouble(3), cursor.getString(4)?.trim()?.takeIf { it.isNotEmpty() }))
                        checkInterrupted()
                    }
                }
            }
        }
    }

    private fun checkInterrupted() {
        // Do not publish a partial query as success after the layer shuts down.
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Public track query cancelled")
    }
}
