package com.melodie.player.data.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "drive_folders")
public class DriveFolder {
    @PrimaryKey(autoGenerate = true)
    public long id;

    @NonNull
    public String driveId = "";

    @NonNull
    public String name = "";

    public long lastSync;

    public boolean selected;
}

