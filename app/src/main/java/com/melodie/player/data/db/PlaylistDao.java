package com.melodie.player.data.db;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Transaction;

import com.melodie.player.data.entity.Song;
import com.melodie.player.data.entity.Playlist;
import com.melodie.player.data.entity.PlaylistSong;
import com.melodie.player.data.model.PlaylistSummary;

import java.util.List;

@Dao
public interface PlaylistDao {

    @Insert
    long createPlaylist(Playlist p);

    @Query("SELECT p.id AS id, p.name AS name, p.createdAt AS createdAt, "
            + "COUNT(ps.songId) AS songCount, COALESCE(SUM(s.duration), 0) AS totalDuration "
            + "FROM playlists p "
            + "LEFT JOIN playlist_song ps ON ps.playlistId = p.id "
            + "LEFT JOIN songs s ON s.id = ps.songId "
            + "GROUP BY p.id "
            + "ORDER BY p.createdAt DESC")
    LiveData<List<PlaylistSummary>> observeAllSummaries();

    @Query("SELECT * FROM playlists WHERE id = :playlistId LIMIT 1")
    LiveData<Playlist> observePlaylist(long playlistId);

    @Query("SELECT id FROM playlists WHERE name = :name LIMIT 1")
    Long getPlaylistIdByName(String name);

    @Query("SELECT COUNT(*) FROM playlist_song WHERE playlistId = :playlistId AND songId = :songId")
    int isSongInPlaylist(long playlistId, String songId);

    @Query("SELECT " + SongDao.SONG_WITH_ALBUM_COVER_COLUMNS
            + " FROM playlist_song ps "
            + "JOIN songs ON songs.id = ps.songId "
            + "LEFT JOIN albums ON albums.id = songs.albumId "
            + "WHERE ps.playlistId = :playlistId "
            + "ORDER BY ps.position ASC")
    LiveData<List<Song>> observeSongs(long playlistId);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void addSong(PlaylistSong ps);

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM playlist_song WHERE playlistId = :playlistId")
    int getNextPosition(long playlistId);

    @Query("DELETE FROM playlist_song WHERE playlistId = :playlistId AND songId = :songId")
    void removeSong(long playlistId, String songId);

    @Query("DELETE FROM playlist_song WHERE playlistId = :playlistId")
    void clearSongs(long playlistId);

    @Query("UPDATE playlist_song SET position = :position WHERE playlistId = :playlistId AND songId = :songId")
    void updateSongPosition(long playlistId, String songId, int position);

    @Query("UPDATE playlists SET name = :name WHERE id = :playlistId")
    void rename(long playlistId, String name);

    @Query("DELETE FROM playlists WHERE id = :playlistId")
    void deletePlaylist(long playlistId);

    @Query("SELECT songId FROM playlist_song WHERE playlistId = :id ORDER BY position ASC")
    List<String> getSongIds(long id);

    @Transaction
    default void addSongAtEnd(long playlistId, String songId) {
        PlaylistSong ps = new PlaylistSong();
        ps.playlistId = playlistId;
        ps.songId = songId;
        ps.position = getNextPosition(playlistId);
        addSong(ps);
    }

    @Transaction
    default void replaceOrder(long playlistId, List<String> songIds) {
        if (songIds == null || songIds.isEmpty()) return;
        for (int i = 0; i < songIds.size(); i++) {
            updateSongPosition(playlistId, songIds.get(i), i);
        }
    }

    @Transaction
    default void deletePlaylistWithSongs(long playlistId) {
        clearSongs(playlistId);
        deletePlaylist(playlistId);
    }
}

