package com.melodie.player.ui.adapter;

import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.melodie.player.R;
import com.melodie.player.data.entity.Song;
import com.melodie.player.util.DurationFormatter;

import java.util.Objects;

public class SongAdapter extends ListAdapter<Song, SongAdapter.VH> {

    private static final String NO_REMOTE_COVER = "__NO_REMOTE_COVER__";

    public interface OnSongClick {
        void onClick(Song song, int position);
    }

    private final OnSongClick listener;

    public SongAdapter(OnSongClick listener) {
        super(DIFF);
        this.listener = listener;
    }

    private static final DiffUtil.ItemCallback<Song> DIFF = new DiffUtil.ItemCallback<Song>() {
        @Override
        public boolean areItemsTheSame(@NonNull Song o, @NonNull Song n) {
            return Objects.equals(o.id, n.id);
        }

        @Override
        public boolean areContentsTheSame(@NonNull Song o, @NonNull Song n) {
            return o.favorite == n.favorite
                    && Objects.equals(o.title, n.title)
                    && Objects.equals(o.artist, n.artist)
                    && Objects.equals(o.cover, n.cover)
                    && o.duration == n.duration;
        }
    };

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_song, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Song s = getItem(position);
        h.title.setText(s.title);
        h.subtitle.setText(s.artist != null ? s.artist : "");
        h.duration.setText(DurationFormatter.format(s.duration));
        // Meme logique que LibraryAlbumListAdapter/ArtistAdapter pour une synchro parfaite
        // entre les onglets Songs / Albums / Artists.
        Glide.with(h.cover)
                .load(toGlideSource(s.cover))
                .placeholder(R.drawable.ic_album)
                .error(R.drawable.ic_album)
                .into(h.cover);
        h.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onClick(s, position);
        });
    }

    private Object toGlideSource(String cover) {
        if (cover == null) return null;
        String trimmed = cover.trim();
        if (trimmed.isEmpty() || NO_REMOTE_COVER.equals(trimmed)) return null;
        if (trimmed.startsWith("http") || trimmed.startsWith("content://")) {
            return Uri.parse(trimmed);
        }
        return null;
    }

    static class VH extends RecyclerView.ViewHolder {
        final ImageView cover;
        final TextView title;
        final TextView subtitle;
        final TextView duration;

        VH(@NonNull View itemView) {
            super(itemView);
            cover = itemView.findViewById(R.id.cover);
            title = itemView.findViewById(R.id.title);
            subtitle = itemView.findViewById(R.id.subtitle);
            duration = itemView.findViewById(R.id.duration);
        }
    }
}

