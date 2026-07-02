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
import com.melodie.player.data.db.PlaylistDao;
import com.melodie.player.data.db.SongDao;
import com.melodie.player.data.entity.Album;
import com.melodie.player.data.entity.FolderSource;
import com.melodie.player.data.entity.Playlist;
import com.melodie.player.data.entity.Song;
import com.melodie.player.data.model.ArtistData;
import com.melodie.player.data.model.PlaylistSummary;
import com.melodie.player.data.scan.MediaStoreScanner;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.function.LongConsumer;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.hilt.android.qualifiers.ApplicationContext;

@Singleton
public class MusicRepository {

    private static final String PREFS_NAME = "melodie_prefs";
    private static final String PREF_ONLINE_COVERS = "online_covers_enabled";
    private static final String PREF_LOCAL_SOURCE_SEEDED = "local_source_seeded";
    private static final String PREF_LOCAL_SOURCE_SUPPRESSED = "local_source_suppressed";
    private static final String NO_REMOTE_COVER = "__NO_REMOTE_COVER__";
    private static final String DRIVE_SOURCE_PREFIX = "drive://folder/";
    private static final String LOCAL_SOURCE_TREE_URI = "local://music";
    private static final String LOCAL_SOURCE_DISPLAY_NAME = "Téléphone / Music";
    private static final String FAVORITES_PLAYLIST_NAME = "Favorites";

    private final Context context;
    private final SongDao songDao;
    private final AlbumDao albumDao;
    private final PlaylistDao playlistDao;
    private final FolderSourceDao folderSourceDao;
    private final CoverArtFetcher coverArtFetcher;
    private final ExecutorService executor;
    private final SharedPreferences prefs;

    @Inject
    public MusicRepository(@ApplicationContext Context context,
                           SongDao songDao,
                           AlbumDao albumDao,
                           PlaylistDao playlistDao,
                           FolderSourceDao folderSourceDao,
                           CoverArtFetcher coverArtFetcher,
                           ExecutorService executor) {
        this.context = context;
        this.songDao = songDao;
        this.albumDao = albumDao;
        this.playlistDao = playlistDao;
        this.folderSourceDao = folderSourceDao;
        this.coverArtFetcher = coverArtFetcher;
        this.executor = executor;
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        try {
            executor.submit(() -> {
                ensureDefaultLocalSourceSeeded();
                return null;
            }).get();
        } catch (Exception e) {
            android.util.Log.w("MusicRepository", "Unable to seed default local source", e);
        }
    }

