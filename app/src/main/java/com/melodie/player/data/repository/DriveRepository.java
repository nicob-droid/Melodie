package com.melodie.player.data.repository;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaMetadataRetriever;
import android.util.Log;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.melodie.player.data.db.DriveAudioDao;
import com.melodie.player.data.db.DriveFolderDao;
import com.melodie.player.data.db.DriveSyncStateDao;
import com.melodie.player.data.db.FolderSourceDao;
import com.melodie.player.data.db.SongDao;
import com.melodie.player.data.cover.CoverArtFetcher.DiscogsTrackInfo;
import com.melodie.player.data.cover.CoverArtFetcher;
import com.melodie.player.data.entity.DriveAudio;
import com.melodie.player.data.entity.DriveFolder;
import com.melodie.player.data.entity.DriveSyncState;
import com.melodie.player.data.entity.FolderSource;
import com.melodie.player.data.entity.Song;
import com.google.android.gms.auth.GoogleAuthUtil;
import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.api.Scope;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import com.google.api.services.drive.model.Change;
import com.google.api.services.drive.model.ChangeList;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;
import com.google.api.services.drive.model.StartPageToken;

import java.io.IOException;
import java.io.OutputStream;
import java.io.FileOutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.hilt.android.qualifiers.ApplicationContext;

@Singleton
public class DriveRepository {

    private static final String TAG = "DriveRepository";
    private static final String DRIVE_SOURCE_PREFIX = "drive://folder/";
    private static final String DRIVE_SYNC_TAG = "DriveSync";
    private static final String DRIVE_AUDIO_CACHE_DIR = "drive_audio_cache";
    private static final String PREFS_DRIVE_AUTH = "drive_auth";
    private static final String KEY_DRIVE_ACCESS_TOKEN = "drive_access_token";
    private static final String SYNC_KEY_START_PAGE_TOKEN = "sync_start_page_token";
    private static final String SYNC_KEY_SELECTION_SIGNATURE = "sync_selection_signature";

    // Extraction des durées : nombre d'extractions réseau menées en parallèle.
    // Chaque extraction ne fait qu'une petite requête HTTP Range (16 Ko, keep-alive) ;
    // concurrence modérée pour rester rapide sans se faire throttler par Google Drive.
    private static final int DURATION_ENRICH_THREADS = 8;

    private final Context context;
    private final DriveFolderDao driveFolderDao;
    private final DriveAudioDao driveAudioDao;
    private final DriveSyncStateDao driveSyncStateDao;
    private final FolderSourceDao folderSourceDao;
    private final SongDao songDao;
    private final MusicRepository musicRepository;
    private final CoverArtFetcher coverArtFetcher;
    private final ExecutorService executor;
    private final SharedPreferences drivePrefs;
    private final MutableLiveData<String> authStatus = new MutableLiveData<>("LOGGED_OUT");
    private final MutableLiveData<Boolean> isLoading = new MutableLiveData<>(false);
    private final MutableLiveData<Boolean> isSyncing = new MutableLiveData<>(false);

    // Année en tête d'un libellé d'album : "[2000] ...", "(2000) ...", "2000 - ...".
    private static final Pattern LEADING_YEAR_PATTERN = Pattern.compile(
            "^\\s*(?:\\[\\s*((?:19|20)\\d{2})\\s*\\]|\\(\\s*((?:19|20)\\d{2})\\s*\\)|((?:19|20)\\d{2})\\s*-)");

    private Drive driveService;
    private volatile String driveAccessToken;
    private volatile boolean durationEnrichmentRunning = false;
    private GoogleSignInClient googleSignInClient;
    private volatile boolean listFoldersInProgress = false;

    @Inject
    public DriveRepository(@ApplicationContext Context context,
                          DriveFolderDao driveFolderDao,
                          DriveAudioDao driveAudioDao,
                          DriveSyncStateDao driveSyncStateDao,
                          FolderSourceDao folderSourceDao,
                          SongDao songDao,
                          MusicRepository musicRepository,
                          CoverArtFetcher coverArtFetcher,
                          ExecutorService executor) {
        this.context = context;
        this.driveFolderDao = driveFolderDao;
        this.driveAudioDao = driveAudioDao;
        this.driveSyncStateDao = driveSyncStateDao;
        this.folderSourceDao = folderSourceDao;
        this.songDao = songDao;
        this.musicRepository = musicRepository;
        this.coverArtFetcher = coverArtFetcher;
        this.executor = executor;
        this.drivePrefs = context.getSharedPreferences(PREFS_DRIVE_AUTH, Context.MODE_PRIVATE);
        initializeGoogleSignIn();
        restorePersistedSession();
        refreshSessionSilentlyIfPossible();
    }

    private void bootstrapDriveSync(Map<DriveFolder, String> foldersToSync,
                                    Map<String, FolderSource> sourceByRootDriveId,
                                    String selectionSignature) throws IOException {
        Map<String, Long> knownDurationsBySongId = new HashMap<>();
        List<Song> previousDriveSongs = songDao.getBySourceSync(Song.SOURCE_DRIVE);
        if (previousDriveSongs != null) {
            for (Song existing : previousDriveSongs) {
                if (existing == null || existing.id == null || existing.id.trim().isEmpty()) continue;
                if (existing.duration > 0L) {
                    knownDurationsBySongId.put(existing.id, existing.duration);
                }
            }
        }

        List<Song> driveSongs = new ArrayList<>();
        long scanStartMs = System.currentTimeMillis();
        int processedFolders = 0;
        int foldersWithAudio = 0;
        int durationFromDrive = 0;
        int durationMissing = 0;
        driveAudioDao.clear();
        for (Map.Entry<DriveFolder, String> entry : foldersToSync.entrySet()) {
            long folderStartMs = System.currentTimeMillis();
            DriveFolder folder = entry.getKey();
            String rootDriveId = entry.getValue();
            FolderSource source = rootDriveId != null ? sourceByRootDriveId.get(rootDriveId) : null;
            if (source == null || folder == null) {
                continue;
            }
            List<DriveAudio> audioFiles = syncAudioFilesFromFolder(folder.driveId);
            processedFolders++;
            if (audioFiles == null || audioFiles.isEmpty()) {
                Log.d(DRIVE_SYNC_TAG,
                        "FOLDER_SYNC folder=" + folder.driveId
                                + " files=0"
                                + " ms=" + (System.currentTimeMillis() - folderStartMs));
                continue;
            }
            foldersWithAudio++;

            for (DriveAudio audio : audioFiles) {
                Song song = buildDriveSong(folder, source, audio);
                if (song == null) continue;
                Long knownDuration = knownDurationsBySongId.get(song.id);
                if (knownDuration != null && knownDuration > 0L && song.duration <= 0L) {
                    song.duration = knownDuration;
                }
                if (song.duration > 0L) {
                    durationFromDrive++;
                } else {
                    durationMissing++;
                }
                driveSongs.add(song);
            }

            Log.d(DRIVE_SYNC_TAG,
                    "FOLDER_SYNC folder=" + folder.driveId
                            + " files=" + audioFiles.size()
                            + " ms=" + (System.currentTimeMillis() - folderStartMs));
        }

        // Remplacement atomique côté chansons Drive pour garantir un état cohérent après bootstrap.
        songDao.deleteBySource(Song.SOURCE_DRIVE);
        if (!driveSongs.isEmpty()) {
            long upsertStartMs = System.currentTimeMillis();
            songDao.insertAll(driveSongs);
            Log.d(DRIVE_SYNC_TAG,
                    "UPSERT_BATCH type=songs size=" + driveSongs.size()
                            + " ms=" + (System.currentTimeMillis() - upsertStartMs));
        }
        musicRepository.rebuildAlbumsFromSongs();

        String newStartPageToken = fetchDriveStartPageToken();
        if (newStartPageToken != null && !newStartPageToken.trim().isEmpty()) {
            putSyncState(SYNC_KEY_START_PAGE_TOKEN, newStartPageToken.trim());
            Log.d(DRIVE_SYNC_TAG, "CURSOR_SAVE tokenLength=" + newStartPageToken.trim().length());
        }
        putSyncState(SYNC_KEY_SELECTION_SIGNATURE, selectionSignature);

        long elapsed = System.currentTimeMillis() - scanStartMs;
        Log.d(DRIVE_SYNC_TAG,
                "SYNC_END mode=bootstrap folders=" + processedFolders
                        + " foldersWithAudio=" + foldersWithAudio
                        + " songs=" + driveSongs.size()
                        + " durationDrive=" + durationFromDrive
                        + " durationMissing=" + durationMissing
                        + " totalMs=" + elapsed);
    }

