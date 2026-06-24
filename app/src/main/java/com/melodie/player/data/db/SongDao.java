package com.melodie.player.data.db;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import com.melodie.player.data.entity.Song;
import com.melodie.player.data.model.ArtistData;

import java.util.List;

@Dao
public interface SongDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAll(List<Song> songs);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(Song song);

    @Update
    void update(Song song);

    @Query("DELETE FROM songs WHERE source = :source")
    void deleteBySource(String source);

    @Query("DELETE FROM songs WHERE source = 'LOCAL' AND folderSourceId NOT IN (:activeFolderSourceIds)")
    void deleteLocalSongsNotInFolderSources(List<Long> activeFolderSourceIds);

    // Colonnes completes d'une Song en utilisant TOUJOURS la pochette de l'album (table albums)
    // comme source de verite, afin d'etre parfaitement synchronise avec les onglets Albums/Artists.
    // Fallback sur songs.cover uniquement si la chanson n'a pas de ligne album correspondante.
    String SONG_WITH_ALBUM_COVER_COLUMNS =
            "songs.id AS id, songs.title AS title, songs.artist AS artist, songs.album AS album, "
            + "songs.albumId AS albumId, songs.trackNumber AS trackNumber, songs.duration AS duration, songs.path AS path, "
            + "songs.source AS source, songs.folderSourceId AS folderSourceId, "
            + "COALESCE(albums.cover, songs.cover) AS cover, "
            + "songs.favorite AS favorite, songs.dateAdded AS dateAdded";

    @Query("SELECT " + SONG_WITH_ALBUM_COVER_COLUMNS
            + " FROM songs LEFT JOIN albums ON albums.id = songs.albumId "
            + "ORDER BY songs.title COLLATE NOCASE ASC")
    LiveData<List<Song>> observeAll();

    @Query("SELECT * FROM songs ORDER BY title COLLATE NOCASE ASC")
    List<Song> getAll();

    @Query("SELECT " + SONG_WITH_ALBUM_COVER_COLUMNS
            + " FROM songs LEFT JOIN albums ON albums.id = songs.albumId "
            + "WHERE songs.favorite = 1 ORDER BY songs.title COLLATE NOCASE ASC")
    LiveData<List<Song>> observeFavorites();

    @Query("SELECT " + SONG_WITH_ALBUM_COVER_COLUMNS
            + " FROM songs LEFT JOIN albums ON albums.id = songs.albumId "
            + "WHERE songs.albumId = :albumId "
            + "ORDER BY songs.trackNumber ASC, songs.title COLLATE NOCASE ASC")
    LiveData<List<Song>> observeByAlbum(long albumId);

    @Query("SELECT " + SONG_WITH_ALBUM_COVER_COLUMNS
            + " FROM songs LEFT JOIN albums ON albums.id = songs.albumId "
            + "WHERE songs.artist = :artist "
            + "ORDER BY COALESCE(NULLIF(albums.releaseDate, ''), '9999') ASC, "
            + "songs.albumId ASC, "
            + "songs.trackNumber ASC, "
            + "songs.title COLLATE NOCASE ASC")
    LiveData<List<Song>> observeByArtist(String artist);

    @Query("SELECT " + SONG_WITH_ALBUM_COVER_COLUMNS
            + " FROM songs LEFT JOIN albums ON albums.id = songs.albumId "
            + "WHERE songs.title LIKE '%' || :q || '%' "
            + "OR songs.artist LIKE '%' || :q || '%' "
            + "OR songs.album LIKE '%' || :q || '%' "
            + "ORDER BY songs.title COLLATE NOCASE ASC LIMIT 100")
    LiveData<List<Song>> search(String q);

    @Query("SELECT * FROM songs WHERE id = :id")
    Song getById(String id);

    @Query("UPDATE songs SET favorite = :fav WHERE id = :id")
    void setFavorite(String id, boolean fav);

    @Query("SELECT DISTINCT artist FROM songs WHERE artist IS NOT NULL ORDER BY artist COLLATE NOCASE ASC")
    LiveData<List<String>> observeArtists();

    @Query("SELECT COUNT(*) FROM songs WHERE source = :source")
    int countBySource(String source);

    @Query("SELECT songs.artist as artist, COUNT(songs.id) as cnt, " +
           "COALESCE(" +
           "  (SELECT cover FROM albums WHERE artist = songs.artist AND cover LIKE 'http%' LIMIT 1), " +
           "  (SELECT cover FROM albums WHERE artist = songs.artist LIMIT 1)" +
           ") as cover " +
           "FROM songs WHERE songs.artist IS NOT NULL " +
           "GROUP BY songs.artist ORDER BY songs.artist COLLATE NOCASE ASC")
    LiveData<List<ArtistDataRow>> observeArtistsWithData();

    class ArtistDataRow {
        public String artist;
        public int cnt;
        public String cover;
    }
}

