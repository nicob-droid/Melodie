package com.melodie.player.data.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * Etat global persistant d'une session de synchro Drive.
 * Une seule ligne (id=1) est maintenue pour reprendre proprement apres restart process.
 */
@Entity(tableName = "drive_sync_session")
public class DriveSyncSession {
    @PrimaryKey
    public long id = 1L;

    @NonNull
    public String startPageToken = "";

    @NonNull
    public String selectionSignature = "";

    @NonNull
    public String metadataSchemaVersion = "";

    public long currentGeneration;

    public boolean syncing;

    @NonNull
    public String currentPhase = "";

    public int phaseCurrent;
    public int phaseTotal;
    public int tracksDone;
    public int tracksTotal;

    public long lastSyncStartedAt;
    public long lastSyncFinishedAt;
    public long lastProgressAt;

    @NonNull
    public String lastError = "";

    public long lastErrorAt;
}

