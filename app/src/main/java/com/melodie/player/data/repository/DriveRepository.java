package com.melodie.player.data.repository;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.melodie.player.data.db.DriveAudioDao;
import com.melodie.player.data.db.DriveFolderDao;
import com.melodie.player.data.entity.DriveAudio;
import com.melodie.player.data.entity.DriveFolder;
import com.google.android.gms.auth.api.signin.GoogleSignIn;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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
    private static final String PREFS_NAME = "melodie_drive_prefs";
    private static final String PREF_ACCOUNT = "drive_account";

    private final Context context;
    private final DriveFolderDao driveFolderDao;
    private final DriveAudioDao driveAudioDao;
    private final ExecutorService executor;
    private final SharedPreferences prefs;
    private final MutableLiveData<String> authStatus = new MutableLiveData<>("LOGGED_OUT");
    private final MutableLiveData<Boolean> isLoading = new MutableLiveData<>(false);

    private Drive driveService;
    private GoogleSignInClient googleSignInClient;

    @Inject
    public DriveRepository(@ApplicationContext Context context,
                          DriveFolderDao driveFolderDao,
                          DriveAudioDao driveAudioDao,
                          ExecutorService executor) {
        this.context = context;
        this.driveFolderDao = driveFolderDao;
        this.driveAudioDao = driveAudioDao;
        this.executor = executor;
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        initializeGoogleSignIn();
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

                // Cherche les fichiers audio dans le dossier
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

                // Supprime les anciens fichiers du dossier
                driveAudioDao.deleteByFolder(folderId);

                if (files != null && !files.isEmpty()) {
                    List<DriveAudio> audioList = new ArrayList<>();
                    for (File file : files) {
                        DriveAudio audio = new DriveAudio();
                        audio.fileId = file.getId();
                        audio.fileName = file.getName();
                        audio.folderId = folderId;
                        audio.fileSize = file.getSize() != null ? file.getSize() : 0;
                        audio.lastModified = file.getModifiedTime() != null ? file.getModifiedTime().getValue() : 0;
                        audio.webContentLink = file.getWebContentLink() != null ? file.getWebContentLink() : "";
                        audio.downloaded = false;
                        audioList.add(audio);
                    }
                    driveAudioDao.insertAll(audioList);
                }

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
                List<DriveFolder> selectedFolders = driveFolderDao.getSelected();

                for (DriveFolder folder : selectedFolders) {
                    listAudioFilesFromFolderSync(folder.driveId);
                }

                isLoading.postValue(false);
            } catch (Exception e) {
                Log.e(TAG, "Error syncing folders", e);
                isLoading.postValue(false);
            } finally {
                if (onDone != null) onDone.run();
            }
        });
    }

    private void listAudioFilesFromFolderSync(String folderId) throws IOException {
        if (driveService == null) return;

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
            List<DriveAudio> audioList = new ArrayList<>();
            for (File file : files) {
                DriveAudio audio = new DriveAudio();
                audio.fileId = file.getId();
                audio.fileName = file.getName();
                audio.folderId = folderId;
                audio.fileSize = file.getSize() != null ? file.getSize() : 0;
                audio.lastModified = file.getModifiedTime() != null ? file.getModifiedTime().getValue() : 0;
                audio.webContentLink = file.getWebContentLink() != null ? file.getWebContentLink() : "";
                audio.downloaded = false;
                audioList.add(audio);
            }
            driveAudioDao.insertAll(audioList);
        }
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
        authStatus.postValue("LOGGED_OUT");
        executor.execute(() -> {
            driveFolderDao.deleteAll();
            driveAudioDao.clear();
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
}
