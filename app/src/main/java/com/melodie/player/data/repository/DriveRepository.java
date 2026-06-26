package com.melodie.player.data.repository;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaMetadataRetriever;
import android.util.Log;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.melodie.player.data.db.DriveAudioDao;
import com.melodie.player.data.db.DriveFolderDao;
import com.melodie.player.data.db.FolderSourceDao;
import com.melodie.player.data.db.SongDao;
import com.melodie.player.data.entity.DriveAudio;
import com.melodie.player.data.entity.DriveFolder;
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
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;

import java.io.IOException;
import java.io.OutputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.hilt.android.qualifiers.ApplicationContext;

@Singleton
public class DriveRepository {

    private static final String TAG = "DriveRepository";
    private static final String DRIVE_SOURCE_PREFIX = "drive://folder/";
    private static final String DRIVE_AUDIO_CACHE_DIR = "drive_audio_cache";
    private static final String PREFS_DRIVE_AUTH = "drive_auth";
    private static final String KEY_DRIVE_ACCESS_TOKEN = "drive_access_token";

    private final Context context;
    private final DriveFolderDao driveFolderDao;
    private final DriveAudioDao driveAudioDao;
    private final FolderSourceDao folderSourceDao;
    private final SongDao songDao;
    private final MusicRepository musicRepository;
    private final ExecutorService executor;
    private final SharedPreferences drivePrefs;
    private final MutableLiveData<String> authStatus = new MutableLiveData<>("LOGGED_OUT");
    private final MutableLiveData<Boolean> isLoading = new MutableLiveData<>(false);

    private Drive driveService;
    private volatile String driveAccessToken;
    private GoogleSignInClient googleSignInClient;

