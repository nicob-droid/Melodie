package com.melodie.player.ui.search;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.melodie.player.R;
import com.melodie.player.playback.PlayerController;
import com.melodie.player.ui.adapter.SongAdapter;
import com.melodie.player.ui.library.LibraryViewModel;
import com.melodie.player.ui.library.PlaylistDialogs;

import java.util.List;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public class SearchFragment extends Fragment {

    @Inject
    PlayerController playerController;

    private TextView emptyState;
    private RecyclerView recycler;
    private boolean hasActiveSources = true;
    private boolean isQueryEmpty = true;
    private int lastResultCount;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_search, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        LibraryViewModel libraryVm = new ViewModelProvider(requireParentFragment())
                .get(LibraryViewModel.class);

        recycler = view.findViewById(R.id.recycler);
        recycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        emptyState = view.findViewById(R.id.empty_state);

        SongAdapter adapter = new SongAdapter((song, position) -> {
            playerController.playQueue(((SongAdapter) recycler.getAdapter()).getCurrentList(), position);
            NavHostFragment.findNavController(SearchFragment.this).navigate(R.id.playerFragment);
        }, (anchor, song, position) -> PlaylistDialogs.showAddToPlaylistDialog(
                SearchFragment.this,
                libraryVm,
                song.id,
                () -> requireActivity().runOnUiThread(() ->
                        Toast.makeText(requireContext(), R.string.playlist_track_added, Toast.LENGTH_SHORT).show())
        ));
        recycler.setAdapter(adapter);

        SearchViewModel vm = new ViewModelProvider(this).get(SearchViewModel.class);

        // Observe les sources actives : ne dépend pas de l'état (asynchrone) de l'adapter.
        libraryVm.folderSources().observe(getViewLifecycleOwner(), sources -> {
            hasActiveSources = computeHasActiveSources(sources);
            updateEmptyState();
        });

        vm.results().observe(getViewLifecycleOwner(), results -> {
            adapter.submitList(results);
            lastResultCount = results != null ? results.size() : 0;
            isQueryEmpty = vm.getLastQuery().isEmpty();
            updateEmptyState();
        });

        EditText search = view.findViewById(R.id.search_input);
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                vm.setQuery(s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) { }
        });
    }

    private boolean computeHasActiveSources(List<com.melodie.player.data.entity.FolderSource> sources) {
        if (sources == null) return false;
        for (com.melodie.player.data.entity.FolderSource source : sources) {
            if (source != null && source.enabled) {
                return true;
            }
        }
        return false;
    }

    private void updateEmptyState() {
        if (emptyState == null || recycler == null) return;

        if (!hasActiveSources) {
            emptyState.setText(R.string.search_no_active_source);
            emptyState.setVisibility(View.VISIBLE);
            recycler.setVisibility(View.GONE);
        } else if (lastResultCount == 0) {
            emptyState.setText(isQueryEmpty ? R.string.search_empty : R.string.search_no_results);
            emptyState.setVisibility(View.VISIBLE);
            recycler.setVisibility(View.GONE);
        } else {
            emptyState.setVisibility(View.GONE);
            recycler.setVisibility(View.VISIBLE);
        }
    }
}

