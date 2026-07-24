package com.melodie.player.data.db;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.melodie.player.data.entity.Album;

import java.util.List;

@Dao
public interface AlbumDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAll(List<Album> albums);

    @Query("DELETE FROM albums")
    void clear();

    @Query("SELECT * FROM albums " +
           "WHERE EXISTS (" +
           "  SELECT 1 FROM songs " +
           "  WHERE songs.albumId = albums.id " +
           "    AND songs.folderSourceId IN (SELECT id FROM folder_sources WHERE enabled = 1)" +
           ") " +
           "AND albums.hidden = 0 " +
           "ORDER BY COALESCE(artist, '') COLLATE NOCASE ASC, name COLLATE NOCASE ASC")
    LiveData<List<Album>> observeAll();

    @Query("SELECT * FROM albums " +
           "WHERE EXISTS (" +
           "  SELECT 1 FROM songs " +
           "  WHERE songs.albumId = albums.id " +
           "    AND songs.folderSourceId IN (SELECT id FROM folder_sources WHERE enabled = 1)" +
           ") " +
           "AND albums.hidden = 0 " +
           "ORDER BY COALESCE(artist, '') COLLATE NOCASE ASC, name COLLATE NOCASE ASC LIMIT :limit")
    LiveData<List<Album>> observeRecent(int limit);

    @Query("SELECT * FROM albums WHERE id = :albumId LIMIT 1")
    LiveData<Album> observeById(long albumId);

    @Query("SELECT * FROM albums WHERE hidden = 1 " +
           "ORDER BY COALESCE(artist, '') COLLATE NOCASE ASC, name COLLATE NOCASE ASC")
    LiveData<List<Album>> observeHidden();

    @Query("UPDATE albums SET cover = :cover WHERE id = :albumId")
    void updateCover(long albumId, String cover);

    /**
     * Efface le sentinel "pochette introuvable" pour permettre une nouvelle tentative
     * de recherche distante. Met la colonne cover à NULL afin que la logique de prefetch
     * la considère comme manquante. Retourne le nombre de lignes affectées.
     */
    @Query("UPDATE albums SET cover = NULL WHERE cover = :sentinel")
    int clearNoRemoteCoverSentinel(String sentinel);

    @Query("UPDATE albums SET releaseDate = :releaseDate WHERE id = :albumId")
    void updateReleaseDate(long albumId, String releaseDate);

    @Query("UPDATE albums SET hidden = :hidden WHERE id = :albumId")
    void setHidden(long albumId, boolean hidden);

    @Query("UPDATE albums SET name = :name, artist = :artist, releaseDate = :releaseDate, cover = :cover, " +
           "userEditedCover = CASE WHEN :cover IS NOT NULL THEN 1 ELSE 0 END, " +
           "userEditedReleaseDate = CASE WHEN :releaseDate IS NOT NULL AND :releaseDate != '' THEN 1 ELSE 0 END, " +
           "userEditedArtist = CASE WHEN :artist IS NOT NULL AND :artist != '' THEN 1 ELSE 0 END " +
           "WHERE id = :albumId")
    void updateMetadata(long albumId, String name, String artist, String releaseDate, String cover);

    @Query("DELETE FROM albums WHERE id IN (:albumIds)")
    void deleteByIds(List<Long> albumIds);

    @Query("DELETE FROM albums WHERE id NOT IN (SELECT DISTINCT albumId FROM songs)")
    void deleteOrphans();

    @Query("SELECT * FROM albums WHERE id = :albumId LIMIT 1")
    Album getByIdSync(long albumId);

    @Query("SELECT * FROM albums WHERE cover IS NULL OR cover = '' OR cover NOT LIKE 'http%' ORDER BY COALESCE(artist, '') COLLATE NOCASE ASC, name COLLATE NOCASE ASC")
    List<Album> getAlbumsMissingRemoteCover();

    @Query("SELECT * FROM albums ORDER BY COALESCE(artist, '') COLLATE NOCASE ASC, name COLLATE NOCASE ASC")
    List<Album> getAllSync();
}

