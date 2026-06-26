package com.melodie.player.data.db;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import com.melodie.player.data.entity.DriveAudio;

import java.util.List;

@Dao
public interface DriveAudioDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(DriveAudio audio);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAll(List<DriveAudio> audios);

    @Update
    void update(DriveAudio audio);

    @Query("SELECT * FROM drive_audio_files WHERE folderId = :folderId ORDER BY fileName COLLATE NOCASE ASC")
    LiveData<List<DriveAudio>> observeByFolder(String folderId);

    @Query("SELECT * FROM drive_audio_files WHERE folderId = :folderId")
    List<DriveAudio> getByFolderSync(String folderId);

    @Query("SELECT * FROM drive_audio_files WHERE downloaded = 1 ORDER BY fileName COLLATE NOCASE ASC")
    LiveData<List<DriveAudio>> observeDownloaded();

    @Query("SELECT * FROM drive_audio_files WHERE fileId = :fileId")
    DriveAudio getById(String fileId);

    @Query("DELETE FROM drive_audio_files WHERE folderId = :folderId")
    void deleteByFolder(String folderId);

    @Query("DELETE FROM drive_audio_files WHERE fileId = :fileId")
    void deleteById(String fileId);

    @Query("DELETE FROM drive_audio_files")
    void clear();
}

