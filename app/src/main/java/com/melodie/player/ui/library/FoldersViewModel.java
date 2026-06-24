package com.melodie.player.ui.library;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MediatorLiveData;
import androidx.lifecycle.ViewModel;

import com.melodie.player.data.entity.FolderSource;
import com.melodie.player.data.entity.Song;
import com.melodie.player.data.repository.MusicRepository;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;

@HiltViewModel
public class FoldersViewModel extends ViewModel {

    private static final long LOCAL_SOURCE_ID = 0L;

    private final MusicRepository repository;
    private final MediatorLiveData<List<FolderSource>> folderSources = new MediatorLiveData<>();

    private List<FolderSource> persistedSources = new ArrayList<>();
    private boolean hasLocalSongs;

    @Inject
    public FoldersViewModel(MusicRepository repository) {
        this.repository = repository;

        folderSources.setValue(new ArrayList<>());

        folderSources.addSource(repository.observeFolderSources(), sources -> {
            persistedSources = sources != null ? sources : new ArrayList<>();
            publishSources();
        });

        folderSources.addSource(repository.observeAllSongs(), songs -> {
            hasLocalSongs = songs != null && !songs.isEmpty();
            publishSources();
        });
    }

    public LiveData<List<FolderSource>> getFolderSources() {
        return folderSources;
    }

    public void addFolderSource(String displayName, String treeUri) {
        repository.addFolderSource(displayName, treeUri);
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
        List<FolderSource> result = new ArrayList<>();

        if (hasLocalSongs) {
            FolderSource local = new FolderSource();
            local.id = LOCAL_SOURCE_ID;
            local.displayName = "Téléphone / Music";
            local.treeUri = "Sources locales déjà indexées";
            local.enabled = true;
            local.createdAt = 0L;
            result.add(local);
        }

        result.addAll(persistedSources);
        folderSources.postValue(result);
    }
}