    private void incrementalDriveSync(Map<DriveFolder, String> foldersToSync,
                                      Map<String, FolderSource> sourceByRootDriveId,
                                      String startPageToken,
                                      String selectionSignature) throws IOException {
        Map<String, DriveFolder> trackedFolderById = new HashMap<>();
        Map<String, String> rootByFolderId = new HashMap<>();
        for (Map.Entry<DriveFolder, String> entry : foldersToSync.entrySet()) {
            DriveFolder folder = entry.getKey();
            String rootDriveId = entry.getValue();
            if (folder == null || folder.driveId == null) continue;
            String folderId = folder.driveId.trim();
            if (folderId.isEmpty()) continue;
            trackedFolderById.put(folderId, folder);
            if (rootDriveId != null && !rootDriveId.trim().isEmpty()) {
                rootByFolderId.put(folderId, rootDriveId.trim());
            }
        }

        if (trackedFolderById.isEmpty()) {
            Log.w(DRIVE_SYNC_TAG, "SYNC_END mode=incremental reason=no_tracked_folders");
            return;
        }

        SyncCounters counters = new SyncCounters();
        String pageToken = startPageToken;
        String newStartPageToken = null;
        long syncStartMs = System.currentTimeMillis();
        int pageIndex = 0;

        do {
            long pageStartMs = System.currentTimeMillis();
            int beforeUpdated = counters.updated;
            int beforeDeleted = counters.deleted;
            int beforeIgnored = counters.ignored;
            ChangeList page = driveService.changes().list(pageToken)
                    .setSupportsAllDrives(true)
                    .setIncludeItemsFromAllDrives(true)
                    .setPageSize(1000)
                    .setFields("nextPageToken,newStartPageToken,changes(fileId,removed,file(id,name,mimeType,modifiedTime,size,parents,md5Checksum,trashed,webContentLink))")
                    .execute();

            List<Change> changes = page != null ? page.getChanges() : null;
            int changeCount = changes != null ? changes.size() : 0;
            long pageMs = System.currentTimeMillis() - pageStartMs;
            Log.d(DRIVE_SYNC_TAG,
                    "CHANGES_PAGE page=" + pageIndex
                            + " changes=" + changeCount
                            + " updated=" + (counters.updated - beforeUpdated)
                            + " deleted=" + (counters.deleted - beforeDeleted)
                            + " ignored=" + (counters.ignored - beforeIgnored)
                            + " ms=" + pageMs);

            if (changes != null) {
                for (Change change : changes) {
                    applyDriveChange(change, trackedFolderById, rootByFolderId, sourceByRootDriveId, counters);
                }
            }

            if (page != null && page.getNewStartPageToken() != null && !page.getNewStartPageToken().trim().isEmpty()) {
                newStartPageToken = page.getNewStartPageToken().trim();
            }

            pageToken = page != null ? page.getNextPageToken() : null;
            pageIndex++;
        } while (pageToken != null && !pageToken.isEmpty());

        if (newStartPageToken != null && !newStartPageToken.isEmpty()) {
            putSyncState(SYNC_KEY_START_PAGE_TOKEN, newStartPageToken);
        }
        putSyncState(SYNC_KEY_SELECTION_SIGNATURE, selectionSignature);

        if (counters.updated > 0 || counters.deleted > 0) {
            musicRepository.rebuildAlbumsFromSongs();
        }

        long totalMs = System.currentTimeMillis() - syncStartMs;
        Log.d(DRIVE_SYNC_TAG,
                "SYNC_END mode=incremental totalMs=" + totalMs
                        + " updated=" + counters.updated
                        + " deleted=" + counters.deleted
                        + " durationDrive=" + counters.durationFromDrive
                        + " durationMissing=" + counters.durationMissing
                        + " ignored=" + counters.ignored);
    }

    private void applyDriveChange(Change change,
                                  Map<String, DriveFolder> trackedFolderById,
                                  Map<String, String> rootByFolderId,
                                  Map<String, FolderSource> sourceByRootDriveId,
                                  SyncCounters counters) {
        if (change == null || change.getFileId() == null || change.getFileId().trim().isEmpty()) {
            counters.ignored++;
            return;
        }

        String fileId = change.getFileId().trim();
        if (change.getRemoved() != null && change.getRemoved()) {
            removeDriveFile(fileId);
            counters.deleted++;
            return;
        }

        File file = change.getFile();
        if (file == null || Boolean.TRUE.equals(file.getTrashed())) {
            removeDriveFile(fileId);
            counters.deleted++;
            return;
        }

        String trackedFolderId = findTrackedFolderId(file, trackedFolderById.keySet());
        if (trackedFolderId == null) {
            // Le fichier existe mais ne fait plus partie de l'arborescence sélectionnée.
            removeDriveFile(fileId);
            counters.deleted++;
            return;
        }

        if (!isAudioMimeType(file.getMimeType())) {
            // Delta non audio dans un dossier suivi: pas de chanson locale à créer/mettre à jour.
            counters.ignored++;
            return;
        }

        DriveFolder folder = trackedFolderById.get(trackedFolderId);
        String rootDriveId = rootByFolderId.get(trackedFolderId);
        FolderSource source = rootDriveId != null ? sourceByRootDriveId.get(rootDriveId) : null;
        if (folder == null || source == null) {
            counters.ignored++;
            return;
        }

        DriveAudio previous = driveAudioDao.getById(fileId);
        DriveAudio audio = mapDriveFileToAudio(file, trackedFolderId, previous);
        driveAudioDao.insert(audio);

        Song song = buildDriveSong(folder, source, audio);
        if (song != null) {
            songDao.insert(song);
            counters.updated++;
            if (song.duration > 0L) {
                counters.durationFromDrive++;
            } else {
                counters.durationMissing++;
            }
        } else {
            counters.ignored++;
        }
    }

    private DriveAudio mapDriveFileToAudio(File file, String folderId, DriveAudio previous) {
        DriveAudio audio = new DriveAudio();
        audio.fileId = file.getId() != null ? file.getId() : "";
        audio.fileName = file.getName() != null ? file.getName() : "";
        audio.folderId = folderId != null ? folderId : "";
        audio.fileSize = file.getSize() != null ? file.getSize() : 0L;
        audio.lastModified = file.getModifiedTime() != null ? file.getModifiedTime().getValue() : 0L;
        audio.durationMs = 0L;
        audio.trackNumber = 0;
        Object musicMetaRaw = file.get("musicMetadata");
        if (!(musicMetaRaw instanceof Map)) {
            // Compatibilite defensive avec d'anciens payloads/tests.
            musicMetaRaw = file.get("audioMediaMetadata");
        }
        if (musicMetaRaw instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> musicMeta = (Map<String, Object>) musicMetaRaw;
            audio.durationMs = parseLongValue(musicMeta.get("durationMillis"));
            audio.trackNumber = (int) parseLongValue(musicMeta.get("trackNumber"));
        }
        audio.webContentLink = file.getWebContentLink() != null ? file.getWebContentLink() : "";
        audio.downloaded = previous != null && canReuseCachedFile(previous, audio);
        audio.localPath = audio.downloaded && previous != null ? previous.localPath : "";
        return audio;
    }

    private void removeDriveFile(String fileId) {
        driveAudioDao.deleteById(fileId);
        songDao.deleteById("D_" + fileId);
    }

    private String findTrackedFolderId(File file, Set<String> trackedFolderIds) {
        if (file == null || trackedFolderIds == null || trackedFolderIds.isEmpty()) return null;
        List<String> parents = file.getParents();
        if (parents == null || parents.isEmpty()) return null;
        for (String parent : parents) {
            if (parent != null && trackedFolderIds.contains(parent)) {
                return parent;
            }
        }
        return null;
    }

    private boolean isAudioMimeType(String mimeType) {
        if (mimeType == null || mimeType.trim().isEmpty()) return false;
        String m = mimeType.trim().toLowerCase();
        return m.startsWith("audio/")
                || "application/ogg".equals(m)
                || "application/x-flac".equals(m);
    }

    private String fetchDriveStartPageToken() throws IOException {
        StartPageToken token = driveService.changes().getStartPageToken()
                .setSupportsAllDrives(true)
                .execute();
        return token != null ? token.getStartPageToken() : null;
    }

    private String buildSelectionSignature(Set<String> rootDriveIds) {
        if (rootDriveIds == null || rootDriveIds.isEmpty()) return "";
        List<String> sorted = new ArrayList<>(rootDriveIds);
        Collections.sort(sorted);
        return String.join("|", sorted);
    }

    private String getSyncState(String key) {
        DriveSyncState state = driveSyncStateDao.get(key);
        if (state == null || state.value == null) return null;
        return state.value;
    }

    private void putSyncState(String key, String value) {
        if (key == null || key.trim().isEmpty()) return;
        DriveSyncState state = new DriveSyncState();
        state.key = key.trim();
        state.value = value != null ? value : "";
        state.updatedAt = System.currentTimeMillis();
        driveSyncStateDao.upsert(state);
    }

    private static final class SyncCounters {
        int updated;
        int deleted;
        int ignored;
        int durationFromDrive;
        int durationMissing;
    }

    private void initializeGoogleSignIn() {
        GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestScopes(new Scope(DriveScopes.DRIVE_READONLY))
                .requestEmail()
                .build();
        googleSignInClient = GoogleSignIn.getClient(context, gso);
    }

