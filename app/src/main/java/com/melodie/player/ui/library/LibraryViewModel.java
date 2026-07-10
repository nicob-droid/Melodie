package com.melodie.player.ui.library;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.ViewModel;

import android.os.Parcelable;

import com.melodie.player.data.entity.Album;
import com.melodie.player.data.entity.FolderSource;
import com.melodie.player.data.entity.Playlist;
import com.melodie.player.data.entity.Song;
import com.melodie.player.data.model.ArtistData;
import com.melodie.player.data.model.PlaylistSummary;
import com.melodie.player.data.repository.MusicRepository;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.LongConsumer;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;

@HiltViewModel
public class LibraryViewModel extends ViewModel {

    private final MusicRepository repository;
    private final Set<Long> coverRequests = Collections.synchronizedSet(new HashSet<>());

    /** Mémorise la position de défilement de la liste des albums pour la restaurer au retour. */
    public Parcelable albumsListState;

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

    public LiveData<Album> album(long albumId) {
        return repository.observeAlbum(albumId);
    }

    public LiveData<List<String>> artists() {
        return repository.observeArtists();
    }

    public LiveData<List<ArtistData>> artistsWithData() {
        return repository.observeArtistsWithData();
    }

    public LiveData<List<PlaylistSummary>> playlists() {
        return repository.observePlaylists();
    }

    public LiveData<List<FolderSource>> folderSources() {
        return repository.observeFolderSources();
    }

    public LiveData<Playlist> playlist(long playlistId) {
        return repository.observePlaylist(playlistId);
    }

    public LiveData<List<Song>> playlistSongs(long playlistId) {
        return repository.observePlaylistSongs(playlistId);
    }

    public void createPlaylist(String name, LongConsumer onDone) {
        repository.createPlaylist(name, onDone);
    }

    public void addSongToPlaylist(long playlistId, String songId, Runnable onDone) {
        repository.addSongToPlaylist(playlistId, songId, onDone);
    }

    public void removeSongFromPlaylist(long playlistId, String songId) {
        repository.removeSongFromPlaylist(playlistId, songId);
    }

    public void renamePlaylist(long playlistId, String name) {
        repository.renamePlaylist(playlistId, name);
    }

    public void deletePlaylist(long playlistId) {
        repository.deletePlaylist(playlistId);
    }

    public void reorderPlaylist(long playlistId, List<String> orderedSongIds) {
        repository.reorderPlaylist(playlistId, orderedSongIds);
    }

    public void updateAlbumMetadata(long albumId, String name, String releaseDate, String cover) {
        repository.updateAlbumMetadata(albumId, name, releaseDate, cover);
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
            boolean missingCover = album.cover == null || album.cover.trim().isEmpty();
            boolean missingReleaseDate = album.releaseDate == null || album.releaseDate.trim().isEmpty();
            if (missingCover || missingReleaseDate) {
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

