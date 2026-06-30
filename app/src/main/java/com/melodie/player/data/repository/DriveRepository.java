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
import com.melodie.player.data.cover.CoverArtFetcher.DiscogsTrackInfo;
import com.melodie.player.data.cover.CoverArtFetcher;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private GoogleSignInClient googleSignInClient;
    private volatile boolean listFoldersInProgress = false;

    @Inject
    public DriveRepository(@ApplicationContext Context context,
                          DriveFolderDao driveFolderDao,
                          DriveAudioDao driveAudioDao,
                          FolderSourceDao folderSourceDao,
                          SongDao songDao,
                          MusicRepository musicRepository,
                          CoverArtFetcher coverArtFetcher,
                          ExecutorService executor) {
        this.context = context;
        this.driveFolderDao = driveFolderDao;
        this.driveAudioDao = driveAudioDao;
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

                for (DriveFolder folder : selectedFolders) {
                    String rootDriveId = resolveSelectedRootDriveId(folder, selectedById);
                    FolderSource source = rootDriveId != null ? sourceByRootDriveId.get(rootDriveId) : null;
                    if (source == null) {
                        Log.w(TAG, "Skipping Drive folder without resolved root source: " + folder.driveId + " (root=" + rootDriveId + ")");
                        continue;
                    }
                    List<DriveAudio> audioFiles = syncAudioFilesFromFolder(folder.driveId);
                    if (audioFiles != null && !audioFiles.isEmpty()) {
                        // Discogs duration lookup removed from sync path: it causes HTTP 429 storms
                        // when syncing many folders, blocking the entire sync. Durations will be 0
                        // initially (or restored from previous sync cache below).
                        for (DriveAudio audio : audioFiles) {
                            Song song = buildDriveSong(folder, source, audio);
                            if (song != null) {
                                // Restore previously known duration if available
                                Long knownDuration = knownDurationsBySongId.get(song.id);
                                if (knownDuration != null && knownDuration > 0L) {
                                    song.duration = knownDuration;
                                }
                                driveSongs.add(song);
                            }
                        }
                    }
                }

                // On remplace la bibliothèque Drive uniquement après une synchronisation réussie.
                // Cela évite de tout effacer si une requête réseau échoue en cours de route.
                driveAudioDao.clear();
                songDao.deleteBySource(Song.SOURCE_DRIVE);

                if (!driveSongs.isEmpty()) {
                    songDao.insertAll(driveSongs);
                    Log.d(TAG, "Drive sync complete: inserted " + driveSongs.size() + " songs");
                }

                musicRepository.rebuildAlbumsFromSongs();
            } catch (Exception e) {
                Log.e(TAG, "Error syncing folders", e);
            } finally {
                isLoading.postValue(false);
                isSyncing.postValue(false);
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

        // musicMetadata n'est pas supporté sur toutes les versions de l'API Drive.
        // On utilise directement les champs de base pour éviter le double aller-retour systématique.
        FileList result;
        Drive.Files.List request = driveService.files().list()
                .setQ(query)
                .setSpaces("drive")
                .setSupportsAllDrives(true)
                .setIncludeItemsFromAllDrives(true)
                .setFields("files(id,name,size,modifiedTime,webContentLink,mimeType)")
                .setPageSize(1000);
        result = request.execute();
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
