package com.melodie.player.data.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.melodie.player.data.entity.DriveSyncState;

@Dao
public interface DriveSyncStateDao {

    @Query("SELECT * FROM drive_sync_state WHERE `key` = :key LIMIT 1")
    DriveSyncState get(String key);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(DriveSyncState state);

    @Query("DELETE FROM drive_sync_state WHERE `key` = :key")
    void delete(String key);

    @Query("DELETE FROM drive_sync_state")
    void clear();
}

