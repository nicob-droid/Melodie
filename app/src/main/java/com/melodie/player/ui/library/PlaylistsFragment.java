package com.melodie.player.ui.library;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.melodie.player.R;
import com.melodie.player.ui.adapter.PlaylistAdapter;

import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public class PlaylistsFragment extends Fragment {

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_playlists, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        View emptyState = view.findViewById(R.id.empty_state);
        View createButton = view.findViewById(R.id.btn_create_playlist);
        View createFab = view.findViewById(R.id.fab_create_playlist);
        RecyclerView rv = view.findViewById(R.id.recycler);
        rv.setLayoutManager(new LinearLayoutManager(requireContext()));
        PlaylistAdapter adapter = new PlaylistAdapter(playlist -> {
            Bundle args = new Bundle();
            args.putLong(PlaylistDetailFragment.ARG_PLAYLIST_ID, playlist.id);
            NavHostFragment.findNavController(PlaylistsFragment.this)
                    .navigate(R.id.playlistDetailFragment, args);
        });
        rv.setAdapter(adapter);

        LibraryViewModel vm = new ViewModelProvider(requireParentFragment())
                .get(LibraryViewModel.class);
        View.OnClickListener onCreate = v -> PlaylistDialogs.showCreatePlaylistDialog(
                PlaylistsFragment.this,
                vm,
                id -> requireActivity().runOnUiThread(() -> {
                    Bundle args = new Bundle();
                    args.putLong(PlaylistDetailFragment.ARG_PLAYLIST_ID, id);
                    NavHostFragment.findNavController(PlaylistsFragment.this)
                            .navigate(R.id.playlistDetailFragment, args);
                })
        );
        createButton.setOnClickListener(onCreate);
        createFab.setOnClickListener(onCreate);

        vm.playlists().observe(getViewLifecycleOwner(), playlists -> {
            adapter.submitList(playlists);
            boolean isEmpty = playlists == null || playlists.isEmpty();
            emptyState.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
            createFab.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
        });
    }
}


