package net.osmand.plus.plugins.publictracks

import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal data class PublicTrackSegment(val id: Long, val trackId: String, val coordinates: DoubleArray, val length: Double) {
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
    fun query(bounds: DoubleArray? = null, track: String? = null): List<PublicTrackSegment> {
        if (!file.exists()) return emptyList()
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            require(db.version == 1) { "Unsupported public track format" }
            val sql: String
            val args: Array<String>
            if (track != null) {
                sql = "SELECT id,track_id,geometry,length_m FROM segments WHERE track_id=? ORDER BY id"
                args = arrayOf(track)
            } else {
                require(bounds != null)
                // A covering B-tree also works on Android builds without the optional R-tree module.
                sql = "SELECT s.id,s.track_id,s.geometry,s.length_m FROM segment_bounds b INDEXED BY segment_bounds_cover CROSS JOIN segments s ON s.id=b.id WHERE b.min_x<=? AND b.max_x>=? AND b.min_y<=? AND b.max_y>=? LIMIT 2001"
                args = arrayOf(bounds[2], bounds[0], bounds[3], bounds[1]).map { it.toString() }.toTypedArray()
            }
            val result = ArrayList<PublicTrackSegment>()
            db.rawQuery(sql, args).use { cursor ->
                while (cursor.moveToNext()) {
                    val bytes = cursor.getBlob(2)
                    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                    val count = buffer.int
                    require(count >= 2 && count <= 1_000_000 && bytes.size.toLong() == 4L + count * 16L)
                    val points = DoubleArray(count * 2) { buffer.double }
                    require(points.all { it.isFinite() })
                    result.add(PublicTrackSegment(cursor.getLong(0), cursor.getString(1), points, cursor.getDouble(3)))
                }
            }
            return result
        }
    }
}
