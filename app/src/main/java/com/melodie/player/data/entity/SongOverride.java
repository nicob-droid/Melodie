package com.melodie.player.data.entity;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * Correction de métadonnées appliquée par l'utilisateur, mémorisée par chanson (clé stable).
 * Permet de conserver l'artiste / le nom d'album édités MALGRÉ le rescan complet du MediaStore
 * (qui réinsère les chansons avec les balises brutes du fichier), et donc de rendre les fusions
 * d'albums durables.
 */
@Entity(tableName = "song_overrides")
public class SongOverride {

    @PrimaryKey
    @NonNull
    public String songId = "";

    /** Artiste effectif choisi par l'utilisateur (null = pas de correction). */
    @Nullable
    public String artist;

    /** Nom d'album effectif choisi par l'utilisateur (null = pas de correction). */
    @Nullable
    public String album;
}

