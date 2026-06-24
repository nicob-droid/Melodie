package com.melodie.player.data.entity;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "albums")
public class Album {
    @PrimaryKey
    public long id;

    @NonNull
    public String name = "";

    @Nullable
    public String artist;

    @Nullable
    public String cover;

    @Nullable
    public String releaseDate;

    public int count;
}