    public LiveData<String> getAuthStatus() {
        return authStatus;
    }

    public LiveData<Boolean> getIsLoading() {
        return isLoading;
    }

    public LiveData<Boolean> getIsSyncing() {
        return isSyncing;
    }

    public GoogleSignInClient getGoogleSignInClient() {
        return googleSignInClient;
    }

    public void setDriveService(Drive drive) {
        this.driveService = drive;
        authStatus.postValue("LOGGED_IN");
    }

    public void setDriveAccessToken(String accessToken) {
        if (accessToken == null || accessToken.trim().isEmpty()) {
            return;
        }
        this.driveAccessToken = accessToken.trim();
        drivePrefs.edit().putString(KEY_DRIVE_ACCESS_TOKEN, this.driveAccessToken).apply();
    }

    public String getDriveAccessToken() {
        return driveAccessToken;
    }

    public boolean isLoggedIn() {
        return driveService != null;
    }

    public void listFoldersFromDrive(Runnable onDone) {
        executor.execute(() -> {
            try {
                if (driveService == null) {
                    Log.w(TAG, "Drive service is null, cannot list folders");
                    if (onDone != null) onDone.run();
                    return;
                }

                // Évite les doubles appels parallèles (ex: restauration de session + LOGGED_IN observer)
                synchronized (DriveRepository.this) {
                    if (listFoldersInProgress) {
                        Log.d(TAG, "listFoldersFromDrive already in progress, skipping duplicate call");
                        if (onDone != null) onDone.run();
                        return;
                    }
                    listFoldersInProgress = true;
                }

                Log.d(TAG, "Starting to list Drive folders...");
                isLoading.postValue(true);

                // Root de Mon Drive (ancre de filtrage d'ascendance)
                String rootId = driveService.files()
                        .get("root")
                        .setSupportsAllDrives(false)
                        .setFields("id")
                        .execute()
                        .getId();

                // Charge tous les dossiers accessibles. Le classement Mon Drive / Lecteurs partagés
                // est fait ensuite à partir de l'ascendance et du driveId.
                String query = "mimeType='application/vnd.google-apps.folder' and trashed=false";
                List<File> files = new ArrayList<>();
                String pageToken = null;
                do {
                    Drive.Files.List request = driveService.files().list()
                            .setQ(query)
                            .setSpaces("drive")
                            .setCorpora("allDrives")
                            .setSupportsAllDrives(true)
                            .setIncludeItemsFromAllDrives(true)
                            .setFields("nextPageToken,files(id,name,parents,modifiedTime,driveId)")
                            .setPageSize(1000);
                    if (pageToken != null && !pageToken.isEmpty()) {
                        request.setPageToken(pageToken);
                    }
                    FileList page = request.execute();
                    if (page.getFiles() != null) {
                        files.addAll(page.getFiles());
                    }
                    pageToken = page.getNextPageToken();
                } while (pageToken != null && !pageToken.isEmpty());

                Map<String, String> parentById = new HashMap<>();
                for (File f : files) {
                    String pid = (f.getParents() != null && !f.getParents().isEmpty())
                            ? f.getParents().get(0)
                            : "";
                    parentById.put(f.getId(), pid);
                }

                Set<String> knownIds = new HashSet<>(parentById.keySet());

                // Conserve les dossiers de Mon Drive dont l'ascendance remonte jusqu'au root de Mon Drive.
                List<File> myDriveFiles = new ArrayList<>();
                List<File> sharedDriveFiles = new ArrayList<>();
                for (File file : files) {
                    if (isUnderMyDriveRoot(file.getId(), parentById, rootId)) {
                        myDriveFiles.add(file);
                    } else if (isSharedDriveFolder(file, parentById, rootId, knownIds)) {
                        sharedDriveFiles.add(file);
                    }
                }

                Log.d(TAG, "Found " + myDriveFiles.size() + " My Drive folders and "
                        + sharedDriveFiles.size() + " shared-drive folders");

                // Ne vide la table que si l'API a retourné au moins un dossier, pour ne pas
                // effacer la liste si la réponse est vide suite à un problème temporaire.
                if (!myDriveFiles.isEmpty() || !sharedDriveFiles.isEmpty()) {
                    driveFolderDao.deleteAll();
                } else {
                    Log.w(TAG, "Drive API returned 0 folders – skipping deleteAll to preserve cached list");
                }

                for (File file : myDriveFiles) {
                    DriveFolder folder = new DriveFolder();
                    folder.driveId = file.getId();
                    folder.name = file.getName();
                    String parentId = parentById.getOrDefault(file.getId(), "");
                    folder.parentDriveId = parentId;
                    folder.isSharedDrive = false;
                    folder.lastSync = System.currentTimeMillis();
                    driveFolderDao.insert(folder);
                    Log.d(TAG, "Added My Drive folder: " + folder.name + " parent=" + parentId);
                }

                for (File file : sharedDriveFiles) {
                    DriveFolder folder = new DriveFolder();
                    folder.driveId = file.getId();
                    folder.name = file.getName();
                    String parentId = parentById.getOrDefault(file.getId(), "");
                    folder.parentDriveId = parentId;
                    folder.isSharedDrive = true;
                    folder.lastSync = System.currentTimeMillis();
                    driveFolderDao.insert(folder);
                    Log.d(TAG, "Added Shared Drive folder: " + folder.name + " parent=" + parentId);
                }

            } catch (Exception e) {
                Log.e(TAG, "Error while listing Drive folders", e);
            } finally {
                isLoading.postValue(false);
                synchronized (DriveRepository.this) {
                    listFoldersInProgress = false;
                }
                if (onDone != null) onDone.run();
            }
        });
    }

    private boolean isUnderMyDriveRoot(String fileId,
                                       Map<String, String> parentById,
                                       String rootId) {
        if (rootId == null || rootId.isEmpty() || fileId == null || fileId.isEmpty()) {
            return false;
        }

        String current = fileId;
        Set<String> visited = new HashSet<>();
        while (current != null && !current.isEmpty() && !visited.contains(current)) {
            visited.add(current);
            String parentId = parentById.get(current);

            if (rootId.equals(parentId)) {
                return true;
            }

            // Si on perd la chaîne des parents avant root, le dossier n'est pas sous Mon Drive.
            if (parentId == null || parentId.isEmpty() || !parentById.containsKey(parentId)) {
                return false;
            }

            current = parentId;
        }

        return false;
    }

    public void listAudioFilesFromFolder(String folderId, Runnable onDone) {
        executor.execute(() -> {
            try {
                if (driveService == null) {
                    if (onDone != null) onDone.run();
                    return;
                }

                isLoading.postValue(true);

                syncAudioFilesFromFolder(folderId);

                isLoading.postValue(false);
            } catch (IOException e) {
                handleDriveApiError(e, "Error listing audio files");
                isLoading.postValue(false);
            } finally {
                if (onDone != null) onDone.run();
            }
        });
    }

