package com.melodie.player.ui.adapter;

import android.net.Uri;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.melodie.player.R;
import com.melodie.player.data.model.ArtistData;

public class ArtistAdapter extends ListAdapter<ArtistData, ArtistAdapter.VH> {

    public interface OnArtistClick {
        void onClick(ArtistData artist);
    }

    private final OnArtistClick listener;

    public ArtistAdapter(OnArtistClick listener) {
        super(DIFF);
        this.listener = listener;
    }

    private static final DiffUtil.ItemCallback<ArtistData> DIFF = new DiffUtil.ItemCallback<ArtistData>() {
        @Override
        public boolean areItemsTheSame(@NonNull ArtistData oldItem, @NonNull ArtistData newItem) {
            return oldItem.name.equals(newItem.name);
        }

        @Override
        public boolean areContentsTheSame(@NonNull ArtistData oldItem, @NonNull ArtistData newItem) {
            boolean sameCover = (oldItem.cover == null && newItem.cover == null)
                    || (oldItem.cover != null && oldItem.cover.equals(newItem.cover));
            return oldItem.name.equals(newItem.name) && oldItem.songCount == newItem.songCount && sameCover;
        }
    };

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_artist, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        ArtistData artist = getItem(position);
        holder.name.setText(artist.name);

        String countText = holder.itemView.getContext().getString(R.string.artist_song_count, artist.songCount);
        holder.count.setText(countText);

        Object source = toGlideSource(artist.cover);
        Glide.with(holder.cover)
                .load(source)
                .placeholder(R.drawable.ic_music_note)
                .error(R.drawable.ic_music_note)
                .listener(new RequestListener<Drawable>() {
                    @Override
                    public boolean onLoadFailed(@Nullable GlideException e, Object model,
                                                Target<Drawable> target, boolean isFirstResource) {
                        return false;
                    }

                    @Override
                    public boolean onResourceReady(Drawable resource, Object model,
                                                   Target<Drawable> target, DataSource dataSource,
                                                   boolean isFirstResource) {
                        return false;
                    }
                })
                .into(holder.cover);

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onClick(artist);
        });
    }

    private Object toGlideSource(String cover) {
        if (cover == null) return null;
        String trimmed = cover.trim();
        if (trimmed.isEmpty() || "__NO_REMOTE_COVER__".equals(trimmed)) return null;
        if (trimmed.startsWith("http") || trimmed.startsWith("content://") || trimmed.startsWith("file://")) {
            return Uri.parse(trimmed);
        }
        return null;
    }

    static class VH extends RecyclerView.ViewHolder {
        final ImageView cover;
        final TextView name;
        final TextView count;

        VH(@NonNull View itemView) {
            super(itemView);
            cover = itemView.findViewById(R.id.artist_cover);
            name = itemView.findViewById(R.id.artist_name);
            count = itemView.findViewById(R.id.artist_count);
        }
    }
}

