package com.peaknav.utils;
import com.badlogic.gdx.files.FileHandle;
import java.io.File;
/** Persistent downloaded terrain, separate from temporary imagery and the host map files. */
public final class MountainStorage {
    private static volatile File root;
    private MountainStorage() { }
    public static void initialize(File directory) {
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IllegalStateException("Cannot create mountain storage: " + directory);
        }
        root = directory;
    }
    public static FileHandle external(String path) {
        File directory = root;
        if (directory == null) throw new IllegalStateException("Mountain storage is not initialized");
        try {
            File file = new File(directory, path).getCanonicalFile();
            File base = directory.getCanonicalFile();
            if (!file.equals(base) && !file.getPath().startsWith(base.getPath() + File.separator)) {
                throw new IllegalArgumentException("Path outside mountain storage");
            }
            return new FileHandle(file);
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("Invalid mountain storage path", e);
        }
    }
}
