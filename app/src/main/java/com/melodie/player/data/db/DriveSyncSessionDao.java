package com.melodie.player.data.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.melodie.player.data.entity.DriveSyncSession;

@Dao
public interface DriveSyncSessionDao {

    @Query("SELECT * FROM drive_sync_session WHERE id = 1 LIMIT 1")
    DriveSyncSession get();

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(DriveSyncSession session);

    @Query("DELETE FROM drive_sync_session")
    void clear();
}

