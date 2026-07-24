package com.melodie.player.data.entity;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.Entity;
import androidx.room.ColumnInfo;
import androidx.room.PrimaryKey;

@Entity(tableName = "albums")
public class Album {
    public static final int SOURCE_UNKNOWN = 0;
    public static final int SOURCE_LOCAL = 1;
    public static final int SOURCE_DRIVE = 2;
    public static final int SOURCE_MIXED = 3;

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

    /** Indique la provenance principale de l'album pour le badge UI. */
    public int sourceType = SOURCE_UNKNOWN;

    /** Vrai si l'utilisateur a manuellement défini la pochette via l'écran d'édition. */
    @ColumnInfo(defaultValue = "0")
    public boolean userEditedCover = false;

    /** Vrai si l'utilisateur a manuellement défini l'année via l'écran d'édition. */
    @ColumnInfo(defaultValue = "0")
    public boolean userEditedReleaseDate = false;

    /** Vrai si l'utilisateur a manuellement défini l'artiste via l'écran d'édition. */
    @ColumnInfo(defaultValue = "0")
    public boolean userEditedArtist = false;
}

