package com.melodie.player.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.melodie.player.R;
import com.melodie.player.data.model.PlaylistSummary;

import java.util.Locale;
import java.util.Objects;

public class PlaylistAdapter extends ListAdapter<PlaylistSummary, PlaylistAdapter.VH> {

    public interface OnPlaylistClick {
        void onClick(PlaylistSummary playlist);
    }

    private final OnPlaylistClick listener;

    public PlaylistAdapter(OnPlaylistClick listener) {
        super(DIFF);
        this.listener = listener;
    }

    private static final DiffUtil.ItemCallback<PlaylistSummary> DIFF =
            new DiffUtil.ItemCallback<>() {
                @Override
                public boolean areItemsTheSame(@NonNull PlaylistSummary oldItem,
                                               @NonNull PlaylistSummary newItem) {
                    return oldItem.id == newItem.id;
                }

                @Override
                public boolean areContentsTheSame(@NonNull PlaylistSummary oldItem,
                                                  @NonNull PlaylistSummary newItem) {
                    return Objects.equals(oldItem.name, newItem.name)
                            && oldItem.songCount == newItem.songCount
                            && oldItem.totalDuration == newItem.totalDuration;
                }
            };

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_playlist, parent, false);
        return new VH(view);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        PlaylistSummary playlist = getItem(position);
        holder.title.setText(playlist.name);
        holder.subtitle.setText(buildSubtitle(holder.itemView, playlist.songCount, playlist.totalDuration));
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onClick(playlist);
        });
    }

    private String buildSubtitle(View itemView, int songCount, long totalDuration) {
        String count = itemView.getResources().getQuantityString(
                R.plurals.playlist_song_count,
                songCount,
                songCount
        );
        String duration = formatDuration(totalDuration);
        return count + " • " + duration;
    }

    private String formatDuration(long durationMs) {
        long minutes = Math.max(0L, durationMs / 60000L);
        long hours = minutes / 60L;
        long remainingMinutes = minutes % 60L;
        if (hours > 0) {
            return String.format(Locale.getDefault(), "%dh %02d", hours, remainingMinutes);
        }
        return String.format(Locale.getDefault(), "%d min", remainingMinutes);
    }

    static class VH extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView subtitle;

        VH(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.title);
            subtitle = itemView.findViewById(R.id.subtitle);
        }
    }
}

