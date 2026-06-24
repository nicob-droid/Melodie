package com.melodie.player.ui.home;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.ViewModel;

import com.melodie.player.data.entity.Album;
import com.melodie.player.data.entity.Song;
import com.melodie.player.data.repository.MusicRepository;

import java.util.List;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;

@HiltViewModel
public class HomeViewModel extends ViewModel {

    private final MusicRepository repository;

    @Inject
    public HomeViewModel(MusicRepository repository) {
        this.repository = repository;
    }

    public LiveData<List<Album>> recentAlbums() {
        return repository.observeRecentAlbums(10);
    }

    public LiveData<List<Song>> favorites() {
        return repository.observeFavorites();
    }
}

