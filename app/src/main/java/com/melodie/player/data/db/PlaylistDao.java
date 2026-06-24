package com.melodie.player.data.db;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.melodie.player.data.entity.Playlist;
import com.melodie.player.data.entity.PlaylistSong;

import java.util.List;

@Dao
public interface PlaylistDao {

    @Insert
    long createPlaylist(Playlist p);

    @Query("SELECT * FROM playlists ORDER BY createdAt DESC")
    LiveData<List<Playlist>> observeAll();

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void addSong(PlaylistSong ps);

    @Query("DELETE FROM playlist_song WHERE playlistId = :playlistId AND songId = :songId")
    void removeSong(long playlistId, String songId);

    @Query("SELECT songId FROM playlist_song WHERE playlistId = :id ORDER BY position ASC")
    List<String> getSongIds(long id);
}

