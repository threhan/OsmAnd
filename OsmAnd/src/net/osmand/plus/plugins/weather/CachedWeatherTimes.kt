package net.osmand.plus.plugins.weather

import android.database.sqlite.SQLiteDatabase
import net.osmand.PlatformUtil
import java.io.File

/** Immutable per-tile schedules, published by WeatherHelper's cache executor. */
class CachedWeatherTimes {
    @Volatile private var schedules: Map<String, Map<Int, LongArray>> = emptyMap()

    fun reload(directory: File) {
        val result = mutableMapOf<String, Map<Int, LongArray>>()
        for (model in listOf("gfs", "ecmwf")) {
            val file = File(directory, "${model}_weather_tiffs.db")
            if (!file.isFile) continue
            try {
                val tiles = mutableMapOf<Int, MutableList<Long>>()
                SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                    db.rawQuery("SELECT x,y,s FROM tiles WHERE z=4 ORDER BY x,y,s", null).use { cursor ->
                        while (cursor.moveToNext()) {
                            val key = cursor.getInt(0) * 16 + cursor.getInt(1)
                            tiles.getOrPut(key) { mutableListOf() }.add(cursor.getLong(2))
                        }
                    }
                }
                result[model] = tiles.mapValues { it.value.toLongArray() }
            } catch (e: Exception) {
                PlatformUtil.getLog(CachedWeatherTimes::class.java).warn("Cannot read cached weather times for $model", e)
            }
        }
        schedules = result
    }

    fun resolve(model: String, x31: Int, y31: Int, requested: Long): Long {
        val times = schedules[model]?.get((x31 ushr 27) * 16 + (y31 ushr 27)) ?: return requested
        // Zero means known coverage has no valid sample; do not let the native
        // model-specific rounding silently extend the last forecast interval.
        return previousSample(times, requested) ?: 0L
    }

    companion object {
        /** Only bridge bounded model intervals; never extend expired coverage or arbitrary holes. */
        @JvmStatic fun previousSample(times: LongArray, requested: Long): Long? {
            val index = times.binarySearch(requested)
            if (index >= 0) return times[index]
            val next = -index - 1
            if (next == 0 || next == times.size) return null
            val previous = times[next - 1]
            return previous.takeIf { times[next] - previous <= 6 * 3600000L }
        }
    }
}
