package com.melodie.player.ui.library;

import android.content.Context;
import android.text.InputType;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;

import com.melodie.player.R;
import com.melodie.player.data.model.PlaylistSummary;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongConsumer;

public final class PlaylistDialogs {

    private PlaylistDialogs() {
    }

    public static void showCreatePlaylistDialog(@NonNull Fragment fragment,
                                                @NonNull LibraryViewModel viewModel,
                                                @NonNull LongConsumer onCreated) {
        Context context = fragment.requireContext();
        EditText input = new EditText(context);
        input.setHint(R.string.playlist_name_hint);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        int padding = (int) (16 * context.getResources().getDisplayMetrics().density);
        input.setPadding(padding, padding, padding, padding);

        new AlertDialog.Builder(context)
                .setTitle(R.string.playlist_create)
                .setView(input)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.playlist_create_confirm, (dialog, which) -> {
                    String name = input.getText() != null ? input.getText().toString().trim() : "";
                    if (name.isEmpty()) {
                        Toast.makeText(context, R.string.playlist_name_required, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    viewModel.createPlaylist(name, onCreated);
                })
                .show();
    }

    public static void showAddToPlaylistDialog(@NonNull Fragment fragment,
                                               @NonNull LibraryViewModel viewModel,
                                               @NonNull String songId,
                                               @NonNull Runnable onDone) {
        LiveData<List<PlaylistSummary>> playlistsLiveData = viewModel.playlists();
        Observer<List<PlaylistSummary>> observer = new Observer<>() {
            @Override
            public void onChanged(List<PlaylistSummary> playlists) {
                playlistsLiveData.removeObserver(this);

                List<PlaylistSummary> items = playlists != null ? playlists : new ArrayList<>();
                if (items.isEmpty()) {
                    showCreatePlaylistDialog(fragment, viewModel,
                            id -> runOnMainThread(fragment,
                                    () -> viewModel.addSongToPlaylist(id, songId, onDone)));
                    return;
                }

                String[] labels = new String[items.size() + 1];
                for (int i = 0; i < items.size(); i++) {
                    labels[i] = items.get(i).name;
                }
                labels[items.size()] = fragment.getString(R.string.playlist_create_new_short);

                new AlertDialog.Builder(fragment.requireContext())
                        .setTitle(R.string.playlist_add_to)
                        .setItems(labels, (dialog, which) -> {
                            if (which == items.size()) {
                                showCreatePlaylistDialog(fragment, viewModel,
                                        id -> runOnMainThread(fragment,
                                                () -> viewModel.addSongToPlaylist(id, songId, onDone)));
                                return;
                            }
                            PlaylistSummary selected = items.get(which);
                            viewModel.addSongToPlaylist(selected.id, songId, onDone);
                        })
                        .show();
            }
        };
        playlistsLiveData.observe(fragment.getViewLifecycleOwner(), observer);
    }

    private static void runOnMainThread(@NonNull Fragment fragment, @NonNull Runnable action) {
        if (!fragment.isAdded()) return;
        fragment.requireActivity().runOnUiThread(action);
    }
}