    private void ensureDefaultLocalSourceSeeded() {
        try {
            if (prefs.getBoolean(PREF_LOCAL_SOURCE_SUPPRESSED, false)
                    || prefs.getBoolean(PREF_LOCAL_SOURCE_SEEDED, false)) {
                return;
            }

            FolderSource localSource = folderSourceDao.getByTreeUri(LOCAL_SOURCE_TREE_URI);
            if (localSource == null) {
                FolderSource source = new FolderSource();
                source.displayName = LOCAL_SOURCE_DISPLAY_NAME;
                source.treeUri = LOCAL_SOURCE_TREE_URI;
                source.enabled = true;
                source.createdAt = System.currentTimeMillis();
                folderSourceDao.insert(source);
            }
            prefs.edit().putBoolean(PREF_LOCAL_SOURCE_SEEDED, true).apply();
        } catch (Exception e) {
            // Si le seed échoue, on n'empêche pas l'app de démarrer ; le prochain accès réessaiera.
            android.util.Log.w("MusicRepository", "Unable to seed default local source", e);
        }
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

    public LiveData<List<PlaylistSummary>> observePlaylists() {
        return playlistDao.observeAllSummaries();
    }

    public LiveData<Playlist> observePlaylist(long playlistId) {
        return playlistDao.observePlaylist(playlistId);
    }

    public LiveData<List<Song>> observePlaylistSongs(long playlistId) {
        return playlistDao.observeSongs(playlistId);
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

    /**
     * Indique (de façon asynchrone) si un morceau appartient à la playlist "Favorites".
     * Le callback est invoké depuis un thread d'arrière-plan : l'appelant doit basculer
     * sur le thread principal s'il met à jour l'UI.
     */
    public void isSongFavorite(String songId, java.util.function.Consumer<Boolean> callback) {
        if (callback == null) return;
        if (songId == null || songId.trim().isEmpty()) {
            callback.accept(false);
            return;
        }
        executor.execute(() -> {
            Long playlistId = playlistDao.getPlaylistIdByName(FAVORITES_PLAYLIST_NAME);
            boolean favorite = playlistId != null
                    && playlistDao.isSongInPlaylist(playlistId, songId) > 0;
            callback.accept(favorite);
        });
    }

    /**
     * Ajoute ou retire un morceau de la playlist "Favorites". Si la playlist n'existe
     * pas encore et que l'on ajoute un favori, elle est créée automatiquement.
     */
    public void setSongFavorite(String songId, boolean favorite, Runnable onDone) {
        if (songId == null || songId.trim().isEmpty()) return;
        executor.execute(() -> {
            Long playlistId = playlistDao.getPlaylistIdByName(FAVORITES_PLAYLIST_NAME);
            if (favorite) {
                if (playlistId == null) {
                    Playlist playlist = new Playlist();
                    playlist.name = FAVORITES_PLAYLIST_NAME;
                    playlist.createdAt = System.currentTimeMillis();
                    playlistId = playlistDao.createPlaylist(playlist);
                }
                if (playlistDao.isSongInPlaylist(playlistId, songId) == 0) {
                    playlistDao.addSongAtEnd(playlistId, songId);
                }
            } else if (playlistId != null) {
                playlistDao.removeSong(playlistId, songId);
            }
            if (onDone != null) onDone.run();
        });
    }

    public void createPlaylist(String name, LongConsumer onDone) {
        executor.execute(() -> {
            Playlist playlist = new Playlist();
            playlist.name = name;
            playlist.createdAt = System.currentTimeMillis();
            long id = playlistDao.createPlaylist(playlist);
            if (onDone != null) onDone.accept(id);
        });
    }

    public void addSongToPlaylist(long playlistId, String songId, Runnable onDone) {
        if (playlistId <= 0 || songId == null || songId.trim().isEmpty()) return;
        executor.execute(() -> {
            playlistDao.addSongAtEnd(playlistId, songId);
            if (onDone != null) onDone.run();
        });
    }

    public void removeSongFromPlaylist(long playlistId, String songId) {
        if (playlistId <= 0 || songId == null || songId.trim().isEmpty()) return;
        executor.execute(() -> playlistDao.removeSong(playlistId, songId));
    }

    public void renamePlaylist(long playlistId, String name) {
        if (playlistId <= 0 || name == null || name.trim().isEmpty()) return;
        executor.execute(() -> playlistDao.rename(playlistId, name.trim()));
    }

    public void deletePlaylist(long playlistId) {
        if (playlistId <= 0) return;
        executor.execute(() -> playlistDao.deletePlaylistWithSongs(playlistId));
    }

    public void reorderPlaylist(long playlistId, List<String> orderedSongIds) {
        if (playlistId <= 0 || orderedSongIds == null || orderedSongIds.isEmpty()) return;
        executor.execute(() -> playlistDao.replaceOrder(playlistId, orderedSongIds));
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

    public void addDriveFolderSource(String displayName, String driveFolderId) {
        if (driveFolderId == null || driveFolderId.trim().isEmpty()) return;
        executor.execute(() -> {
            String normalizedId = driveFolderId.trim();
            String driveTreeUri = DRIVE_SOURCE_PREFIX + normalizedId;

            FolderSource existing = folderSourceDao.getByTreeUri(driveTreeUri);
            if (existing != null) {
                existing.displayName = displayName != null && !displayName.trim().isEmpty()
                        ? displayName.trim()
                        : existing.displayName;
                existing.enabled = true;
                folderSourceDao.update(existing);
                rebuildAlbumsFromSongs();
                return;
            }

            FolderSource source = new FolderSource();
            source.displayName = displayName != null && !displayName.trim().isEmpty()
                    ? displayName.trim()
                    : "Google Drive";
            source.treeUri = driveTreeUri;
            source.enabled = true;
            source.createdAt = System.currentTimeMillis();
            folderSourceDao.insert(source);
            rebuildAlbumsFromSongs();
        });
    }

    public void toggleFolderSourceEnabled(FolderSource source) {
        if (source == null) return;
        executor.execute(() -> {
            source.enabled = !source.enabled;
            if (isLocalMusicSource(source.treeUri)) {
                prefs.edit().putBoolean(PREF_LOCAL_SOURCE_SUPPRESSED, true).apply();
            }
            folderSourceDao.update(source);
            if (shouldRescanLocalLibrary(source)) {
                // Rescan pour refléter le changement d'état (activation / désactivation).
                // On supprime aussi les chansons orphelines des sources désactivées.
                performFullScan();
            } else {
                rebuildAlbumsFromSongs();
            }
        });
    }

    public void setFolderSourceEnabled(FolderSource source, boolean enabled) {
        if (source == null) return;
        executor.execute(() -> {
            source.enabled = enabled;
            if (isLocalMusicSource(source.treeUri)) {
                prefs.edit().putBoolean(PREF_LOCAL_SOURCE_SUPPRESSED, true).apply();
            }
            folderSourceDao.update(source);
            if (shouldRescanLocalLibrary(source)) {
                // Rescan pour refléter le changement d'état.
                performFullScan();
            } else {
                rebuildAlbumsFromSongs();
            }
        });
    }

    public void removeFolderSource(FolderSource source) {
        if (source == null) return;
        executor.execute(() -> {
            if (isLocalMusicSource(source.treeUri)) {
                prefs.edit().putBoolean(PREF_LOCAL_SOURCE_SUPPRESSED, true).apply();
            }
            folderSourceDao.deleteById(source.id);
            if (shouldRescanLocalLibrary(source)) {
                // Rescan pour supprimer les chansons issues de ce dossier de la bibliothèque.
                performFullScan();
            } else {
                songDao.deleteDriveSongsByFolderSourceId(source.id);
                songDao.deleteLocalSongsByFolderSourceId(source.id);
                rebuildAlbumsFromSongs();
            }
        });
    }

    /**
     * Reconstruit les lignes de `albums` à partir des morceaux visibles dans `songs`.
     * Les morceaux Drive désactivés restent stockés mais sont exclus de l'agrégation.
     */
    public void rebuildAlbumsFromSongs() {
        Map<Long, Album> savedAlbums = new HashMap<>();
        for (Album album : albumDao.getAllSync()) {
            if (album != null) {
                savedAlbums.put(album.id, album);
            }
        }
        rebuildAlbumsFromSongs(savedAlbums);
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
                 if (force) {
                     android.util.Log.d("CoverDebug", "resolveAlbumCover id=" + album.id
                             + " force=" + force + " cover=" + album.cover
                             + " release=" + album.releaseDate
                             + " onlineEnabled=" + isOnlineCoverEnabled());
                 }
                 if (!isOnlineCoverEnabled()) return;
                  String currentCover = album.cover != null ? album.cover.trim() : "";
                  String currentReleaseDate = album.releaseDate != null ? album.releaseDate.trim() : "";
                  
                  // Si la pochette est déjà une URI locale (content:// ou file://), ne pas chercher en ligne
                  boolean hasLocalCover = !currentCover.isEmpty() && (currentCover.startsWith("content://") || currentCover.startsWith("file://"));
                  // force=true est déclenché par un échec de chargement Glide (la pochette locale
                  // content:// est cassée/absente) : dans ce cas on DOIT chercher en ligne même si
                  // l'URI actuelle ressemble à une pochette locale.
                  boolean needsCoverLookup = force || (!hasLocalCover && currentCover.isEmpty());

                  boolean needsReleaseDateLookup = currentReleaseDate.isEmpty();
                  if (!needsCoverLookup && !needsReleaseDateLookup) {
                     return;
                  }

                  if (needsCoverLookup) {
                      String remoteCover = coverArtFetcher.fetchAlbumCover(album.artist, album.name);
                      if (remoteCover != null && !remoteCover.isEmpty()) {
                          albumDao.updateCover(album.id, remoteCover);
                      } else if (force || currentCover.isEmpty() || NO_REMOTE_COVER.equals(currentCover)) {
                          // Aucun résultat distant : on mémorise l'absence (sentinel) pour éviter de
                          // retenter en boucle, y compris quand la pochette locale (content://) est
                          // cassée et a échoué côté Glide (force=true).
                          albumDao.updateCover(album.id, NO_REMOTE_COVER);
                      }
                  }

                  if (needsReleaseDateLookup) {
                      String remoteReleaseDate = coverArtFetcher.fetchAlbumReleaseDate(album.artist, album.name);
                      if (remoteReleaseDate != null && !remoteReleaseDate.trim().isEmpty()) {
                          albumDao.updateReleaseDate(album.id, remoteReleaseDate.trim());
                      }
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
                       String currentCover = album.cover != null ? album.cover.trim() : "";
                       
                       // Si la pochette est déjà une URI locale, ne pas chercher en ligne
                       boolean hasLocalCover = !currentCover.isEmpty() && (currentCover.startsWith("content://") || currentCover.startsWith("file://"));
                       
                       boolean needsCoverLookup = !hasLocalCover && (currentCover.isEmpty() || NO_REMOTE_COVER.equals(currentCover));
                       if (needsCoverLookup) {
                           String remoteCover = coverArtFetcher.fetchAlbumCover(album.artist, album.name);
                           if (remoteCover != null && !remoteCover.isEmpty()) {
                               albumDao.updateCover(album.id, remoteCover);
                           } else {
                               // Toujours rien trouve : on (re)pose le sentinel pour eviter de
                               // retenter a chaque affichage de la liste.
                               albumDao.updateCover(album.id, NO_REMOTE_COVER);
                           }
                       }

                       if (album.releaseDate == null || album.releaseDate.trim().isEmpty()) {
                           String remoteReleaseDate = coverArtFetcher.fetchAlbumReleaseDate(album.artist, album.name);
                           if (remoteReleaseDate != null && !remoteReleaseDate.trim().isEmpty()) {
                               albumDao.updateReleaseDate(album.id, remoteReleaseDate.trim());
                           }
                       }
                  }
              } finally {
                  if (onDone != null) onDone.run();
              }
          });
      }

