package com.melodie.player.data.db;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import com.melodie.player.data.entity.FolderSource;

import java.util.List;

@Dao
public interface FolderSourceDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insert(FolderSource source);

    @Update
    void update(FolderSource source);

    @Query("SELECT * FROM folder_sources ORDER BY enabled DESC, displayName COLLATE NOCASE ASC")
    LiveData<List<FolderSource>> observeAll();

    @Query("SELECT * FROM folder_sources WHERE treeUri = :treeUri LIMIT 1")
    FolderSource getByTreeUri(String treeUri);

    @Query("DELETE FROM folder_sources WHERE id = :id")
    void deleteById(long id);

    @Query("DELETE FROM folder_sources")
    void deleteAll();
}

