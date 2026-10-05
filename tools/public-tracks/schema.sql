PRAGMA user_version = 1;
PRAGMA foreign_keys = ON;
CREATE TABLE track_metadata (
    track_id TEXT PRIMARY KEY NOT NULL,
    name TEXT NOT NULL
);
CREATE TABLE segments (
    id INTEGER PRIMARY KEY,
    track_id TEXT NOT NULL REFERENCES track_metadata(track_id),
    geometry BLOB NOT NULL,
    length_m REAL NOT NULL CHECK (length_m >= 0)
);
CREATE INDEX segments_track_id ON segments(track_id, id);
CREATE TABLE segment_bounds (
    id INTEGER PRIMARY KEY REFERENCES segments(id),
    min_x REAL NOT NULL,
    max_x REAL NOT NULL,
    min_y REAL NOT NULL,
    max_y REAL NOT NULL,
    CHECK (min_x <= max_x AND min_y <= max_y)
);
CREATE INDEX segment_bounds_cover ON segment_bounds(min_x, max_x, min_y, max_y, id);
