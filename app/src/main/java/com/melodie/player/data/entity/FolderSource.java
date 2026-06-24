package com.melodie.player.data.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(
        tableName = "folder_sources",
        indices = {@Index(value = "treeUri", unique = true)}
)
public class FolderSource {

    @PrimaryKey(autoGenerate = true)
    public long id;

    @NonNull
    public String displayName = "";

    @NonNull
    public String treeUri = "";

    public boolean enabled = true;

    public long createdAt;
}

