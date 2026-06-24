package com.melodie.player.ui.adapter;

import android.net.Uri;
import android.graphics.drawable.Drawable;
import android.util.Log;
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
import com.melodie.player.data.entity.Album;

public class AlbumAdapter extends ListAdapter<Album, AlbumAdapter.VH> {

    private static final String TAG = "AlbumAdapter";
    private static final String NO_REMOTE_COVER = "__NO_REMOTE_COVER__";

    public interface OnAlbumClick {
        void onClick(Album album);
    }

    public interface OnMissingCover {
        void onMissing(Album album);
    }

    private final OnAlbumClick listener;
    private final OnMissingCover missingCoverListener;

    public AlbumAdapter(OnAlbumClick listener, OnMissingCover missingCoverListener) {
        super(DIFF);
        this.listener = listener;
        this.missingCoverListener = missingCoverListener;
    }

    private static final DiffUtil.ItemCallback<Album> DIFF = new DiffUtil.ItemCallback<Album>() {
        @Override
        public boolean areItemsTheSame(@NonNull Album o, @NonNull Album n) {
            return o.id == n.id;
        }

        @Override
        public boolean areContentsTheSame(@NonNull Album o, @NonNull Album n) {
            boolean sameArtist = (o.artist == null && n.artist == null)
                    || (o.artist != null && o.artist.equals(n.artist));
            boolean sameCover = (o.cover == null && n.cover == null)
                    || (o.cover != null && o.cover.equals(n.cover));
            return o.name.equals(n.name) && o.count == n.count && sameArtist && sameCover;
        }
    };

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_album, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Album a = getItem(position);
        Log.d(TAG, "Album affiche: id=" + a.id + ", artist=" + a.artist + ", album=" + a.name);
        String artistText = a.artist != null && !a.artist.trim().isEmpty()
                ? a.artist
                : h.itemView.getContext().getString(R.string.unknown_artist);
        h.title.setText(artistText);
        h.subtitle.setText(a.name != null ? a.name : "");
        Object source = toGlideSource(a.cover);
        boolean shouldAttemptRemoteCover = shouldAttemptRemoteCover(a.cover);
        Glide.with(h.cover)
                .load(source)
                .placeholder(R.drawable.ic_album)
                .error(R.drawable.ic_album)
                .listener(new RequestListener<Drawable>() {
                    @Override
                    public boolean onLoadFailed(@Nullable GlideException e, Object model,
                                                Target<Drawable> target, boolean isFirstResource) {
                        if (shouldAttemptRemoteCover && missingCoverListener != null) {
                            missingCoverListener.onMissing(a);
                        }
                        return false;
                    }

                    @Override
                    public boolean onResourceReady(Drawable resource, Object model,
                                                   Target<Drawable> target, DataSource dataSource,
                                                   boolean isFirstResource) {
                        return false;
                    }
                })
                .into(h.cover);
        h.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onClick(a);
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

    private boolean shouldAttemptRemoteCover(String cover) {
        if (cover == null) return true;
        String trimmed = cover.trim();
        if (trimmed.isEmpty()) return true;
        if (NO_REMOTE_COVER.equals(trimmed)) return false;
        return trimmed.startsWith("content://");
    }

    static class VH extends RecyclerView.ViewHolder {
        final ImageView cover;
        final TextView title;
        final TextView subtitle;

        VH(@NonNull View itemView) {
            super(itemView);
            cover = itemView.findViewById(R.id.cover);
            title = itemView.findViewById(R.id.title);
            subtitle = itemView.findViewById(R.id.subtitle);
        }
    }
}

