package com.melodie.player.ui.library;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
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

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public class SongsFragment extends Fragment {

    @Inject
    PlayerController playerController;

    private TextView emptyStateView;
    private int lastSongCount;
    private boolean hasActiveSources = true;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_songs, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        LibraryViewModel vm = new ViewModelProvider(requireParentFragment())
                .get(LibraryViewModel.class);

        RecyclerView rv = view.findViewById(R.id.recycler);
        emptyStateView = view.findViewById(R.id.songs_empty_state);
        rv.setLayoutManager(new LinearLayoutManager(requireContext()));
        SongAdapter adapter = new SongAdapter((song, position) -> {
            playerController.playQueue(((SongAdapter) rv.getAdapter()).getCurrentList(), position);
            NavHostFragment.findNavController(SongsFragment.this)
                    .navigate(R.id.playerFragment);
        }, (anchor, song, position) -> PlaylistDialogs.showAddToPlaylistDialog(
                SongsFragment.this,
                vm,
                song.id,
                () -> requireActivity().runOnUiThread(() ->
                        Toast.makeText(requireContext(), R.string.playlist_track_added, Toast.LENGTH_SHORT).show())
        ));
        rv.setAdapter(adapter);

        SongAdapter finalAdapter = adapter;
        vm.songs().observe(getViewLifecycleOwner(), songs -> {
            finalAdapter.submitList(songs);
            lastSongCount = songs != null ? songs.size() : 0;
            updateEmptyState();
        });

        vm.folderSources().observe(getViewLifecycleOwner(), sources -> {
            boolean active = false;
            if (sources != null) {
                for (com.melodie.player.data.entity.FolderSource source : sources) {
                    if (source != null && source.enabled) {
                        active = true;
                        break;
                    }
                }
            }
            hasActiveSources = active;
            updateEmptyState();
        });
    }

    private void updateEmptyState() {
        if (emptyStateView == null) return;
        boolean showEmpty = lastSongCount == 0;
        emptyStateView.setVisibility(showEmpty ? View.VISIBLE : View.GONE);
        if (!showEmpty) return;

        emptyStateView.setText(hasActiveSources
                ? R.string.songs_empty
                : R.string.songs_empty_no_active_source);
    }
}

