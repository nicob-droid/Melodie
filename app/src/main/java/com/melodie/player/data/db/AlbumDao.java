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

    @Query("SELECT * FROM albums ORDER BY COALESCE(artist, '') COLLATE NOCASE ASC, name COLLATE NOCASE ASC")
    LiveData<List<Album>> observeAll();

    @Query("SELECT * FROM albums ORDER BY COALESCE(artist, '') COLLATE NOCASE ASC, name COLLATE NOCASE ASC LIMIT :limit")
    LiveData<List<Album>> observeRecent(int limit);

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

    @Query("DELETE FROM albums WHERE id IN (:albumIds)")
    void deleteByIds(List<Long> albumIds);

    @Query("SELECT * FROM albums WHERE id = :albumId LIMIT 1")
    Album getByIdSync(long albumId);

    @Query("SELECT * FROM albums WHERE cover IS NULL OR cover = '' OR cover NOT LIKE 'http%' ORDER BY COALESCE(artist, '') COLLATE NOCASE ASC, name COLLATE NOCASE ASC")
    List<Album> getAlbumsMissingRemoteCover();

    @Query("SELECT * FROM albums ORDER BY COALESCE(artist, '') COLLATE NOCASE ASC, name COLLATE NOCASE ASC")
    List<Album> getAllSync();
}

