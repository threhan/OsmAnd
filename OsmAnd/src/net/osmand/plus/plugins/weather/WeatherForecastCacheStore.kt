package net.osmand.plus.plugins.weather

import android.database.sqlite.SQLiteDatabase
import android.system.Os
import net.osmand.IndexConstants
import net.osmand.plus.OsmandApplication
import java.io.File

/** Source-aware import/expiry for the native TIFF cache. Call on WeatherHelper's cache executor. */
object WeatherForecastCacheStore {
    private val models = listOf("gfs", "ecmwf")
    private const val VERSION = "source-aware-v1"
    private fun signature(file: File) = "$VERSION:${file.length()}:${file.lastModified()}"
    private fun prefs(app: OsmandApplication) = app.getSharedPreferences("weather_cache_imports", 0)
    private fun packages(app: OsmandApplication) = app.getAppPath(IndexConstants.WEATHER_FORECAST_DIR)
        .listFiles()?.filter { it.isFile && it.name.endsWith(".tifsqlite") }.orEmpty()

    @JvmStatic fun needsImport(app: OsmandApplication): Boolean = packages(app).any { source ->
        val preferences = prefs(app)
        val path = source.absolutePath
        if (preferences.getString(path, null) != signature(source)) return@any true
        try {
            val required = preferences.getString("$path:models", null)?.split(',') ?: packageModels(source)
            val directory = File(app.cacheDir, IndexConstants.WEATHER_FORECAST_DIR)
            val identity = cacheIdentity(directory, required) ?: return@any true
            val importedIdentity = preferences.getString("$path:cache", null)
            if (importedIdentity != null) return@any importedIdentity != identity
            // Upgrade existing completion records without reimporting multi-GB packages.
            // Empty/missing native databases are not evidence of a completed import.
            val populated = required.all { model ->
                SQLiteDatabase.openDatabase(File(directory, "${model}_weather_tiffs.db").path,
                    null, SQLiteDatabase.OPEN_READONLY).use { db ->
                    db.rawQuery("SELECT 1 FROM tiles LIMIT 1", null).use { it.moveToFirst() }
                }
            }
            if (!populated) return@any true
            !preferences.edit().putString("$path:models", required.joinToString(","))
                .putString("$path:cache", identity).commit()
        } catch (_: Exception) { true }
    }

    private fun packageModels(source: File): List<String> = SQLiteDatabase.openDatabase(
        source.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
        db.rawQuery("PRAGMA table_info(tiles)", null).use { cursor ->
            var sourceColumn = false
            while (cursor.moveToNext()) if (cursor.getString(1) == "source") sourceColumn = true
            if (sourceColumn) models else listOf("gfs")
        }
    }

    /** Identity survives normal SQLite writes/pruning, but changes when a cache is replaced. */
    @JvmStatic fun cacheIdentity(directory: File, required: List<String>): String? {
        if (required.isEmpty() || required.any { it !in models }) return null
        return try {
            val identities = ArrayList<String>()
            for (model in required) {
                val file = File(directory, "${model}_weather_tiffs.db")
                if (!file.isFile || file.length() == 0L) return null
                val stat = Os.stat(file.path)
                identities.add("$model:${stat.st_dev}:${stat.st_ino}")
            }
            identities.joinToString(";")
        } catch (_: Exception) { null }
    }

    @JvmStatic fun importPackage(app: OsmandApplication, path: String) {
        val file = File(path)
        val stamp = signature(file)
        importDatabase(File(app.cacheDir, IndexConstants.WEATHER_FORECAST_DIR), file)
        check(stamp == signature(file)) { "Weather package changed during import" }
        val required = packageModels(file)
        val identity = checkNotNull(cacheIdentity(File(app.cacheDir, IndexConstants.WEATHER_FORECAST_DIR), required))
        check(prefs(app).edit().putString(file.absolutePath, stamp)
            .putString("${file.absolutePath}:models", required.joinToString(","))
            .putString("${file.absolutePath}:cache", identity).commit())
    }

