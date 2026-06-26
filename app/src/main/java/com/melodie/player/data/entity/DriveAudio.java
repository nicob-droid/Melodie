package com.melodie.player.data.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "drive_audio_files")
public class DriveAudio {
    @PrimaryKey
    @NonNull
    public String fileId = "";

    @NonNull
    public String fileName = "";

    @NonNull
    public String folderId = "";

    public long fileSize;

    public long lastModified;

    /** Duration from Google Drive audio metadata (ms). 0 if unavailable. */
    public long durationMs;

    /** Track number from Google Drive audio metadata. 0 if unavailable. */
    public int trackNumber;

    @NonNull
    public String webContentLink = "";

    public boolean downloaded;

    @NonNull
    public String localPath = "";
}

