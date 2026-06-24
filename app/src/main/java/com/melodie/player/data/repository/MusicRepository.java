package com.melodie.player.data.repository;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Environment;
import android.provider.DocumentsContract;

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
import java.util.ArrayList;
import java.io.File;
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

    public LiveData<List<Song>> observeSongsByAlbum(long albumId) {
        return songDao.observeByAlbum(albumId);
    }

    public LiveData<List<Song>> observeSongsByArtist(String artist) {
        return songDao.observeByArtist(artist);
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
            // Rescan immédiatement pour que les fichiers du nouveau dossier apparaissent aussitôt.
            performFullScan();
        });
    }

    public void toggleFolderSourceEnabled(FolderSource source) {
        if (source == null) return;
        executor.execute(() -> {
            source.enabled = !source.enabled;
            folderSourceDao.update(source);
            // Rescan pour refléter le changement d'état (activation / désactivation).
            // On supprime aussi les chansons orphelines des sources désactivées.
            performFullScan();
        });
    }

    public void setFolderSourceEnabled(FolderSource source, boolean enabled) {
        if (source == null) return;
        executor.execute(() -> {
            source.enabled = enabled;
            folderSourceDao.update(source);
            // Rescan pour refléter le changement d'état.
            performFullScan();
        });
    }

    public void removeFolderSource(FolderSource source) {
        if (source == null) return;
        executor.execute(() -> {
            folderSourceDao.deleteById(source.id);
            // Rescan pour supprimer les chansons issues de ce dossier de la bibliothèque.
            performFullScan();
        });
    }

     public void scanLocal(Runnable onDone) {
         executor.execute(() -> {
             try {
                 performFullScan();
             } finally {
                 if (onDone != null) onDone.run();
             }
         });
     }

     public void ensureLocalIndexed(Runnable onDone) {
         executor.execute(() -> {
             try {
                 performFullScan();
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

     /**
      * Effectue un scan complet du MediaStore LOCAL :
      * 1. Sauvegarde les URLs de pochettes HTTP déjà connues (pour ne pas les re-télécharger)
      * 2. Supprime toutes les chansons LOCAL existantes
      * 3. Rescanne le MediaStore
      * 4. Réinsère les albums en restaurant les pochettes HTTP déjà sauvegardées
      *
      * Cela garantit la cohérence entre la base de données et le MediaStore,
      * tout en préservant les pochettes déjà téléchargées.
      */
     private void performFullScan() {
         // 1. Snapshot des covers HTTP/NO_REMOTE_COVER valides AVANT de tout effacer.
         //    On préserve aussi le sentinel __NO_REMOTE_COVER__ pour ne pas retenter
         //    un fetch réseau sur des albums déjà confirmés sans pochette distante.
         java.util.Map<Long, String> savedCovers = new java.util.HashMap<>();
         for (Album album : albumDao.getAllSync()) {
             if (album.cover != null && !album.cover.trim().isEmpty()
                     && (album.cover.startsWith("http") || NO_REMOTE_COVER.equals(album.cover))) {
                 savedCovers.put(album.id, album.cover);
             }
         }

         // 2. Scan MediaStore.
         MediaStoreScanner.ScanResult result = MediaStoreScanner.scan(context, buildActiveSourceRoots());

         // 3. Pré-remplir la cover dans les albums avant insertion, pour que la LiveData
         //    fire directement avec la bonne pochette HTTP.
         //    Ainsi Glide charge l'URL HTTP directement, sans passer par content://.
         for (Album album : result.albums) {
             String savedCover = savedCovers.get(album.id);
             if (savedCover != null) {
                 album.cover = savedCover;
             }
         }

         // 4. Mise en base en une seule passe – la LiveData ne fire qu'une fois
         //    avec les pochettes déjà correctes.
         songDao.deleteBySource(Song.SOURCE_LOCAL);
         songDao.insertAll(result.songs);
         albumDao.clear();
         albumDao.insertAll(result.albums);
     }

     private List<MediaStoreScanner.SourceRoot> buildActiveSourceRoots() {
         List<MediaStoreScanner.SourceRoot> roots = new ArrayList<>();

         String defaultMusicPath = Environment
                 .getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
                 .getAbsolutePath();
         if (defaultMusicPath != null && !defaultMusicPath.trim().isEmpty()) {
             roots.add(new MediaStoreScanner.SourceRoot(0L, defaultMusicPath));
         }

         for (FolderSource source : folderSourceDao.getAllSync()) {
             if (source == null || !source.enabled || source.id <= 0L) continue;
             String absolutePath = treeUriToAbsolutePath(source.treeUri);
             if (absolutePath != null && !absolutePath.trim().isEmpty()) {
                 roots.add(new MediaStoreScanner.SourceRoot(source.id, absolutePath));
             }
         }

         return roots;
     }

     private String treeUriToAbsolutePath(String treeUri) {
         if (treeUri == null || treeUri.trim().isEmpty()) return null;
         try {
             Uri uri = Uri.parse(treeUri);
             String docId = DocumentsContract.getTreeDocumentId(uri);
             if (docId == null || docId.trim().isEmpty()) return null;

             String[] parts = docId.split(":", 2);
             if (parts.length == 0) return null;

             String volume = parts[0];
             String relative = parts.length > 1 ? parts[1] : "";
             String basePath;
             if ("primary".equalsIgnoreCase(volume)) {
                 basePath = Environment.getExternalStorageDirectory().getAbsolutePath();
             } else {
                 basePath = "/storage/" + volume;
             }

             if (relative.isEmpty()) return basePath;
             return new File(basePath, relative).getAbsolutePath();
         } catch (Exception ignored) {
             return null;
         }
     }
}

