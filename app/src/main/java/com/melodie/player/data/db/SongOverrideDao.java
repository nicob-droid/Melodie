package com.melodie.player.data.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.melodie.player.data.entity.SongOverride;

import java.util.List;

@Dao
public interface SongOverrideDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertAll(List<SongOverride> overrides);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(SongOverride override);

    @Query("SELECT * FROM song_overrides")
    List<SongOverride> getAllSync();

    @Query("SELECT * FROM song_overrides WHERE songId = :songId LIMIT 1")
    SongOverride getByIdSync(String songId);

    @Query("DELETE FROM song_overrides WHERE songId IN (:songIds)")
    void deleteByIds(List<String> songIds);

    @Query("DELETE FROM song_overrides")
    void clear();
}

