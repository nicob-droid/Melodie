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

public class LibraryAlbumListAdapter extends ListAdapter<Album, LibraryAlbumListAdapter.VH> {

    private static final String TAG = "LibraryAlbumList";
    private static final String NO_REMOTE_COVER = "__NO_REMOTE_COVER__";

    public interface OnAlbumClick {
        void onClick(Album album);
    }

    public interface OnMissingCover {
        void onMissing(Album album);
    }

    private final OnAlbumClick clickListener;
    private final OnMissingCover missingCoverListener;

    public LibraryAlbumListAdapter(OnAlbumClick clickListener, OnMissingCover missingCoverListener) {
        super(DIFF);
        this.clickListener = clickListener;
        this.missingCoverListener = missingCoverListener;
    }

    private static final DiffUtil.ItemCallback<Album> DIFF = new DiffUtil.ItemCallback<Album>() {
        @Override
        public boolean areItemsTheSame(@NonNull Album oldItem, @NonNull Album newItem) {
            return oldItem.id == newItem.id;
        }

        @Override
        public boolean areContentsTheSame(@NonNull Album oldItem, @NonNull Album newItem) {
            boolean sameArtist = (oldItem.artist == null && newItem.artist == null)
                    || (oldItem.artist != null && oldItem.artist.equals(newItem.artist));
            boolean sameAlbum = (oldItem.name == null && newItem.name == null)
                    || (oldItem.name != null && oldItem.name.equals(newItem.name));
            boolean sameCover = (oldItem.cover == null && newItem.cover == null)
                    || (oldItem.cover != null && oldItem.cover.equals(newItem.cover));
            boolean sameRelease = (oldItem.releaseDate == null && newItem.releaseDate == null)
                    || (oldItem.releaseDate != null && oldItem.releaseDate.equals(newItem.releaseDate));
            return sameArtist && sameAlbum && sameCover && sameRelease && oldItem.count == newItem.count;
        }
    };

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_album_list, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        Album album = getItem(position);
        Log.d(TAG, "Album affiche: id=" + album.id + ", artist=" + album.artist + ", album=" + album.name);

        String artistText = (album.artist != null && !album.artist.trim().isEmpty())
                ? album.artist
                : holder.itemView.getContext().getString(R.string.unknown_artist);
        holder.artist.setText(artistText);
        holder.album.setText(album.name != null ? album.name : "");

        String dateText = (album.releaseDate != null && !album.releaseDate.trim().isEmpty())
                ? album.releaseDate
                : holder.itemView.getContext().getString(R.string.release_date_unknown);
        holder.releaseDate.setText(dateText);

        Object source = toGlideSource(album.cover);
        boolean shouldAttemptRemoteCover = shouldAttemptRemoteCover(album.cover);

        Glide.with(holder.cover)
                .load(source)
                .placeholder(R.drawable.ic_album)
                .error(R.drawable.ic_album)
                .listener(new RequestListener<Drawable>() {
                    @Override
                    public boolean onLoadFailed(@Nullable GlideException e, Object model,
                                                Target<Drawable> target, boolean isFirstResource) {
                        if (shouldAttemptRemoteCover && missingCoverListener != null) {
                            missingCoverListener.onMissing(album);
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
                .into(holder.cover);

        holder.itemView.setOnClickListener(v -> {
            if (clickListener != null) {
                clickListener.onClick(album);
            }
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
        // Le sentinel indique qu'on a déjà cherché sans résultat : on n'essaie plus.
        if (NO_REMOTE_COVER.equals(trimmed)) return false;
        // Une URL HTTP déjà connue : pas la peine de re-chercher même si Glide échoue.
        if (trimmed.startsWith("http")) return false;
        // Albumart embarqué (content://) : si Glide échoue, on tente une pochette distante.
        return trimmed.startsWith("content://");
    }

    static class VH extends RecyclerView.ViewHolder {
        final ImageView cover;
        final TextView artist;
        final TextView album;
        final TextView releaseDate;

        VH(@NonNull View itemView) {
            super(itemView);
            cover = itemView.findViewById(R.id.cover);
            artist = itemView.findViewById(R.id.artist);
            album = itemView.findViewById(R.id.album);
            releaseDate = itemView.findViewById(R.id.release_date);
        }
    }
}

