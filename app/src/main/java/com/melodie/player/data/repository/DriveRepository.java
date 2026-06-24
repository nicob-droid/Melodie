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
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
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
        authStatus.setValue("LOGGED_IN");
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

                Log.d(TAG, "Starting to list Drive folders...");
                isLoading.postValue(true);

                // Cherche les dossiers dans Google Drive
                String query = "mimeType='application/vnd.google-apps.folder' and trashed=false";
                Drive.Files.List request = driveService.files().list()
                        .setQ(query)
                        .setSpaces("drive")
                        .setFields("files(id, name, parents, modifiedTime)")
                        .setPageSize(100);

                FileList result = request.execute();
                List<File> files = result.getFiles();
                
                Log.d(TAG, "Found " + (files != null ? files.size() : 0) + " folders");

                if (files != null && !files.isEmpty()) {
                    driveFolderDao.deleteAll();
                    for (File file : files) {
                        DriveFolder folder = new DriveFolder();
                        folder.driveId = file.getId();
                        folder.name = file.getName();
                        folder.lastSync = System.currentTimeMillis();
                        driveFolderDao.insert(folder);
                        Log.d(TAG, "Added folder: " + file.getName() + " (" + file.getId() + ")");
                    }
                } else {
                    Log.d(TAG, "No folders found in Drive");
                }

                isLoading.postValue(false);
            } catch (IOException e) {
                Log.e(TAG, "IOException while listing folders", e);
                isLoading.postValue(false);
            } finally {
                if (onDone != null) onDone.run();
            }
        });
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
                Log.e(TAG, "Error listing audio files", e);
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
        executor.execute(() -> {
            folder.selected = !folder.selected;
            driveFolderDao.update(folder);
        });
    }

    public void logout() {
        googleSignInClient.signOut();
        driveService = null;
        authStatus.setValue("LOGGED_OUT");
        driveFolderDao.deleteAll();
        driveAudioDao.clear();
    }
}