    /**
     * Réinitialise les pochettes marquées "introuvables" (sentinel) afin qu'elles
     * soient re-cherchées UNE seule fois lors du prochain affichage de la liste.
     * À appeler une fois au démarrage de l'application.
     */
    public void retryMissingCoversOnStartup() {
        executor.execute(() -> {
            try {
                int cleared = albumDao.clearNoRemoteCoverSentinel(NO_REMOTE_COVER);
                android.util.Log.d("MusicRepository",
                        "Startup retry: cleared " + cleared + " no-remote-cover sentinels");
            } catch (Exception e) {
                android.util.Log.w("MusicRepository", "Unable to clear cover sentinels on startup", e);
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
          ensureDefaultLocalSourceSeeded();
         // 2. Scan MediaStore.
         MediaStoreScanner.ScanResult result = MediaStoreScanner.scan(context, buildActiveSourceRoots());
         songDao.deleteBySource(Song.SOURCE_LOCAL);
         songDao.insertAll(result.songs);
          rebuildAlbumsFromSongs();
     }

     private void rebuildAlbumsFromSongs(Map<Long, Album> savedAlbums) {
         Map<String, Album> savedAlbumsBySignature = new HashMap<>();
         if (savedAlbums != null && !savedAlbums.isEmpty()) {
             for (Album saved : savedAlbums.values()) {
                 if (saved == null) continue;
                 String signature = albumSignature(saved.artist, saved.name);
                 if (signature != null && !signature.isEmpty()) {
                     savedAlbumsBySignature.put(signature, saved);
                 }
             }
         }

         Set<Long> enabledDriveSourceIds = new HashSet<>();
         for (FolderSource source : folderSourceDao.getAllSync()) {
             if (source != null && source.enabled && isDriveTreeUri(source.treeUri) && source.id > 0L) {
                 enabledDriveSourceIds.add(source.id);
             }
         }

         Map<Long, Album> albumMap = new HashMap<>();
         for (Song song : songDao.getAll()) {
             if (song == null) continue;
             if (Song.SOURCE_DRIVE.equals(song.source) && !enabledDriveSourceIds.contains(song.folderSourceId)) {
                 continue;
             }

             Album album = albumMap.get(song.albumId);
              int songSourceType = Song.SOURCE_DRIVE.equals(song.source) ? Album.SOURCE_DRIVE : Album.SOURCE_LOCAL;
             if (album == null) {
                 album = new Album();
                 album.id = song.albumId;
                 album.name = song.album != null && !song.album.trim().isEmpty() ? song.album.trim() : "Unknown";
                 album.artist = song.artist != null && !song.artist.trim().isEmpty() ? song.artist.trim() : null;
                 album.cover = song.cover != null && !song.cover.trim().isEmpty() ? song.cover.trim() : null;
                 album.releaseDate = song.releaseDate != null && !song.releaseDate.trim().isEmpty()
                         ? song.releaseDate.trim()
                         : null;
                 album.count = 0;
                  album.sourceType = songSourceType;
                 albumMap.put(album.id, album);
             } else {
                 if ((album.artist == null || album.artist.trim().isEmpty())
                         && song.artist != null && !song.artist.trim().isEmpty()) {
                     album.artist = song.artist.trim();
                 }
                 if ((album.cover == null || album.cover.trim().isEmpty())
                         && song.cover != null && !song.cover.trim().isEmpty()) {
                     album.cover = song.cover.trim();
                 }
                 if ((album.releaseDate == null || album.releaseDate.trim().isEmpty())
                         && song.releaseDate != null && !song.releaseDate.trim().isEmpty()) {
                     album.releaseDate = song.releaseDate.trim();
                 }
                 if (album.name == null || album.name.trim().isEmpty()) {
                     album.name = song.album != null && !song.album.trim().isEmpty() ? song.album.trim() : "Unknown";
                 }
                  if (album.sourceType != songSourceType) {
                      album.sourceType = Album.SOURCE_MIXED;
                  }
             }
             album.count++;
         }

         for (Album album : albumMap.values()) {
             Album saved = savedAlbums != null ? savedAlbums.get(album.id) : null;
             if (saved == null && !savedAlbumsBySignature.isEmpty()) {
                 saved = savedAlbumsBySignature.get(albumSignature(album.artist, album.name));
             }
             if (saved != null) {
                 if (shouldUseSavedCover(album.cover, saved.cover)) {
                     album.cover = saved.cover != null ? saved.cover.trim() : null;
                 }
                 // Préserver la date de sortie de l'album sauvegardé
                 if ((album.releaseDate == null || album.releaseDate.trim().isEmpty())
                         && saved.releaseDate != null && !saved.releaseDate.trim().isEmpty()) {
                     album.releaseDate = saved.releaseDate;
                 }
             }
         }

         List<Album> rebuiltAlbums = new ArrayList<>(albumMap.values());
         rebuiltAlbums.sort(Comparator
                 .comparing((Album album) -> album.artist != null ? album.artist : "", String.CASE_INSENSITIVE_ORDER)
                 .thenComparing(album -> album.name != null ? album.name : "", String.CASE_INSENSITIVE_ORDER));

         albumDao.clear();
         if (!rebuiltAlbums.isEmpty()) {
             albumDao.insertAll(rebuiltAlbums);
         }
     }

     private List<MediaStoreScanner.SourceRoot> buildActiveSourceRoots() {
         List<MediaStoreScanner.SourceRoot> roots = new ArrayList<>();

         for (FolderSource source : folderSourceDao.getAllSync()) {
             if (source == null || !source.enabled || source.id <= 0L) continue;
              String absolutePath = isLocalMusicSource(source.treeUri)
                      ? Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC).getAbsolutePath()
                      : treeUriToAbsolutePath(source.treeUri);
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

     private boolean shouldRescanLocalLibrary(FolderSource source) {
          return source != null && (isLocalMusicSource(source.treeUri) || !isDriveTreeUri(source.treeUri));
     }

     private boolean isDriveTreeUri(String treeUri) {
         return treeUri != null && treeUri.startsWith(DRIVE_SOURCE_PREFIX);
     }

      private boolean isLocalMusicSource(String treeUri) {
          return treeUri != null && treeUri.equals(LOCAL_SOURCE_TREE_URI);
      }

      private String albumSignature(String artist, String albumName) {
          String normalizedArtist = normalizeText(artist);
          String normalizedAlbum = normalizeText(albumName);
          if (normalizedAlbum.isEmpty()) return "";
          return normalizedArtist + "||" + normalizedAlbum;
      }

      private String normalizeText(String value) {
          if (value == null) return "";
          return value.trim().toLowerCase();
      }

        private boolean shouldUseSavedCover(String currentCover, String savedCover) {
            String current = currentCover != null ? currentCover.trim() : "";
            String saved = savedCover != null ? savedCover.trim() : "";
            if (saved.isEmpty()) return false;

            // Preserve already resolved remote covers across album rebuilds.
            if (isHttpCover(saved) && !isHttpCover(current)) return true;

            // Preserve the "no remote cover" sentinel to avoid repeated remote lookups
            // when local content:// albumart keeps failing.
            if (NO_REMOTE_COVER.equals(saved) && (current.isEmpty() || isContentCover(current))) return true;

            return current.isEmpty();
        }

        private boolean isHttpCover(String cover) {
            return cover != null && cover.trim().startsWith("http");
        }

        private boolean isContentCover(String cover) {
            return cover != null && cover.trim().startsWith("content://");
        }
}

