package net.osmand.plus.plugins.weather;

import android.app.Instrumentation;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import java.io.File;
import java.lang.reflect.Method;

/** Standalone device regression runner; uses the installed app's actual Kotlin importer. */
public class WeatherCacheImportRunner extends Instrumentation {
    private Method importer, prune;
    private File root, directory;
    private int checks;

    private void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        checks++;
    }

    private SQLiteDatabase cache(String model) {
        return SQLiteDatabase.openDatabase(new File(directory, model + "_weather_tiffs.db").getPath(),
                null, SQLiteDatabase.OPEN_READONLY);
    }

    private long count(String model) {
        try (SQLiteDatabase db = cache(model); Cursor c = db.rawQuery("SELECT count(*) FROM tiles", null)) {
            c.moveToFirst(); return c.getLong(0);
        }
    }

    private long time(String model) {
        try (SQLiteDatabase db = cache(model); Cursor c = db.rawQuery("SELECT time FROM tiles", null)) {
            c.moveToFirst(); return c.getLong(0);
        }
    }

    private String image(String model) {
        try (SQLiteDatabase db = cache(model); Cursor c = db.rawQuery("SELECT hex(image) FROM tiles", null)) {
            c.moveToFirst(); return c.getString(0);
        }
    }

    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            Class<?> store = Class.forName("net.osmand.plus.plugins.weather.WeatherForecastCacheStore", true,
                    getTargetContext().getClassLoader());
            importer = store.getMethod("importDatabase", File.class, File.class);
            prune = store.getMethod("prune", File.class, long.class);
            root = new File(getTargetContext().getCacheDir(), "weather-import-test-" + System.nanoTime());
            check(root.mkdirs(), "create scratch directory");
            directory = new File(root, "cache");
            File source = new File(root, "mixed.db");
            try (SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(source, null)) {
                db.execSQL("CREATE TABLE tiles(x INTEGER,y INTEGER,z INTEGER,forecastdate INTEGER,image BLOB,time INTEGER,source TEXT)");
                db.execSQL("INSERT INTO tiles VALUES(12,9,4,1000,X'11',200,'gfs')");
                db.execSQL("INSERT INTO tiles VALUES(12,9,4,1000,X'22',200,'ecmwf')");
            }
            importer.invoke(null, directory, source);
            check(count("gfs") == 1 && count("ecmwf") == 1, "mixed package imported into both caches");
            Method identity = store.getMethod("cacheIdentity", File.class, java.util.List.class);
            java.util.List<String> models = java.util.Arrays.asList("gfs", "ecmwf");
            Object originalIdentity = identity.invoke(null, directory, models);
            check(originalIdentity != null, "imported model cache identity exists");
            check(image("gfs").equals("11") && image("ecmwf").equals("22"), "same tile/time separated by model");
            importer.invoke(null, directory, source);
            check(count("gfs") == 1 && count("ecmwf") == 1, "reimport is idempotent");
            check(source.setReadOnly(), "make input package read-only");
            importer.invoke(null, directory, source);
            check(count("gfs") == 1 && count("ecmwf") == 1, "read-only input supports transactional import");
            check(source.setWritable(true), "restore scratch fixture access");
            try (SQLiteDatabase db = SQLiteDatabase.openDatabase(source.getPath(), null, SQLiteDatabase.OPEN_READWRITE)) {
                db.execSQL("UPDATE tiles SET time=100,image=X'33'");
            }
            importer.invoke(null, directory, source);
            check(time("gfs") == 200 && image("gfs").equals("11"), "older import cannot replace newer forecast");
            try (SQLiteDatabase db = SQLiteDatabase.openDatabase(source.getPath(), null, SQLiteDatabase.OPEN_READWRITE)) {
                db.execSQL("UPDATE tiles SET time=300,image=X'44' WHERE source='ecmwf'");
            }
            importer.invoke(null, directory, source);
            check(time("ecmwf") == 300 && image("ecmwf").equals("44"), "newer forecast replaces old model record");
            try (SQLiteDatabase db = SQLiteDatabase.openDatabase(new File(directory,"ecmwf_weather_tiffs.db").getPath(),null,SQLiteDatabase.OPEN_READWRITE)) {
                db.execSQL("UPDATE tiles SET s=3000");
            }
            prune.invoke(null, directory, 2000L);
            check(count("gfs") == 0 && count("ecmwf") == 1, "empty GFS does not delete future ECMWF");
            check(new File(directory,"gfs_weather_tiffs.db").exists(), "expiry keeps database files openable");
            check(originalIdentity.equals(identity.invoke(null, directory, models)), "normal writes and expiry preserve import identity");
            File legacy = new File(root,"legacy.db");
            try (SQLiteDatabase db=SQLiteDatabase.openOrCreateDatabase(legacy,null)) {
                db.execSQL("CREATE TABLE tiles(x INTEGER,y INTEGER,z INTEGER,forecastdate INTEGER,image BLOB,time INTEGER)");
                db.execSQL("INSERT INTO tiles VALUES(12,9,4,4000,X'55',400)");
            }
            importer.invoke(null,directory,legacy);
            check(count("gfs")==1 && count("ecmwf")==1 && image("ecmwf").equals("44"), "legacy package only affects GFS");
            File invalid = new File(root,"invalid.db");
            try (SQLiteDatabase db=SQLiteDatabase.openOrCreateDatabase(invalid,null)) { db.execSQL("CREATE TABLE tiles(x INTEGER)"); }
            boolean rejected = false;
            try { importer.invoke(null,directory,invalid); } catch (java.lang.reflect.InvocationTargetException expected) { rejected=true; }
            check(rejected && count("gfs")==1 && count("ecmwf")==1, "invalid package leaves existing caches unchanged");
            File oldCache = new File(directory, "ecmwf_weather_tiffs.db");
            File savedCache = new File(directory, "ecmwf.saved");
            check(oldCache.renameTo(savedCache), "simulate removed model cache without losing fixture");
            check(identity.invoke(null, directory, models) == null, "missing cache invalidates import completion");
            try (SQLiteDatabase fresh = SQLiteDatabase.openOrCreateDatabase(oldCache, null)) {
                fresh.execSQL("CREATE TABLE tiles(x INTEGER)");
            }
            check(!originalIdentity.equals(identity.invoke(null, directory, models)), "native recreation of an empty cache invalidates completion");
            check(identity.invoke(null, directory, java.util.Collections.singletonList("gfs")) != null, "legacy GFS identity does not require ECMWF");
            result.putInt("checks",checks); result.putString("result","PASS");
            finish(-1,result);
        } catch (Throwable t) {
            result.putString("error",android.util.Log.getStackTraceString(t)); finish(1,result);
        } finally { if (root != null) deleteScratch(root); }
    }
    private static void deleteScratch(File file) {
        File[] children=file.listFiles(); if(children!=null) for(File child:children) deleteScratch(child);
        file.delete();
    }
}
