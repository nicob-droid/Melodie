package com.melodie.player.data.repository;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.Transformations;

import com.melodie.player.data.cover.CoverArtFetcher;
import com.melodie.player.data.db.AlbumDao;
import com.melodie.player.data.db.FolderSourceDao;
import com.melodie.player.data.db.SongDao;
import com.melodie.player.data.entity.Album;
import com.melodie.player.data.entity.FolderSource;
import com.melodie.player.data.entity.Song;
import com.melodie.player.data.model.ArtistData;
import com.melodie.player.data.scan.MediaStoreScanner;

import java.util.List;
import java.util.concurrent.ExecutorService;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.hilt.android.qualifiers.ApplicationContext;

@Singleton
public class MusicRepository {

    private static final String PREFS_NAME = "melodie_prefs";
    private static final String PREF_ONLINE_COVERS = "online_covers_enabled";
    private static final String NO_REMOTE_COVER = "__NO_REMOTE_COVER__";

    private final Context context;
    private final SongDao songDao;
    private final AlbumDao albumDao;
    private final FolderSourceDao folderSourceDao;
    private final CoverArtFetcher coverArtFetcher;
    private final ExecutorService executor;
    private final SharedPreferences prefs;

    @Inject
    public MusicRepository(@ApplicationContext Context context,
                           SongDao songDao,
                           AlbumDao albumDao,
                           FolderSourceDao folderSourceDao,
                           CoverArtFetcher coverArtFetcher,
                           ExecutorService executor) {
        this.context = context;
        this.songDao = songDao;
        this.albumDao = albumDao;
        this.folderSourceDao = folderSourceDao;
        this.coverArtFetcher = coverArtFetcher;
        this.executor = executor;
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public LiveData<List<Song>> observeAllSongs() {
        return songDao.observeAll();
    }

    public LiveData<List<Album>> observeAllAlbums() {
        return albumDao.observeAll();
    }

    public LiveData<List<Album>> observeRecentAlbums(int limit) {
        return albumDao.observeRecent(limit);
    }

    public LiveData<List<Song>> observeFavorites() {
        return songDao.observeFavorites();
    }

    public LiveData<List<String>> observeArtists() {
        return songDao.observeArtists();
    }

    public LiveData<List<ArtistData>> observeArtistsWithData() {
        return Transformations.map(songDao.observeArtistsWithData(), rows -> {
            java.util.List<ArtistData> result = new java.util.ArrayList<>();
            if (rows != null) {
                for (SongDao.ArtistDataRow row : rows) {
                    result.add(new ArtistData(row.artist, row.cover, row.cnt));
                }
            }
            return result;
        });
    }

    public LiveData<List<FolderSource>> observeFolderSources() {
        return folderSourceDao.observeAll();
    }

    public LiveData<List<Song>> search(String q) {
        return songDao.search(q);
    }

    public void toggleFavorite(Song song) {
        executor.execute(() -> songDao.setFavorite(song.id, !song.favorite));
    }

    public void addFolderSource(String displayName, String treeUri) {
        executor.execute(() -> {
            FolderSource source = new FolderSource();
            source.displayName = displayName != null && !displayName.trim().isEmpty()
                    ? displayName.trim()
                    : "Folder";
            source.treeUri = treeUri != null ? treeUri : "";
            source.enabled = true;
            source.createdAt = System.currentTimeMillis();
            folderSourceDao.insert(source);
        });
    }

    public void toggleFolderSourceEnabled(FolderSource source) {
        if (source == null) return;
        executor.execute(() -> {
            source.enabled = !source.enabled;
            folderSourceDao.update(source);
        });
    }

    public void setFolderSourceEnabled(FolderSource source, boolean enabled) {
        if (source == null) return;
        executor.execute(() -> {
            source.enabled = enabled;
            folderSourceDao.update(source);
        });
    }

    public void removeFolderSource(FolderSource source) {
        if (source == null) return;
        executor.execute(() -> folderSourceDao.deleteById(source.id));
    }

    public void scanLocal(Runnable onDone) {
        executor.execute(() -> {
            scanLocalInternal();
            if (onDone != null) onDone.run();
        });
    }

    public void ensureLocalIndexed(Runnable onDone) {
        executor.execute(() -> {
            try {
                if (songDao.countBySource(Song.SOURCE_LOCAL) == 0) {
                    scanLocalInternal();
                }
            } finally {
                if (onDone != null) onDone.run();
            }
        });
    }

    public void resolveAlbumCover(Album album, boolean force, Runnable onDone) {
        executor.execute(() -> {
            try {
                if (album == null) return;
                if (!isOnlineCoverEnabled()) return;
                if (!force && album.cover != null
                        && (album.cover.startsWith("http") || NO_REMOTE_COVER.equals(album.cover))) {
                    return;
                }

                String remoteCover = coverArtFetcher.fetchAlbumCover(album.artist, album.name);
                if (remoteCover != null && !remoteCover.isEmpty()) {
                    albumDao.updateCover(album.id, remoteCover);
                } else if (album.cover == null || album.cover.trim().isEmpty() || !album.cover.startsWith("http")) {
                    // Memorise l'absence de resultat pour eviter de retenter a chaque affichage.
                    albumDao.updateCover(album.id, NO_REMOTE_COVER);
                }
            } finally {
                if (onDone != null) onDone.run();
            }
        });
    }

    public boolean isOnlineCoverEnabled() {
        return prefs.getBoolean(PREF_ONLINE_COVERS, true);
    }

    public void setOnlineCoverEnabled(boolean enabled) {
        prefs.edit().putBoolean(PREF_ONLINE_COVERS, enabled).apply();
    }

    public void resolveMissingCoversNow(Runnable onDone) {
        executor.execute(() -> {
            try {
                if (!isOnlineCoverEnabled()) return;
                for (Album album : albumDao.getAllSync()) {
                    // On ne retraite que les albums sans pochette HTTP valide :
                    // - cover null ou vide
                    // - sentinel NO_REMOTE_COVER (nouvelle tentative)
                    // Les URLs http(s) deja sauvegardees sont preservees telles quelles.
                    if (album.cover != null
                            && !album.cover.trim().isEmpty()
                            && album.cover.startsWith("http")) {
                        continue;
                    }

                    String remoteCover = coverArtFetcher.fetchAlbumCover(album.artist, album.name);
                    if (remoteCover != null && !remoteCover.isEmpty()) {
                        albumDao.updateCover(album.id, remoteCover);
                    } else {
                        // Toujours rien trouve : on (re)pose le sentinel pour eviter de
                        // retenter a chaque affichage de la liste.
                        albumDao.updateCover(album.id, NO_REMOTE_COVER);
                    }
                }
            } finally {
                if (onDone != null) onDone.run();
            }
        });
    }

    private void scanLocalInternal() {
        MediaStoreScanner.ScanResult result = MediaStoreScanner.scan(context);
        songDao.deleteBySource(Song.SOURCE_LOCAL);
        songDao.insertAll(result.songs);
        albumDao.clear();
        albumDao.insertAll(result.albums);
    }
}

