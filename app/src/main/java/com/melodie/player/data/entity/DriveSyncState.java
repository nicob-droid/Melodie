package com.melodie.player.data.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "drive_sync_state")
public class DriveSyncState {
    @PrimaryKey
    @NonNull
    public String key = "";

    @NonNull
    public String value = "";

    public long updatedAt;
}

