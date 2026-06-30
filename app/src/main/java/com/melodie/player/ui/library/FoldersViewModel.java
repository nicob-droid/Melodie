package com.melodie.player.ui.library;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MediatorLiveData;
import androidx.lifecycle.ViewModel;

import com.melodie.player.data.entity.DriveFolder;
import com.melodie.player.data.entity.FolderSource;
import com.melodie.player.data.entity.Song;
import com.melodie.player.data.repository.DriveRepository;
import com.melodie.player.data.repository.MusicRepository;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;

@HiltViewModel
public class FoldersViewModel extends ViewModel {

    private final MusicRepository repository;
    private final DriveRepository driveRepository;
    private final MediatorLiveData<List<FolderSource>> folderSources = new MediatorLiveData<>();

    private List<FolderSource> persistedSources = new ArrayList<>();

    @Inject
    public FoldersViewModel(MusicRepository repository, DriveRepository driveRepository) {
        this.repository = repository;
        this.driveRepository = driveRepository;

        folderSources.setValue(new ArrayList<>());

        folderSources.addSource(repository.observeFolderSources(), sources -> {
            persistedSources = sources != null ? sources : new ArrayList<>();
            publishSources();
        });
    }

    public LiveData<List<FolderSource>> getFolderSources() {
        return folderSources;
    }

    public void addFolderSource(String displayName, String treeUri) {
        repository.addFolderSource(displayName, treeUri);
    }

    public void addSelectedDriveFoldersAsSources(List<DriveFolder> folders) {
        if (folders == null || folders.isEmpty()) return;

        // Filtre pour ne garder que les dossiers racine (dont le parent n'est pas sélectionné)
        // Les enfants seront synchronisés automatiquement via la logique de récursion
        // mais ne créeront pas de sources distinctes dans l'UI
        java.util.Set<String> selectedDriveIds = new java.util.HashSet<>();
        for (DriveFolder f : folders) {
            if (f != null && f.selected && f.driveId != null && !f.driveId.trim().isEmpty()) {
                selectedDriveIds.add(f.driveId);
            }
        }

        for (DriveFolder folder : folders) {
            if (folder != null && folder.selected && folder.driveId != null && !folder.driveId.trim().isEmpty()) {
                // Ajouter seulement si le parent n'est pas dans la liste des sélectionnés
                boolean isRootSelection = folder.parentDriveId == null
                        || folder.parentDriveId.trim().isEmpty()
                        || !selectedDriveIds.contains(folder.parentDriveId);
                if (isRootSelection) {
                    repository.addDriveFolderSource(folder.name, folder.driveId);
                }
            }
        }
    }

    public LiveData<List<DriveFolder>> getDriveFolders() {
        return driveRepository.observeDriveFolders();
    }

    public boolean isDriveLoggedIn() {
        return driveRepository.isLoggedIn();
    }

    public void loadDriveFolders() {
        driveRepository.listFoldersFromDrive(null);
    }

    public void toggleFolderSourceEnabled(FolderSource source) {
        repository.toggleFolderSourceEnabled(source);
    }

    public void setFolderSourceEnabled(FolderSource source, boolean enabled) {
        repository.setFolderSourceEnabled(source, enabled);
    }

    public void removeFolderSource(FolderSource source) {
        repository.removeFolderSource(source);
    }

    private void publishSources() {
        folderSources.postValue(new ArrayList<>(persistedSources));
    }
}

