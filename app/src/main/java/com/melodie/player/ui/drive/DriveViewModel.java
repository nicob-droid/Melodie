package com.melodie.player.ui.drive;

import android.content.Context;
import android.util.Log;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.melodie.player.data.entity.DriveAudio;
import com.melodie.player.data.entity.DriveFolder;
import com.melodie.player.data.repository.DriveRepository;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.GoogleAuthUtil;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import java.util.Collections;
import java.util.List;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;
import dagger.hilt.android.qualifiers.ApplicationContext;

@HiltViewModel
public class DriveViewModel extends ViewModel {

    private final DriveRepository repository;
    private final Context context;
    private final MutableLiveData<String> selectedFolderId = new MutableLiveData<>();
    private final MutableLiveData<List<DriveAudio>> currentAudioFiles = new MutableLiveData<>(Collections.emptyList());

    @Inject
    public DriveViewModel(DriveRepository repository, @ApplicationContext Context context) {
        this.repository = repository;
        this.context = context;
    }

    public void loadDriveFolders() {
        repository.listFoldersFromDrive(null);
    }

    public void loadAudioFilesFromFolder(String folderId) {
        selectedFolderId.setValue(folderId);
        repository.listAudioFilesFromFolder(folderId, null);
    }

    public void toggleFolderSelection(DriveFolder folder) {
        repository.toggleFolderSelection(folder);
    }

    public void syncSelectedFolders() {
        repository.syncSelectedFolders(() -> {
            // Sync completed
        });
    }

    public LiveData<String> getAuthStatus() {
        return repository.getAuthStatus();
    }

    public LiveData<Boolean> getIsLoading() {
        return repository.getIsLoading();
    }

    public LiveData<List<DriveFolder>> getDriveFolders() {
        return repository.observeDriveFolders();
    }

    public LiveData<List<DriveAudio>> getAudioFilesFromFolder(String folderId) {
        return repository.getAudioFilesFromFolder(folderId);
    }

    public String getSelectedFolderId() {
        return selectedFolderId.getValue();
    }

    public void logout() {
        repository.logout();
    }

    public boolean isLoggedIn() {
        return repository.isLoggedIn();
    }

    public GoogleSignInClient getGoogleSignInClient() {
        return repository.getGoogleSignInClient();
    }

    public void handleGoogleSignInResult(GoogleSignInAccount account) {
        if (account != null) {
            // GoogleAuthUtil.getToken est bloquant, donc on l'exécute hors thread UI.
            Thread tokenThread = new Thread(() -> {
                try {
                    if (account.getAccount() == null) {
                        Log.e("DriveViewModel", "Google account is null after sign-in");
                        return;
                    }

                    String accessToken = GoogleAuthUtil.getToken(
                            context,
                            account.getAccount(),
                            "oauth2:https://www.googleapis.com/auth/drive.readonly"
                    );

                    // Créer le Drive service avec le Bearer token
                    HttpRequestInitializer httpRequestInitializer = request -> {
                        request.getHeaders().setAuthorization("Bearer " + accessToken);
                    };

                    Drive driveService = new Drive.Builder(
                            new NetHttpTransport(),
                            new GsonFactory(),
                            httpRequestInitializer
                    ).setApplicationName("Melodie").build();

                    repository.setDriveService(driveService);
                    loadDriveFolders();
                } catch (Exception e) {
                    Log.e("DriveViewModel", "Failed to get access token", e);
                    // Fallback best effort, même si ID token ne suffit souvent pas pour Drive REST.
                    fallbackToIdToken(account);
                }
            }, "drive-token-thread");
            tokenThread.start();
        }
    }

    private void fallbackToIdToken(GoogleSignInAccount account) {
        String idToken = account.getIdToken();
        if (idToken != null) {
            HttpRequestInitializer httpRequestInitializer = request -> {
                request.getHeaders().setAuthorization("Bearer " + idToken);
            };

            Drive driveService = new Drive.Builder(
                    new NetHttpTransport(),
                    new GsonFactory(),
                    httpRequestInitializer
            ).setApplicationName("Melodie").build();

            repository.setDriveService(driveService);
            loadDriveFolders();
        } else {
            Log.e("DriveViewModel", "No access token or ID token available");
        }
    }
}

