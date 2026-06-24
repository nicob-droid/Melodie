package com.melodie.player.data.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "playlist_song",
        primaryKeys = {"playlistId", "songId"})
public class PlaylistSong {
    public long playlistId;

    @NonNull
    public String songId = "";

    public int position;
}

