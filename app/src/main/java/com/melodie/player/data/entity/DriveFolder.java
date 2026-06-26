package com.melodie.player.data.entity;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(
        tableName = "drive_folders",
        indices = {@Index(value = {"driveId"}, unique = true)}
)
public class DriveFolder {
    @PrimaryKey(autoGenerate = true)
    public long id;

    @NonNull
    public String driveId = "";

    @NonNull
    public String name = "";

    @NonNull
    public String parentDriveId = "";

    public long lastSync;

    public boolean selected;

    /** true si ce dossier est une racine d'un Lecteur partagé (pas dans Mon Drive) */
    @ColumnInfo(defaultValue = "0")
    public boolean isSharedDrive;
}
