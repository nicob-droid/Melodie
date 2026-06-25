package com.melodie.player.ui.library;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.melodie.player.R;
import com.melodie.player.data.entity.Playlist;
import com.melodie.player.data.entity.Song;
import com.melodie.player.playback.PlayerController;
import com.melodie.player.ui.adapter.SongAdapter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public class PlaylistDetailFragment extends Fragment {

    public static final String ARG_PLAYLIST_ID = "playlist_id";

    @Inject
    PlayerController playerController;

    private long playlistId;
    private LibraryViewModel vm;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_playlist_detail, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        playlistId = requireArguments().getLong(ARG_PLAYLIST_ID, -1L);
        if (playlistId <= 0) {
            NavHostFragment.findNavController(this).navigateUp();
            return;
        }

        vm = new ViewModelProvider(this).get(LibraryViewModel.class);

        TextView title = view.findViewById(R.id.title);
        TextView subtitle = view.findViewById(R.id.subtitle);
        TextView emptyText = view.findViewById(R.id.empty_text);
        RecyclerView rv = view.findViewById(R.id.recycler);

        view.findViewById(R.id.btn_back)
                .setOnClickListener(v -> NavHostFragment.findNavController(this).navigateUp());

        SongAdapter adapter = new SongAdapter((song, position) -> {
            playerController.playQueue(adapterSongs(rv), position);
            NavHostFragment.findNavController(this).navigate(R.id.playerFragment);
        }, (anchor, song, position) -> showTrackMenu(song));
        rv.setLayoutManager(new LinearLayoutManager(requireContext()));
        rv.setAdapter(adapter);

        view.findViewById(R.id.btn_play).setOnClickListener(v -> {
            List<Song> songs = adapterSongs(rv);
            if (songs.isEmpty()) return;
            playerController.playQueue(songs, 0);
            NavHostFragment.findNavController(this).navigate(R.id.playerFragment);
        });

        view.findViewById(R.id.btn_shuffle).setOnClickListener(v -> {
            List<Song> songs = adapterSongs(rv);
            if (songs.isEmpty()) return;
            Collections.shuffle(songs);
            playerController.playQueue(songs, 0);
            NavHostFragment.findNavController(this).navigate(R.id.playerFragment);
        });

        view.findViewById(R.id.btn_rename).setOnClickListener(v -> {
            Playlist playlist = (Playlist) title.getTag();
            if (playlist == null) return;
            showRenameDialog(playlist);
        });

        view.findViewById(R.id.btn_delete).setOnClickListener(v -> {
            Playlist playlist = (Playlist) title.getTag();
            if (playlist == null) return;
            showDeleteDialog(playlist);
        });

        ItemTouchHelper touchHelper = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0
        ) {
            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView,
                                  @NonNull RecyclerView.ViewHolder viewHolder,
                                  @NonNull RecyclerView.ViewHolder target) {
                SongAdapter songAdapter = (SongAdapter) recyclerView.getAdapter();
                if (songAdapter == null) return false;
                List<Song> current = new ArrayList<>(songAdapter.getCurrentList());
                int from = viewHolder.getBindingAdapterPosition();
                int to = target.getBindingAdapterPosition();
                if (from < 0 || to < 0 || from >= current.size() || to >= current.size()) return false;
                Collections.swap(current, from, to);
                songAdapter.submitList(current);
                return true;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
                // Pas de swipe ici : menu contextuel uniquement.
            }

            @Override
            public boolean isLongPressDragEnabled() {
                return true;
            }

            @Override
            public void clearView(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
                super.clearView(recyclerView, viewHolder);
                SongAdapter songAdapter = (SongAdapter) recyclerView.getAdapter();
                if (songAdapter == null) return;
                List<String> orderedIds = new ArrayList<>();
                for (Song song : songAdapter.getCurrentList()) {
                    orderedIds.add(song.id);
                }
                vm.reorderPlaylist(playlistId, orderedIds);
            }
        });
        touchHelper.attachToRecyclerView(rv);

        vm.playlist(playlistId).observe(getViewLifecycleOwner(), playlist -> {
            if (playlist == null) {
                NavHostFragment.findNavController(this).navigateUp();
                return;
            }
            title.setText(playlist.name);
            title.setTag(playlist);
        });

        vm.playlistSongs(playlistId).observe(getViewLifecycleOwner(), songs -> {
            adapter.submitList(songs);
            int count = songs != null ? songs.size() : 0;
            subtitle.setText(getResources().getQuantityString(R.plurals.playlist_song_count, count, count));
            emptyText.setVisibility(count == 0 ? View.VISIBLE : View.GONE);
        });
    }

    private void showTrackMenu(Song song) {
        String[] items = {
                getString(R.string.playlist_remove_track),
                getString(R.string.playlist_add_to)
        };
        new AlertDialog.Builder(requireContext())
                .setItems(items, (dialog, which) -> {
                    if (which == 0) {
                        vm.removeSongFromPlaylist(playlistId, song.id);
                        return;
                    }
                    PlaylistDialogs.showAddToPlaylistDialog(
                            this,
                            vm,
                            song.id,
                            () -> requireActivity().runOnUiThread(() ->
                                    Toast.makeText(requireContext(), R.string.playlist_track_added, Toast.LENGTH_SHORT).show())
                    );
                })
                .show();
    }

    private void showRenameDialog(Playlist playlist) {
        android.widget.EditText input = new android.widget.EditText(requireContext());
        input.setText(playlist.name);
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.playlist_rename)
                .setView(input)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.playlist_rename, (dialog, which) -> {
                    String newName = input.getText() != null ? input.getText().toString().trim() : "";
                    if (newName.isEmpty()) return;
                    vm.renamePlaylist(playlist.id, newName);
                })
                .show();
    }

    private void showDeleteDialog(Playlist playlist) {
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.playlist_delete_confirm_title)
                .setMessage(getString(R.string.playlist_delete_confirm_message, playlist.name))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.playlist_delete, (dialog, which) -> {
                    vm.deletePlaylist(playlist.id);
                    NavHostFragment.findNavController(this).navigateUp();
                })
                .show();
    }

    private List<Song> adapterSongs(RecyclerView rv) {
        SongAdapter adapter = (SongAdapter) rv.getAdapter();
        if (adapter == null) return new ArrayList<>();
        return new ArrayList<>(adapter.getCurrentList());
    }
}

