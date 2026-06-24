package com.melodie.player.data.db;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import com.melodie.player.data.entity.DriveFolder;

import java.util.List;

@Dao
public interface DriveFolderDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(DriveFolder folder);

    @Update
    void update(DriveFolder folder);

    @Query("SELECT * FROM drive_folders ORDER BY name COLLATE NOCASE ASC")
    LiveData<List<DriveFolder>> observeAll();

    @Query("SELECT * FROM drive_folders WHERE selected = 1")
    List<DriveFolder> getSelected();

    @Query("DELETE FROM drive_folders WHERE driveId = :driveId")
    void deleteById(String driveId);

    @Query("DELETE FROM drive_folders")
    void deleteAll();
}

