package com.melodie.player.data.entity;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "songs")
public class Song {

    public static final String SOURCE_LOCAL = "LOCAL";
    public static final String SOURCE_DRIVE = "DRIVE";

    @PrimaryKey
    @NonNull
    public String id;

    @NonNull
    public String title;

    @Nullable
    public String artist;

    @Nullable
    public String album;

    public long albumId;

    /** track number within the album (0 = unknown) */
    public int trackNumber;

    /** duration in ms */
    public long duration;

    /** local URI / path or drive file id */
    @NonNull
    public String path;

    /** "LOCAL" or "DRIVE" */
    @NonNull
    public String source;

    @Nullable
    public String cover;

    public boolean favorite;

    public long dateAdded;

    public Song() {
        this.id = "";
        this.title = "";
        this.path = "";
        this.source = SOURCE_LOCAL;
    }
}

