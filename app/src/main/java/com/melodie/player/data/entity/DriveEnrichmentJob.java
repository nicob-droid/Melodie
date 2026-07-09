package com.melodie.player.data.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * File de taches persistante pour l'enrichissement Drive (duree + tags).
 */
@Entity(tableName = "drive_enrichment_jobs")
public class DriveEnrichmentJob {
    public static final String STATE_PENDING = "PENDING";
    public static final String STATE_RUNNING = "RUNNING";
    public static final String STATE_DONE = "DONE";
    public static final String STATE_FAILED = "FAILED";

    @PrimaryKey
    @NonNull
    public String fileId = "";

    public boolean needDuration;
    public boolean needTags;

    public int priority;

    @NonNull
    public String state = STATE_PENDING;

    public int attemptCount;
    public long lastAttemptAt;

    @NonNull
    public String lastError = "";

    public long generation;
    public long updatedAt;
}