    @Inject
    public DriveRepository(@ApplicationContext Context context,
                          DriveFolderDao driveFolderDao,
                          DriveAudioDao driveAudioDao,
                          FolderSourceDao folderSourceDao,
                          SongDao songDao,
                          MusicRepository musicRepository,
                          ExecutorService executor) {
        this.context = context;
        this.driveFolderDao = driveFolderDao;
        this.driveAudioDao = driveAudioDao;
        this.folderSourceDao = folderSourceDao;
        this.songDao = songDao;
        this.musicRepository = musicRepository;
        this.executor = executor;
        this.drivePrefs = context.getSharedPreferences(PREFS_DRIVE_AUTH, Context.MODE_PRIVATE);
        initializeGoogleSignIn();
        restorePersistedSession();
        refreshSessionSilentlyIfPossible();
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

                Log.d(TAG, "Starting to list Drive folders (My Drive only)...");
                isLoading.postValue(true);

                // Root de Mon Drive (ancre de filtrage d'ascendance)
                String rootId = driveService.files()
                        .get("root")
                        .setSupportsAllDrives(false)
                        .setFields("id")
                        .execute()
                        .getId();

                // My Drive strict: dossiers non supprimés dont je suis propriétaire.
                // Cela exclut les dossiers "Partagés avec moi" dans la plupart des cas.
                String query = "mimeType='application/vnd.google-apps.folder' and trashed=false and 'me' in owners";
                List<File> files = new ArrayList<>();
                String pageToken = null;
                do {
                    Drive.Files.List request = driveService.files().list()
                            .setQ(query)
                            .setSpaces("drive")
                            .setCorpora("user")
                            .setSupportsAllDrives(false)
                            .setIncludeItemsFromAllDrives(false)
                            .setFields("nextPageToken,files(id,name,parents,modifiedTime)")
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

                // Conserve uniquement les dossiers dont l'ascendance remonte jusqu'au root de Mon Drive.
                List<File> myDriveFiles = new ArrayList<>();
                for (File file : files) {
                    if (isUnderMyDriveRoot(file.getId(), parentById, rootId)) {
                        myDriveFiles.add(file);
                    }
                }

                Log.d(TAG, "Found " + myDriveFiles.size() + " folders under My Drive root");

                if (!myDriveFiles.isEmpty()) {
                    driveFolderDao.deleteAll();
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
                } else {
                    Log.d(TAG, "No folders found in My Drive");
                    driveFolderDao.deleteAll();
                }

                isLoading.postValue(false);
            } catch (IOException e) {
                handleDriveApiError(e, "IOException while listing folders");
                isLoading.postValue(false);
            } finally {
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
                isLoading.postValue(true);
                if (driveService == null) {
                    Log.w(TAG, "Drive service is null, skipping selected folders sync");
                    return;
                }
                List<DriveFolder> selectedFolders = driveFolderDao.getSelected();
                if (selectedFolders == null) {
                    selectedFolders = new ArrayList<>();
                }

                // Les dossiers cochés dans l'écran Drive deviennent de vraies sources persistées.
                persistDriveFolderSources(selectedFolders);

                // On reconstruit les morceaux Drive à partir de l'état courant pour éviter les doublons
                // et supprimer les entrées des dossiers qui ne sont plus sélectionnés.
                driveAudioDao.clear();
                songDao.deleteBySource(Song.SOURCE_DRIVE);

                List<Song> driveSongs = new ArrayList<>();

                for (DriveFolder folder : selectedFolders) {
                    FolderSource source = folderSourceDao.getByTreeUri(DRIVE_SOURCE_PREFIX + folder.driveId);
                    if (source == null) {
                        upsertDriveFolderSource(folder);
                        source = folderSourceDao.getByTreeUri(DRIVE_SOURCE_PREFIX + folder.driveId);
                    }
                    List<DriveAudio> audioFiles = syncAudioFilesFromFolder(folder.driveId);
                    if (source != null && audioFiles != null && !audioFiles.isEmpty()) {
                        for (DriveAudio audio : audioFiles) {
                            Song song = buildDriveSong(folder, source, audio);
                            if (song != null) {
                                driveSongs.add(song);
                            }
                        }
                    }
                }

                if (!driveSongs.isEmpty()) {
                    songDao.insertAll(driveSongs);
                }

                musicRepository.rebuildAlbumsFromSongs();
            } catch (Exception e) {
                Log.e(TAG, "Error syncing folders", e);
            } finally {
                isLoading.postValue(false);
                if (onDone != null) onDone.run();
            }
        });
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

        String query = "'" + folderId + "' in parents and " +
                "(mimeType='audio/mpeg' or mimeType='audio/wav' or mimeType='audio/ogg' or " +
                "mimeType='audio/flac' or mimeType='audio/m4a') and trashed=false";

        Drive.Files.List request = driveService.files().list()
                .setQ(query)
                .setSpaces("drive")
                .setFields("files(id, name, size, modifiedTime, webContentLink, mimeType)")
                .setPageSize(100);

        FileList result = request.execute();
        List<File> files = result.getFiles();

        driveAudioDao.deleteByFolder(folderId);

        if (files != null && !files.isEmpty()) {
            for (File file : files) {
                DriveAudio audio = new DriveAudio();
                audio.fileId = file.getId();
                audio.fileName = file.getName();
                audio.folderId = folderId;
                audio.fileSize = file.getSize() != null ? file.getSize() : 0;
                audio.lastModified = file.getModifiedTime() != null ? file.getModifiedTime().getValue() : 0;
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
        song.trackNumber = 0;
        song.duration = 0L;
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

        String artist = null;
        String title = normalizedBase;

        int dashIndex = normalizedBase.indexOf(" - ");
        if (dashIndex > 0 && dashIndex < normalizedBase.length() - 3) {
            String left = normalizedBase.substring(0, dashIndex).trim();
            String right = normalizedBase.substring(dashIndex + 3).trim();
            if (isLikelyArtist(left)) {
                artist = left;
                title = right;
            } else if (isTrackNumberToken(left)) {
                title = right;
            }
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
        return metadata;
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
                context.album = normalizeAlbumLabel(right);
                return context;
            }
        }

        context.album = normalizeAlbumLabel(normalized);
        return context;
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

    private boolean isLikelyArtist(String value) {
        if (value == null) return false;
        String v = value.trim();
        if (v.isEmpty() || isTrackNumberToken(v)) return false;

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
    }

    private static class AlbumContext {
        String artist;
        String album;
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
                if (f != null && f.selected) {
                    upsertDriveFolderSource(f);
                }
            }
        });
    }

    private void persistDriveFolderSources(List<DriveFolder> folders) {
        if (folders == null || folders.isEmpty()) return;
        for (DriveFolder folder : folders) {
            upsertDriveFolderSource(folder);
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
