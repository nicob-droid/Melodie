package com.melodie.player.ui.library;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.ViewModel;

import com.melodie.player.data.entity.Album;
import com.melodie.player.data.entity.Song;
import com.melodie.player.data.model.ArtistData;
import com.melodie.player.data.repository.MusicRepository;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;

@HiltViewModel
public class LibraryViewModel extends ViewModel {

    private final MusicRepository repository;
    private final Set<Long> coverRequests = Collections.synchronizedSet(new HashSet<>());

    @Inject
    public LibraryViewModel(MusicRepository repository) {
        this.repository = repository;
    }

    public LiveData<List<Song>> songs() {
        return repository.observeAllSongs();
    }

    public LiveData<List<Album>> albums() {
        return repository.observeAllAlbums();
    }

    public LiveData<List<String>> artists() {
        return repository.observeArtists();
    }

    public LiveData<List<ArtistData>> artistsWithData() {
        return repository.observeArtistsWithData();
    }

    public LiveData<List<Song>> playlists() {
        return repository.observeFavorites();
    }

    public LiveData<List<Song>> songsByAlbum(long albumId) {
        return repository.observeSongsByAlbum(albumId);
    }

    public LiveData<List<Song>> songsByArtist(String artist) {
        return repository.observeSongsByArtist(artist);
    }

    public void refreshLocal() {
        repository.scanLocal(null);
    }

    public void prefetchMissingCovers(List<Album> albums) {
        if (albums == null) return;
        for (Album album : albums) {
            if (album == null) continue;
            if (album.cover == null || album.cover.trim().isEmpty()) {
                resolveAlbumCover(album, false);
            }
        }
    }

    public void resolveAlbumCover(Album album, boolean force) {
        if (album == null) return;
        if (!coverRequests.add(album.id)) return;

        repository.resolveAlbumCover(album, force, () -> coverRequests.remove(album.id));
    }
}