    public void syncSelectedFolders(Runnable onDone) {
        executor.execute(() -> {
            try {
                isSyncing.postValue(true);
                isLoading.postValue(true);
                if (driveService == null) {
                    Log.w(TAG, "Drive service is null, skipping selected folders sync");
                    return;
                }
                List<DriveFolder> selectedFolders = driveFolderDao.getSelected();
                if (selectedFolders == null) {
                    selectedFolders = new ArrayList<>();
                }
                if (selectedFolders.isEmpty()) {
                    Log.w(TAG, "No selected Drive folders, skipping sync to avoid wiping existing Drive library");
                    return;
                }

                Map<String, DriveFolder> selectedById = indexFoldersByDriveId(selectedFolders);

                // Les dossiers cochés visibles dans l'écran Drive deviennent de vraies sources persistées.
                // Les descendants héritent de la source de leur racine sélectionnée, sans apparaître séparément.
                Map<String, FolderSource> sourceByRootDriveId = persistDriveFolderSources(selectedFolders, selectedById);

                // Expansion récursive (comme Spiral Player) : cocher un dossier synchronise aussi
                // TOUS ses sous-dossiers descendants, en héritant de la source du dossier coché.
                Map<DriveFolder, String> foldersToSync = expandSelectedFoldersWithDescendants(selectedFolders, selectedById);
                Log.d(TAG, "Sync targets after recursive expansion: " + foldersToSync.size()
                        + " folders (from " + selectedFolders.size() + " selected)");

                String currentSelectionSignature = buildSelectionSignature(sourceByRootDriveId.keySet());
                String storedSelectionSignature = getSyncState(SYNC_KEY_SELECTION_SIGNATURE);
                String startPageToken = getSyncState(SYNC_KEY_START_PAGE_TOKEN);

                boolean forceBootstrap = startPageToken == null
                        || startPageToken.trim().isEmpty()
                        || !currentSelectionSignature.equals(storedSelectionSignature);

                Log.d(DRIVE_SYNC_TAG,
                        "SYNC_DECISION forceBootstrap=" + forceBootstrap
                                + " tokenPresent=" + (startPageToken != null && !startPageToken.trim().isEmpty())
                                + " selectionChanged=" + !currentSelectionSignature.equals(storedSelectionSignature)
                                + " roots=" + sourceByRootDriveId.size());

                if (forceBootstrap) {
                    Log.d(DRIVE_SYNC_TAG, "SYNC_START mode=bootstrap roots=" + sourceByRootDriveId.size());
                    bootstrapDriveSync(foldersToSync, sourceByRootDriveId, currentSelectionSignature);
                } else {
                    Log.d(DRIVE_SYNC_TAG, "SYNC_START mode=incremental roots=" + sourceByRootDriveId.size());
                    try {
                        incrementalDriveSync(foldersToSync, sourceByRootDriveId, startPageToken, currentSelectionSignature);
                    } catch (GoogleJsonResponseException jsonException) {
                        // Token invalide (ex: 410 Gone): on repart proprement en bootstrap.
                        Log.w(DRIVE_SYNC_TAG,
                                "Incremental cursor invalid, fallback to bootstrap: HTTP "
                                        + jsonException.getStatusCode());
                        bootstrapDriveSync(foldersToSync, sourceByRootDriveId, currentSelectionSignature);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error syncing folders", e);
            } finally {
                isLoading.postValue(false);
                isSyncing.postValue(false);
                if (onDone != null) onDone.run();
            }

            // Enrichissement des durées en tâche de fond, une fois la sync terminée.
            // On lit la durée directement dans l'en-tête du fichier audio via streaming
            // authentifié (comme Spiral Player) : aucun téléchargement complet, l'UI se
            // met à jour progressivement grâce à Room.
            enrichDriveDurations();
        });
    }

    /**
     * Renseigne proactivement la durée des morceaux Google Drive dont la durée est encore
     * inconnue (0). Chaque durée est décodée depuis l'en-tête du conteneur (quelques Ko lus
     * via requêtes HTTP Range, voir {@link TrackDurationProbe}), en parallèle. La table
     * albums est reconstruite à la fin si au moins une durée a été récupérée.
     */
    private void enrichDriveDurations() {
        final String token = driveAccessToken;
        if (token == null || token.trim().isEmpty()) {
            Log.w(TAG, "Skipping duration enrichment: no Drive access token");
            return;
        }

        synchronized (this) {
            if (durationEnrichmentRunning) {
                Log.d(TAG, "Duration enrichment already running, skipping duplicate run");
                return;
            }
            durationEnrichmentRunning = true;
        }

        // Instantané des morceaux sans durée : on itère dessus sans re-requêter, ce qui évite
        // toute boucle infinie sur les fichiers dont l'extraction échoue.
        final List<Song> pending = songDao.getDriveSongsWithUnknownDurationSync(Integer.MAX_VALUE);
        if (pending == null || pending.isEmpty()) {
            synchronized (this) { durationEnrichmentRunning = false; }
            return;
        }

        final int total = pending.size();
        Log.d(TAG, "Duration enrichment: " + total + " Drive songs (parallel x" + DURATION_ENRICH_THREADS + ")");

        // Pool dédié : on ne bloque pas l'executor de sync et on traite plusieurs fichiers
        // en parallèle pour masquer la latence réseau de chaque extraction.
        final ExecutorService pool = Executors.newFixedThreadPool(DURATION_ENRICH_THREADS);
        final AtomicInteger remaining = new AtomicInteger(total);
        final AtomicInteger updated = new AtomicInteger(0);
        final AtomicInteger failed = new AtomicInteger(0);
        final AtomicLong totalProbeMs = new AtomicLong(0L);
        final List<Long> probeLatenciesMs = Collections.synchronizedList(new ArrayList<>());

        for (Song song : pending) {
            final Song s = song;
            pool.execute(() -> {
                try {
                    if (s == null || s.id == null) return;
                    String fileId = extractDriveFileIdFromSong(s);
                    if (fileId == null) return;

                    DriveAudio audio = driveAudioDao.getById(fileId);
                    String fileName = audio != null ? audio.fileName : s.title;
                    long fileSize = audio != null ? audio.fileSize : 0L;

                    long probeStartMs = System.currentTimeMillis();
                    long durationMs = extractDriveDuration(fileId, fileName, fileSize, token);
                    long probeMs = System.currentTimeMillis() - probeStartMs;
                    totalProbeMs.addAndGet(probeMs);
                    probeLatenciesMs.add(probeMs);

                    if (durationMs > 0L) {
                        songDao.updateDuration(s.id, durationMs);
                        if (audio != null) {
                            audio.durationMs = durationMs;
                            driveAudioDao.update(audio);
                        }
                        updated.incrementAndGet();
                    } else {
                        failed.incrementAndGet();
                    }
                } catch (Exception e) {
                    failed.incrementAndGet();
                    Log.w(TAG, "Duration task failed: " + e.getMessage());
                } finally {
                    if (remaining.decrementAndGet() == 0) {
                        int done = updated.get();
                        long avgProbeMs = total > 0 ? totalProbeMs.get() / Math.max(total, 1) : 0L;
                        long p95ProbeMs = percentile95(probeLatenciesMs);
                        Log.d(DRIVE_SYNC_TAG,
                                "DURATION_FALLBACK count=" + total
                                        + " updated=" + done
                                        + " failed=" + failed.get()
                                        + " avgMs=" + avgProbeMs
                                        + " p95Ms=" + p95ProbeMs);
                        if (done > 0) {
                            musicRepository.rebuildAlbumsFromSongs();
                        }
                        pool.shutdown();
                        synchronized (DriveRepository.this) { durationEnrichmentRunning = false; }
                    }
                }
            });
        }
    }

    private long percentile95(List<Long> values) {
        if (values == null || values.isEmpty()) return 0L;
        List<Long> copy;
        synchronized (values) {
            copy = new ArrayList<>(values);
        }
        Collections.sort(copy);
        int index = (int) Math.ceil(copy.size() * 0.95d) - 1;
        if (index < 0) index = 0;
        if (index >= copy.size()) index = copy.size() - 1;
        return copy.get(index);
    }

    private String extractDriveFileIdFromSong(Song song) {
        if (song == null) return null;
        if (song.path != null) {
            String p = song.path.trim();
            String prefix = "drive://file/";
            if (p.startsWith(prefix)) {
                String id = p.substring(prefix.length()).trim();
                if (!id.isEmpty()) return id;
            }
        }
        // Fallback: l'id d'une Song Drive est de la forme "D_<fileId>".
        if (song.id != null && song.id.startsWith("D_")) {
            String id = song.id.substring(2).trim();
            if (!id.isEmpty()) return id;
        }
        return null;
    }

    /**
     * Lit la durée d'un fichier audio Drive sans le télécharger : MediaMetadataRetriever
     * n'accède qu'aux octets d'en-tête nécessaires via le endpoint de streaming Drive
     * (alt=media) avec l'en-tête Authorization: Bearer.
     */
    /**
     * Lit la durée d'un fichier audio Drive en ne récupérant que quelques Ko d'en-tête
     * (une ou deux requêtes HTTP Range) et en décodant le conteneur. Rapide et sans
     * MediaMetadataRetriever (qui streame le média et est bien trop lent sur des URL HTTP).
     */
    private long extractDriveDuration(String fileId, String fileName, long fileSize, String accessToken) {
        if (fileId == null || fileId.trim().isEmpty()) return 0L;
        String url = "https://www.googleapis.com/drive/v3/files/" + fileId.trim() + "?alt=media";
        return TrackDurationProbe.probeDurationMs(url, "Bearer " + accessToken, fileName, fileSize);
    }

    private List<DriveAudio> syncAudioFilesFromFolder(String folderId) throws IOException {
        List<DriveAudio> audioList = new ArrayList<>();
        if (driveService == null) return audioList;

        Map<String, DriveAudio> existingById = new HashMap<>();
        List<DriveAudio> existing = driveAudioDao.getByFolderSync(folderId);
        if (existing != null) {
            for (DriveAudio prev : existing) {
                if (prev != null && prev.fileId != null && !prev.fileId.trim().isEmpty()) {
                    existingById.put(prev.fileId, prev);
                }
            }
        }

        // Utilise "contains" pour couvrir tous les sous-types audio (audio/mpeg, audio/mp4,
        // audio/flac, audio/ogg, audio/wav, audio/aac, audio/opus…).
        // "audio/m4a" n'existe pas sur Drive : les fichiers .m4a ont le type "audio/mp4".
        // On ajoute application/ogg et application/x-flac pour les cas edge (cohérent avec isAudioMimeType).
        String query = "'" + folderId + "' in parents and " +
                "(mimeType contains 'audio/' or mimeType = 'application/ogg' or mimeType = 'application/x-flac') " +
                "and trashed=false";

        Log.d(DRIVE_SYNC_TAG, "AUDIO_QUERY folder=" + folderId + " q=" + query);

        // Les champs audioMediaMetadata/musicMetadata ne sont pas garantis en Drive v3.
        // On limite la requête aux champs stables et on enrichit la durée ensuite si besoin.
        // Pagination complète (comme Spiral Player) : on suit nextPageToken jusqu'à épuisement
        // pour ne PAS s'arrêter aux 1000 premiers fichiers d'un dossier volumineux.
        List<File> files = new ArrayList<>();
        String pageToken = null;
        int pageIndex = 0;
        do {
            long pageStartMs = System.currentTimeMillis();
            Drive.Files.List request = driveService.files().list()
                    .setQ(query)
                    .setSpaces("drive")
                    .setCorpora("allDrives")
                    .setSupportsAllDrives(true)
                    .setIncludeItemsFromAllDrives(true)
                    .setFields("nextPageToken,files(id,name,mimeType,modifiedTime,size,parents,md5Checksum,trashed,webContentLink)")
                    .setPageSize(1000);
            if (pageToken != null && !pageToken.isEmpty()) {
                request.setPageToken(pageToken);
            }
            FileList result = request.execute();
            List<File> pageFiles = result.getFiles();
            int pageSize = pageFiles != null ? pageFiles.size() : 0;
            long pageMs = System.currentTimeMillis() - pageStartMs;
            Log.d(DRIVE_SYNC_TAG,
                    "LIST_PAGE folder=" + folderId
                            + " page=" + pageIndex
                            + " files=" + pageSize
                            + " ms=" + pageMs);
            if (pageFiles != null && !pageFiles.isEmpty()) {
                files.addAll(pageFiles);
            }
            pageToken = result.getNextPageToken();
            pageIndex++;
        } while (pageToken != null && !pageToken.isEmpty());

        Log.d(TAG, "Folder " + folderId + ": fetched " + files.size() + " audio files (paginated)");

        // Diagnostic : si 0 fichiers audio trouvés, on requête TOUS les fichiers du dossier
        // (sans filtre MIME) pour savoir si le dossier est vide ou si c'est un problème de type.
        if (files.isEmpty()) {
            try {
                String diagQuery = "'" + folderId + "' in parents and trashed=false";
                FileList diagResult = driveService.files().list()
                        .setQ(diagQuery)
                        .setSpaces("drive")
                        .setCorpora("allDrives")
                        .setSupportsAllDrives(true)
                        .setIncludeItemsFromAllDrives(true)
                        .setFields("files(id,name,mimeType)")
                        .setPageSize(20)
                        .execute();
                List<File> diagFiles = diagResult.getFiles();
                if (diagFiles == null || diagFiles.isEmpty()) {
                    Log.w(DRIVE_SYNC_TAG, "FOLDER_EMPTY_DIAG folder=" + folderId + " totalFiles=0 → dossier vraiment vide");
                } else {
                    StringBuilder sb = new StringBuilder();
                    for (File f : diagFiles) {
                        sb.append(f.getName()).append("(").append(f.getMimeType()).append(") ");
                    }
                    Log.w(DRIVE_SYNC_TAG, "FOLDER_EMPTY_DIAG folder=" + folderId
                            + " totalFiles=" + diagFiles.size()
                            + " → MIME types présents: " + sb);
                }
            } catch (Exception diagEx) {
                Log.w(DRIVE_SYNC_TAG, "FOLDER_EMPTY_DIAG failed: " + diagEx.getMessage());
            }
        }

        driveAudioDao.deleteByFolder(folderId);

        if (!files.isEmpty()) {
            for (File file : files) {
                DriveAudio audio = new DriveAudio();
                audio.fileId = file.getId();
                audio.fileName = file.getName();
                audio.folderId = folderId;
                audio.fileSize = file.getSize() != null ? file.getSize() : 0;
                audio.lastModified = file.getModifiedTime() != null ? file.getModifiedTime().getValue() : 0;
                audio.durationMs = 0L;
                audio.trackNumber = 0;
                Object musicMetaRaw = file.get("musicMetadata");
                if (!(musicMetaRaw instanceof Map)) {
                    // Compatibilite defensive avec d'anciens payloads/tests.
                    musicMetaRaw = file.get("audioMediaMetadata");
                }
                if (musicMetaRaw instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> musicMeta = (Map<String, Object>) musicMetaRaw;
                    audio.durationMs = parseLongValue(musicMeta.get("durationMillis"));
                    audio.trackNumber = (int) parseLongValue(musicMeta.get("trackNumber"));
                }
                audio.webContentLink = file.getWebContentLink() != null ? file.getWebContentLink() : "";
                audio.downloaded = false;
                audio.localPath = "";

                DriveAudio prev = existingById.get(audio.fileId);
                if (prev != null && canReuseCachedFile(prev, audio)) {
                    audio.downloaded = true;
                    audio.localPath = prev.localPath;
                }

                audioList.add(audio);
            }
            driveAudioDao.insertAll(audioList);
        }

        return audioList;
    }

    private Song buildDriveSong(DriveFolder folder, FolderSource source, DriveAudio audio) {
        if (audio == null || audio.fileId == null || audio.fileId.trim().isEmpty()) return null;

        String sourceName = source != null && source.displayName != null && !source.displayName.trim().isEmpty()
                ? source.displayName.trim()
                : (folder != null && folder.name != null && !folder.name.trim().isEmpty() ? folder.name.trim() : "Google Drive");
        String folderName = folder != null ? folder.name : null;
        String baseName = stripExtension(audio.fileName);
        TrackMetadata metadata = parseTrackMetadata(baseName, folderName, sourceName);

        Song song = new Song();
        song.id = "D_" + audio.fileId;
        song.title = metadata.title;
        song.artist = metadata.artist != null && !metadata.artist.trim().isEmpty() ? metadata.artist.trim() : sourceName;
        song.album = metadata.album != null && !metadata.album.trim().isEmpty() ? metadata.album.trim() : sourceName;
        song.albumId = toDriveLogicalAlbumId(source != null ? source.id : 0L, song.artist, song.album);
        song.trackNumber = audio.trackNumber > 0 ? audio.trackNumber : (metadata.trackNumber > 0 ? metadata.trackNumber : 0);
        song.duration = audio.durationMs > 0L ? audio.durationMs : 0L;
        // Date de sortie extraite du nom de dossier ("[2000] Album"), null si absente :
        // le rebuild d'albums et le repli en ligne géreront le cas échéant.
        song.releaseDate = metadata.year != null && !metadata.year.trim().isEmpty()
                ? metadata.year.trim()
                : null;
        // Streaming direct: la resolution vers Drive API (alt=media) est faite dans PlaybackService.
        song.path = "drive://file/" + audio.fileId;
        song.source = Song.SOURCE_DRIVE;
        song.folderSourceId = source != null ? source.id : 0L;
        song.cover = null;
        song.favorite = false;
        song.dateAdded = audio.lastModified > 0L ? audio.lastModified : System.currentTimeMillis();
        return song;
    }

    private TrackMetadata parseTrackMetadata(String baseName, String fallbackFolderName, String fallbackSourceName) {
        String normalizedBase = baseName != null ? baseName.trim() : "";
        AlbumContext folderContext = parseAlbumContext(fallbackFolderName);
        AlbumContext sourceContext = parseAlbumContext(fallbackSourceName);

        // When the folder already identifies an artist, trust the folder entirely.
        // We only need to extract the track number and title from the filename.
        boolean hasFolderArtist = folderContext.artist != null && !folderContext.artist.trim().isEmpty();

        String artist = null;
        String title = normalizedBase;
        int trackNumber = 0;

        int dashIndex = normalizedBase.indexOf(" - ");
        if (dashIndex > 0 && dashIndex < normalizedBase.length() - 3) {
            String left = normalizedBase.substring(0, dashIndex).trim();
            String right = normalizedBase.substring(dashIndex + 3).trim();

            if (!hasFolderArtist && isLikelyArtist(left)) {
                // Filename explicitly names an artist and folder doesn't know one: trust filename.
                artist = left;
                title = right;
            } else if (isTrackNumberToken(left)) {
                // Pure numeric token: "01", "2", etc.
                trackNumber = parseTrackNumber(left);
                title = right;
            } else if (isTrackNumberPrefix(left)) {
                // Prefix like "01.", "01 ", "A1", "A1.", "B2 " — extract number, keep right as title.
                trackNumber = extractLeadingTrackNumber(left);
                title = right;
            }
            // Otherwise (hasFolderArtist and left looks like an artist name):
            // we ignore the left part and handle title/trackNumber below.
        }

        if (trackNumber <= 0) {
            trackNumber = extractLeadingTrackNumber(normalizedBase);
        }

        title = stripLeadingTrackNumber(title);

        if (title == null || title.isEmpty()) {
            title = folderContext.album != null && !folderContext.album.trim().isEmpty()
                    ? folderContext.album.trim()
                    : "Unknown";
        }

        if (artist == null || artist.trim().isEmpty()) {
            artist = firstNonEmpty(folderContext.artist, sourceContext.artist);
        }

        String album = firstNonEmpty(folderContext.album, sourceContext.album);

        TrackMetadata metadata = new TrackMetadata();
        metadata.artist = artist;
        metadata.title = title;
        metadata.album = album;
        metadata.year = firstNonEmpty(folderContext.year, sourceContext.year);
        metadata.trackNumber = trackNumber;
        return metadata;
    }

    /**
     * Returns true if the token looks like a track-number prefix:
     *   "01.", "01 ", "1.", "A1", "A1.", "B2 ", "C3-", etc.
     * These are NOT valid artist names.
     */
    private boolean isTrackNumberPrefix(String token) {
        if (token == null || token.isEmpty()) return false;
        String v = token.trim();
        // Numeric prefix (with or without separator): "01", "01.", "1 ", "12-"
        if (v.matches("^\\d{1,3}[.\\s-]?$")) return true;
        if (v.matches("^\\d{1,3}[.\\s-].*")) return true;
        // Vinyl-style side+track: "A1", "B2", "A1.", "B2 ", etc.
        if (v.matches("(?i)^[A-F]\\d{1,2}[.\\s-]?$")) return true;
        if (v.matches("(?i)^[A-F]\\d{1,2}[.\\s-].*")) return true;
        return false;
    }

    private AlbumContext parseAlbumContext(String rawName) {
        AlbumContext context = new AlbumContext();
        if (rawName == null || rawName.trim().isEmpty()) {
            return context;
        }

        String normalized = rawName.trim();
        int dashIndex = normalized.indexOf(" - ");
        if (dashIndex > 0 && dashIndex < normalized.length() - 3) {
            String left = normalized.substring(0, dashIndex).trim();
            String right = normalized.substring(dashIndex + 3).trim();
            if (isLikelyArtist(left)) {
                context.artist = left;
                context.year = extractLeadingYear(right);
                context.album = normalizeAlbumLabel(right);
                return context;
            }
        }

        context.year = extractLeadingYear(normalized);
        context.album = normalizeAlbumLabel(normalized);
        return context;
    }

    /**
     * Extrait l'année en tête d'un libellé d'album : "[2000] Album", "(2000) Album",
     * "2000 - Album". Retourne null si aucune année en tête n'est détectée.
     */
    private String extractLeadingYear(String value) {
        if (value == null) return null;
        Matcher matcher = LEADING_YEAR_PATTERN.matcher(value.trim());
        if (matcher.find()) {
            for (int group = 1; group <= 3; group++) {
                String candidate = matcher.group(group);
                if (candidate != null && !candidate.isEmpty()) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private String normalizeAlbumLabel(String value) {
        if (value == null) return null;
        String v = value.trim();
        if (v.isEmpty()) return null;

        // Remove common year prefixes: [2004] Album, (2004) Album, 2004 - Album
        v = v.replaceFirst("^\\[(19|20)\\d{2}\\]\\s*", "");
        v = v.replaceFirst("^\\((19|20)\\d{2}\\)\\s*", "");
        v = v.replaceFirst("^(19|20)\\d{2}\\s*-\\s*", "");
        v = v.trim();

        return v.isEmpty() ? null : v;
    }

    private String stripLeadingTrackNumber(String value) {
        if (value == null) return null;
        String v = value.trim();
        if (v.isEmpty()) return v;

        v = v.replaceFirst("^(?i)track\\s*\\d{1,3}\\s*[-_.)]\\s*", "");
        v = v.replaceFirst("^\\d{1,3}\\s*[-_.)]\\s*", "");
        v = v.replaceFirst("^\\d{1,3}\\s+", "");
        return v.trim();
    }

    private boolean isTrackNumberToken(String token) {
        if (token == null) return false;
        String t = token.trim();
        if (t.isEmpty() || t.length() > 3) return false;
        for (int i = 0; i < t.length(); i++) {
            if (!Character.isDigit(t.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private int parseTrackNumber(String token) {
        try {
            return Integer.parseInt(token.trim());
        } catch (Exception ignored) {
            return 0;
        }
    }

    private int extractLeadingTrackNumber(String value) {
        if (value == null) return 0;
        String v = value.trim();
        if (v.isEmpty()) return 0;

        int i = 0;
        while (i < v.length() && Character.isDigit(v.charAt(i)) && i < 3) {
            i++;
        }
        if (i == 0) return 0;

        // Accepte 01, 01-, 01., 01_, 01) ou 01 espace
        if (i < v.length()) {
            char sep = v.charAt(i);
            if (!(sep == ' ' || sep == '-' || sep == '_' || sep == '.' || sep == ')')) {
                return 0;
            }
        }

        try {
            return Integer.parseInt(v.substring(0, i));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private boolean isLikelyArtist(String value) {
        if (value == null) return false;
        String v = value.trim();
        if (v.isEmpty() || isTrackNumberToken(v)) return false;
        // Reject track-number prefixes: "01.", "01 Nada Surf", "A1", "B2 something"
        if (isTrackNumberPrefix(v)) return false;

        boolean hasLetter = false;
        for (int i = 0; i < v.length(); i++) {
            if (Character.isLetter(v.charAt(i))) {
                hasLetter = true;
                break;
            }
        }
        return hasLetter;
    }

    private String firstNonEmpty(String a, String b) {
        if (a != null && !a.trim().isEmpty()) return a.trim();
        if (b != null && !b.trim().isEmpty()) return b.trim();
        return null;
    }

    private String stripExtension(String fileName) {
        if (fileName == null) return "";
        int dot = fileName.lastIndexOf('.');
        if (dot > 0) {
            return fileName.substring(0, dot).trim();
        }
        return fileName.trim();
    }

    private long toDriveLogicalAlbumId(long folderSourceId, String artist, String album) {
        String key = folderSourceId + "||" + normalize(artist) + "||" + normalize(album);
        long hash = 1469598103934665603L;
        for (int i = 0; i < key.length(); i++) {
            hash ^= key.charAt(i);
            hash *= 1099511628211L;
        }
        if (hash == Long.MIN_VALUE) return 0L;
        return Math.abs(hash);
    }

    private String normalize(String value) {
        if (value == null) return "";
        return value.trim().toLowerCase();
    }

    private long parseLongValue(Object raw) {
        if (raw == null) return 0L;
        if (raw instanceof Number) {
            return ((Number) raw).longValue();
        }
        try {
            return Long.parseLong(String.valueOf(raw).trim());
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private List<DiscogsTrackInfo> fetchDiscogsDurationsForFolder(DriveFolder folder, FolderSource source) {
        if (coverArtFetcher == null) return null;

        String folderName = folder != null ? folder.name : null;
        String sourceName = source != null ? source.displayName : null;
        AlbumContext folderContext = parseAlbumContext(folderName);
        AlbumContext sourceContext = parseAlbumContext(sourceName);

        String artist = firstNonEmpty(folderContext.artist, sourceContext.artist);
        String album = firstNonEmpty(folderContext.album, sourceContext.album);
        if ((artist == null || artist.isEmpty()) && (album == null || album.isEmpty())) {
            return null;
        }

        return coverArtFetcher.fetchDiscogsTrackInfos(artist != null ? artist : "", album != null ? album : "");
    }

    private void applyDiscogsDurationIfMissing(Song song, DriveAudio audio, List<DiscogsTrackInfo> discogsTracks, int sequentialIndex) {
        if (song == null || audio == null || discogsTracks == null || discogsTracks.isEmpty()) return;
        if (song.duration > 0L) return;

        long duration = 0L;
        if (audio.trackNumber > 0) {
            int index = audio.trackNumber - 1;
            if (index >= 0 && index < discogsTracks.size()) {
                DiscogsTrackInfo info = discogsTracks.get(index);
                duration = info != null ? info.durationMs : 0L;
            }
        }

        if (duration <= 0L) {
            String songTitle = normalizeTrackText(song.title);
            for (DiscogsTrackInfo info : discogsTracks) {
                if (info == null || info.durationMs <= 0L) continue;
                String discogsTitle = normalizeTrackText(info.title);
                if (!songTitle.isEmpty() && !discogsTitle.isEmpty() && titlesLookEquivalent(songTitle, discogsTitle)) {
                    duration = info.durationMs;
                    break;
                }
            }
        }

        if (duration <= 0L && sequentialIndex >= 0 && sequentialIndex < discogsTracks.size()) {
            DiscogsTrackInfo info = discogsTracks.get(sequentialIndex);
            duration = info != null ? info.durationMs : 0L;
        }

        if (duration > 0L) {
            song.duration = duration;
        }
    }

    private String normalizeTrackText(String value) {
        if (value == null) return "";
        String normalized = value.trim().toLowerCase();
        normalized = normalized.replaceAll("\\[[^\\]]*\\]", " ");
        normalized = normalized.replaceAll("\\([^\\)]*\\)", " ");
        normalized = normalized.replaceAll("[^a-z0-9]+", " ");
        return normalized.replaceAll("\\s+", " ").trim();
    }

    private boolean titlesLookEquivalent(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) return false;
        return a.equals(b) || a.contains(b) || b.contains(a);
    }

    private boolean canReuseCachedFile(DriveAudio previous, DriveAudio current) {
        if (previous == null || current == null) return false;
        if (!previous.downloaded) return false;
        if (previous.localPath == null || previous.localPath.trim().isEmpty()) return false;

        java.io.File local = new java.io.File(previous.localPath.trim());
        if (!local.exists() || !local.isFile() || local.length() <= 0L) return false;

        return previous.lastModified == current.lastModified && previous.fileSize == current.fileSize;
    }

    private String downloadDriveAudioToCache(String fileId, String fileName, String mimeType) {
        if (driveService == null || fileId == null || fileId.trim().isEmpty()) return null;
        try {
            java.io.File cacheDir = new java.io.File(context.getFilesDir(), DRIVE_AUDIO_CACHE_DIR);
            if (!cacheDir.exists() && !cacheDir.mkdirs()) {
                Log.w(TAG, "Cannot create Drive cache dir: " + cacheDir.getAbsolutePath());
                return null;
            }

            String extension = resolveExtension(fileName, mimeType);
            String safeName = sanitizeFileName(fileName);
            java.io.File target = new java.io.File(cacheDir, fileId + "_" + safeName + extension);
            java.io.File temp = new java.io.File(cacheDir, fileId + ".tmp");

            if (temp.exists()) {
                // Nettoie un ancien telechargement interrompu.
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
            }

            try (OutputStream out = new FileOutputStream(temp)) {
                driveService.files().get(fileId).executeMediaAndDownloadTo(out);
            }

            if (target.exists()) {
                //noinspection ResultOfMethodCallIgnored
                target.delete();
            }

            if (!temp.renameTo(target)) {
                Log.w(TAG, "Cannot move temp Drive file to target for id=" + fileId);
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
                return null;
            }

            return target.getAbsolutePath();
        } catch (Exception e) {
            Log.e(TAG, "Failed to download Drive audio file id=" + fileId + " name=" + fileName, e);
            return null;
        }
    }

    private long extractDurationMs(String path) {
        if (path == null || path.trim().isEmpty()) return 0L;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(path.trim());
            String value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (value == null || value.trim().isEmpty()) return 0L;
            return Long.parseLong(value.trim());
        } catch (Exception e) {
            Log.w(TAG, "Cannot extract duration for Drive file: " + path, e);
            return 0L;
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
                // ignore
            }
        }
    }

    private String sanitizeFileName(String fileName) {
        String base = fileName != null ? fileName.trim() : "";
        if (base.isEmpty()) return "track";
        return base.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private String resolveExtension(String fileName, String mimeType) {
        if (fileName != null) {
            int dot = fileName.lastIndexOf('.');
            if (dot >= 0 && dot < fileName.length() - 1) {
                return "";
            }
        }

        if ("audio/mpeg".equalsIgnoreCase(mimeType)) return ".mp3";
        if ("audio/flac".equalsIgnoreCase(mimeType)) return ".flac";
        if ("audio/wav".equalsIgnoreCase(mimeType) || "audio/x-wav".equalsIgnoreCase(mimeType)) return ".wav";
        if ("audio/ogg".equalsIgnoreCase(mimeType)) return ".ogg";
        if ("audio/m4a".equalsIgnoreCase(mimeType) || "audio/mp4".equalsIgnoreCase(mimeType)) return ".m4a";
        return "";
    }

    private static class TrackMetadata {
        String artist;
        String title;
        String album;
        String year;
        int trackNumber;
    }

    private static class AlbumContext {
        String artist;
        String album;
        String year;
    }

    public LiveData<List<DriveAudio>> getAudioFilesFromFolder(String folderId) {
        return driveAudioDao.observeByFolder(folderId);
    }

    public LiveData<List<DriveAudio>> getDownloadedAudioFiles() {
        return driveAudioDao.observeDownloaded();
    }

    public LiveData<List<DriveFolder>> observeDriveFolders() {
        return driveFolderDao.observeAll();
    }

    public void toggleFolderSelection(DriveFolder folder) {
        executor.execute(() -> driveFolderDao.update(folder));
    }

    public void setFolderSelections(List<DriveFolder> folders) {
        executor.execute(() -> {
            for (DriveFolder f : folders) {
                driveFolderDao.update(f);
            }
        });
    }

    /**
     * Resynchronise les sources Google Drive déjà ajoutées :
     *   1) rafraîchit la liste des dossiers (découvre les nouveaux sous-dossiers),
     *   2) reconstruit la sélection à partir des sources persistées (folder_sources),
     *      en cochant aussi les nouveaux sous-dossiers,
     *   3) relance la synchronisation des fichiers.
     * Nécessaire car le flag `selected` est éphémère (réinitialisé à chaque re-listing),
     * et {@link #syncAudioFilesFromFolder} n'est pas récursif : un nouveau sous-dossier
     * n'est jamais synchronisé tant qu'il n'est pas explicitement sélectionné.
     */
    public void resyncExistingDriveSources(Runnable onDone) {
        if (driveService == null) {
            Log.w(TAG, "Drive service is null, skipping resync of existing sources");
            if (onDone != null) onDone.run();
            return;
        }
        // Le re-listing réinitialise les `selected` : on reconstruit ensuite la sélection.
        listFoldersFromDrive(() -> executor.execute(() -> {
            try {
                selectFoldersUnderPersistedDriveSources();
            } catch (Exception e) {
                Log.e(TAG, "Error rebuilding Drive selection before resync", e);
            }
            syncSelectedFolders(onDone);
        }));
    }

    /**
     * Coche (selected=1) tous les dossiers Drive appartenant à une source déjà
     * persistée (folder_sources "drive://folder/&lt;rootId&gt;"), y compris les
     * sous-dossiers nouvellement ajoutés.
     */
    private void selectFoldersUnderPersistedDriveSources() {
        List<FolderSource> sources = folderSourceDao.getAllSync();
        Set<String> rootDriveIds = new HashSet<>();
        if (sources != null) {
            for (FolderSource s : sources) {
                if (s == null || s.treeUri == null) continue;
                if (s.treeUri.startsWith(DRIVE_SOURCE_PREFIX)) {
                    String rootId = s.treeUri.substring(DRIVE_SOURCE_PREFIX.length()).trim();
                    if (!rootId.isEmpty()) rootDriveIds.add(rootId);
                }
            }
        }
        if (rootDriveIds.isEmpty()) {
            Log.d(TAG, "No persisted Drive sources, nothing to reselect");
            return;
        }

        List<DriveFolder> all = driveFolderDao.getAllSync();
        if (all == null || all.isEmpty()) return;

        Map<String, DriveFolder> byId = new HashMap<>();
        for (DriveFolder f : all) {
            if (f != null && f.driveId != null && !f.driveId.trim().isEmpty()) {
                byId.put(f.driveId.trim(), f);
            }
        }

        int reselected = 0;
        for (DriveFolder f : all) {
            if (f == null) continue;
            if (isUnderPersistedRoot(f, byId, rootDriveIds)) {
                if (!f.selected) {
                    f.selected = true;
                    driveFolderDao.update(f);
                    reselected++;
                }
            }
        }
        Log.d(TAG, "Reselected " + reselected + " Drive folder(s) under existing sources");
    }

    /**
     * Remonte la chaîne des parents jusqu'à trouver (ou non) un dossier racine
     * correspondant à une source Drive persistée.
     */
    private boolean isUnderPersistedRoot(DriveFolder folder,
                                         Map<String, DriveFolder> byId,
                                         Set<String> rootDriveIds) {
        if (folder == null || folder.driveId == null) return false;
        Set<String> visited = new HashSet<>();
        DriveFolder current = folder;
        while (current != null) {
            String id = current.driveId != null ? current.driveId.trim() : "";
            if (id.isEmpty() || !visited.add(id)) return false;
            if (rootDriveIds.contains(id)) return true;
            String parentId = current.parentDriveId != null ? current.parentDriveId.trim() : "";
            if (parentId.isEmpty()) return false;
            if (rootDriveIds.contains(parentId)) return true;
            current = byId.get(parentId);
        }
        return false;
    }

    private Map<String, FolderSource> persistDriveFolderSources(List<DriveFolder> folders,
                                                                Map<String, DriveFolder> selectedById) {
        Map<String, FolderSource> sourceByRootDriveId = new HashMap<>();
        if (folders == null || folders.isEmpty()) return sourceByRootDriveId;

        Set<String> rootDriveIds = new HashSet<>();
        for (DriveFolder folder : folders) {
            String rootDriveId = resolveSelectedRootDriveId(folder, selectedById);
            if (rootDriveId != null && !rootDriveId.isEmpty()) {
                rootDriveIds.add(rootDriveId);
            }
        }

        pruneObsoleteDriveFolderSources(rootDriveIds);

        for (String rootDriveId : rootDriveIds) {
            DriveFolder rootFolder = selectedById.get(rootDriveId);
            if (rootFolder == null) continue;
            upsertDriveFolderSource(rootFolder);
            FolderSource source = folderSourceDao.getByTreeUri(DRIVE_SOURCE_PREFIX + rootDriveId);
            if (source != null) {
                sourceByRootDriveId.put(rootDriveId, source);
            }
        }

        return sourceByRootDriveId;
    }

    private Map<String, DriveFolder> indexFoldersByDriveId(List<DriveFolder> folders) {
        Map<String, DriveFolder> result = new HashMap<>();
        if (folders == null) return result;
        for (DriveFolder folder : folders) {
            if (folder == null || folder.driveId == null) continue;
            String driveId = folder.driveId.trim();
            if (!driveId.isEmpty()) {
                result.put(driveId, folder);
            }
        }
        return result;
    }

    /**
     * Étend l'ensemble des dossiers cochés à TOUS leurs descendants (récursivement),
     * à la manière de Spiral Player : cocher un dossier revient à synchroniser toute
     * son arborescence. Chaque dossier (coché ou descendant) est associé au rootDriveId
     * de l'ancêtre coché dont il hérite la source.
     *
     * L'ordre d'insertion est préservé et chaque dossier n'apparaît qu'une seule fois,
     * même si un parent et un de ses enfants sont cochés simultanément.
     */
    private Map<DriveFolder, String> expandSelectedFoldersWithDescendants(
            List<DriveFolder> selectedFolders, Map<String, DriveFolder> selectedById) {
        Map<DriveFolder, String> result = new LinkedHashMap<>();
        if (selectedFolders == null || selectedFolders.isEmpty()) return result;

        // Index de TOUS les dossiers connus + arborescence parent -> enfants.
        List<DriveFolder> allFolders = driveFolderDao.getAllSync();
        Map<String, List<DriveFolder>> childrenByParent = new HashMap<>();
        if (allFolders != null) {
            for (DriveFolder f : allFolders) {
                if (f == null || f.parentDriveId == null) continue;
                String parentId = f.parentDriveId.trim();
                if (parentId.isEmpty()) continue;
                childrenByParent.computeIfAbsent(parentId, k -> new ArrayList<>()).add(f);
            }
        }

        Set<String> visited = new HashSet<>();
        for (DriveFolder selected : selectedFolders) {
            if (selected == null || selected.driveId == null) continue;
            String selectedDriveId = selected.driveId.trim();
            if (selectedDriveId.isEmpty()) continue;

            String rootDriveId = resolveSelectedRootDriveId(selected, selectedById);

            // Parcours en largeur depuis le dossier coché vers tous ses descendants.
            Deque<DriveFolder> queue = new ArrayDeque<>();
            queue.add(selected);
            while (!queue.isEmpty()) {
                DriveFolder current = queue.poll();
                if (current == null || current.driveId == null) continue;
                String currentDriveId = current.driveId.trim();
                if (currentDriveId.isEmpty() || !visited.add(currentDriveId)) continue;

                result.put(current, rootDriveId);

                List<DriveFolder> children = childrenByParent.get(currentDriveId);
                if (children != null) {
                    queue.addAll(children);
                }
            }
        }

        return result;
    }

    private String resolveSelectedRootDriveId(DriveFolder folder, Map<String, DriveFolder> selectedById) {
        if (folder == null || folder.driveId == null) return null;

        String currentDriveId = folder.driveId.trim();
        if (currentDriveId.isEmpty()) return null;

        String rootDriveId = currentDriveId;
        Set<String> visited = new HashSet<>();
        DriveFolder current = folder;

        while (current != null && current.parentDriveId != null) {
            String parentDriveId = current.parentDriveId.trim();
            if (parentDriveId.isEmpty() || !visited.add(parentDriveId)) {
                break;
            }
            DriveFolder parent = selectedById.get(parentDriveId);
            if (parent == null) {
                break;
            }
            rootDriveId = parent.driveId != null && !parent.driveId.trim().isEmpty()
                    ? parent.driveId.trim()
                    : rootDriveId;
            current = parent;
        }

        return rootDriveId;
    }

    private void pruneObsoleteDriveFolderSources(Set<String> rootDriveIds) {
        List<FolderSource> existingSources = folderSourceDao.getAllSync();
        if (existingSources == null || existingSources.isEmpty()) return;

        for (FolderSource source : existingSources) {
            if (source == null || source.treeUri == null) continue;
            if (!source.treeUri.startsWith(DRIVE_SOURCE_PREFIX)) continue;

            String driveId = source.treeUri.substring(DRIVE_SOURCE_PREFIX.length()).trim();
            if (driveId.isEmpty()) continue;

            if (!rootDriveIds.contains(driveId)) {
                folderSourceDao.deleteById(source.id);
            }
        }
    }

    private void upsertDriveFolderSource(DriveFolder folder) {
        if (folder == null || folder.driveId == null || folder.driveId.trim().isEmpty()) return;

        String normalizedId = folder.driveId.trim();
        String treeUri = DRIVE_SOURCE_PREFIX + normalizedId;
        FolderSource existing = folderSourceDao.getByTreeUri(treeUri);

        if (existing != null) {
            if (folder.name != null && !folder.name.trim().isEmpty()) {
                existing.displayName = folder.name.trim();
            }
            existing.enabled = true;
            folderSourceDao.update(existing);
            return;
        }

        FolderSource source = new FolderSource();
        source.displayName = (folder.name != null && !folder.name.trim().isEmpty())
                ? folder.name.trim()
                : "Google Drive";
        source.treeUri = treeUri;
        source.enabled = true;
        source.createdAt = System.currentTimeMillis();
        folderSourceDao.insert(source);
    }

    private boolean isSharedDriveFolder(File file,
                                        Map<String, String> parentById,
                                        String myDriveRootId,
                                        Set<String> knownIds) {
        // Signal le plus fiable quand présent (items de lecteurs partagés)
        if (file.getDriveId() != null && !file.getDriveId().isEmpty()) {
            return true;
        }

        String currentId = file.getId();
        Set<String> visited = new HashSet<>();
        while (currentId != null && !currentId.isEmpty() && !visited.contains(currentId)) {
            visited.add(currentId);
            String parentId = parentById.get(currentId);

            // Sans parent connu, on considère Mon Drive par défaut
            if (parentId == null || parentId.isEmpty()) {
                return false;
            }

            if (!myDriveRootId.isEmpty() && parentId.equals(myDriveRootId)) {
                return false;
            }

            // Parent hors dataset et différent de root => racine de lecteur partagé
            if (!knownIds.contains(parentId)) {
                return true;
            }

            currentId = parentId;
        }

        return false;
    }

    public void logout() {
        googleSignInClient.signOut();
        driveService = null;
        driveAccessToken = null;
        drivePrefs.edit().remove(KEY_DRIVE_ACCESS_TOKEN).apply();
        authStatus.postValue("LOGGED_OUT");
        executor.execute(() -> {
            driveFolderDao.deleteAll();
            driveAudioDao.clear();
            driveSyncStateDao.clear();
            songDao.deleteBySource(Song.SOURCE_DRIVE);
            musicRepository.rebuildAlbumsFromSongs();
        });
    }

    private void handleDriveApiError(IOException e, String context) {
        if (e instanceof GoogleJsonResponseException) {
            GoogleJsonResponseException jsonError = (GoogleJsonResponseException) e;
            int statusCode = jsonError.getStatusCode();
            if (statusCode == 403) {
                authStatus.postValue("DRIVE_API_DISABLED");
                Log.e(TAG, context + " - Google Drive API disabled or forbidden (HTTP 403). "
                        + "Enable drive.googleapis.com in Google Cloud Console.", e);
                return;
            }
            Log.e(TAG, context + " - HTTP " + statusCode, e);
            return;
        }
        Log.e(TAG, context, e);
    }


    private void restorePersistedSession() {
        String token = drivePrefs.getString(KEY_DRIVE_ACCESS_TOKEN, null);
        if (token == null || token.trim().isEmpty()) {
            return;
        }

        setDriveAccessToken(token.trim());
        setDriveService(buildDriveService(token.trim()));
        Log.d(TAG, "Restored persisted Drive session token");
    }

    private void refreshSessionSilentlyIfPossible() {
        GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(context);
        if (account == null || account.getAccount() == null) {
            return;
        }

        executor.execute(() -> {
            try {
                String refreshedToken = GoogleAuthUtil.getToken(
                        context,
                        account.getAccount(),
                        "oauth2:https://www.googleapis.com/auth/drive.readonly"
                );
                if (refreshedToken != null && !refreshedToken.trim().isEmpty()) {
                    setDriveAccessToken(refreshedToken.trim());
                    setDriveService(buildDriveService(refreshedToken.trim()));
                    Log.d(TAG, "Drive session refreshed silently");
                }
            } catch (Exception e) {
                Log.w(TAG, "Silent Drive session refresh failed", e);
            }
        });
    }

    private Drive buildDriveService(String accessToken) {
        HttpRequestInitializer httpRequestInitializer = request -> {
            request.getHeaders().setAuthorization("Bearer " + accessToken);
        };

        return new Drive.Builder(
                new NetHttpTransport(),
                new GsonFactory(),
                httpRequestInitializer
        ).setApplicationName("Melodie").build();
    }
}