    @JvmStatic @Synchronized fun importDatabase(directory: File, source: File) {
        require(source.isFile) { "Missing weather package" }
        val columns = SQLiteDatabase.openDatabase(source.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("PRAGMA table_info(tiles)", null).use { cursor ->
                buildSet { while (cursor.moveToNext()) add(cursor.getString(1)) }
            }
        }
        require(columns.containsAll(listOf("x", "y", "z", "image", "time", "forecastdate"))) { "Unsupported weather package schema" }
        check(directory.isDirectory || directory.mkdirs())
        // Android's exclusive transaction also reserves attached inputs. A package
        // copied by adb may be readable but owned by shell, so stage it privately.
        val staged = if (source.canWrite()) null else File.createTempFile("weather-import-", ".sqlite", directory)
        try {
            val input = staged?.also { source.copyTo(it, overwrite = true) } ?: source
            // Legacy packages without a source column predate ECMWF and contain GFS only.
            val importedModels = if ("source" in columns) models else listOf("gfs")
            for (model in importedModels) {
                val target = File(directory, "${model}_weather_tiffs.db")
                require(target.canonicalFile != source.canonicalFile)
                SQLiteDatabase.openOrCreateDatabase(target, null).use { db ->
                    db.rawQuery("PRAGMA busy_timeout=15000", null).use { it.moveToFirst() }
                    db.execSQL("ATTACH DATABASE ? AS incoming", arrayOf(input.absolutePath))
                    try {
                        db.beginTransaction()
                        try {
                            db.execSQL("CREATE TABLE IF NOT EXISTS tiles (x INTEGER NOT NULL,y INTEGER NOT NULL,z INTEGER NOT NULL,s INTEGER NOT NULL,image BLOB,time INTEGER,timestamp INTEGER,PRIMARY KEY(x,y,z,s))")
                            db.execSQL("CREATE INDEX IF NOT EXISTS IND ON tiles(x,y,z,s)")
                            db.execSQL("CREATE TABLE IF NOT EXISTS info (tilenumbering TEXT,minzoom TEXT,maxzoom TEXT,timestamp TEXT,specificated TEXT,timecolumn TEXT)")
                            db.execSQL("INSERT INTO info (tilenumbering,minzoom,maxzoom,timestamp,specificated,timecolumn) SELECT '', '4','4','yes','yes','yes' WHERE NOT EXISTS (SELECT 1 FROM info)")
                            val filter = if ("source" in columns) "i.source = ? AND " else ""
                            val args: Array<Any> = if ("source" in columns) arrayOf(model) else emptyArray()
                            db.execSQL("INSERT OR REPLACE INTO main.tiles(x,y,z,s,image,time) " +
                                "SELECT i.x,i.y,i.z,i.forecastdate,i.image,i.time FROM incoming.tiles i " +
                                "LEFT JOIN main.tiles t ON t.x=i.x AND t.y=i.y AND t.z=i.z AND t.s=i.forecastdate " +
                                "WHERE $filter(t.time IS NULL OR i.time > t.time)", args)
                            db.setTransactionSuccessful()
                        } finally { db.endTransaction() }
                    } finally { db.execSQL("DETACH DATABASE incoming") }
                }
            }
        } finally { staged?.delete() }
    }

    /** Never unlink a database: an empty selected model must not erase other models. */
    @JvmStatic @Synchronized fun prune(directory: File, before: Long) {
        directory.listFiles()?.filter { file ->
            file.name.matches(Regex("(gfs|ecmwf)_weather_(tiffs|cache_[0-9]+)\\.db"))
        }?.forEach { file ->
            SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.rawQuery("PRAGMA busy_timeout=15000", null).use { it.moveToFirst() }
                db.delete("tiles", "s < ?", arrayOf(before.toString()))
            }
        }
    }
}
