package com.melodie.player.ui.search;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Transformations;
import androidx.lifecycle.ViewModel;

import com.melodie.player.data.entity.Song;
import com.melodie.player.data.repository.MusicRepository;

import java.util.List;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;

@HiltViewModel
public class SearchViewModel extends ViewModel {

    private final MusicRepository repository;
    private final MutableLiveData<String> query = new MutableLiveData<>("");
    private final LiveData<List<Song>> results;

    @Inject
    public SearchViewModel(MusicRepository repository) {
        this.repository = repository;
        results = Transformations.switchMap(query, q ->
                (q == null || q.trim().isEmpty())
                        ? repository.observeAllSongs()
                        : repository.search(q.trim()));
    }

    public void setQuery(String q) {
        query.setValue(q);
    }

    public LiveData<List<Song>> results() {
        return results;
    }
}

