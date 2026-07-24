package com.melodie.player.ui.library;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.melodie.player.R;
import com.melodie.player.data.entity.Album;

import java.util.function.Consumer;

/** Liste des albums masqués avec un bouton pour les réafficher. */
public class HiddenAlbumsAdapter extends ListAdapter<Album, HiddenAlbumsAdapter.VH> {

    private final Consumer<Album> onUnhide;

    public HiddenAlbumsAdapter(Consumer<Album> onUnhide) {
        super(DIFF);
        this.onUnhide = onUnhide;
    }

    private static final DiffUtil.ItemCallback<Album> DIFF = new DiffUtil.ItemCallback<Album>() {
        @Override
        public boolean areItemsTheSame(@NonNull Album oldItem, @NonNull Album newItem) {
            return oldItem.id == newItem.id;
        }

        @Override
        public boolean areContentsTheSame(@NonNull Album oldItem, @NonNull Album newItem) {
            return oldItem.id == newItem.id
                    && equalsSafe(oldItem.name, newItem.name)
                    && equalsSafe(oldItem.artist, newItem.artist)
                    && oldItem.hidden == newItem.hidden;
        }

        private boolean equalsSafe(String a, String b) {
            return a == null ? b == null : a.equals(b);
        }
    };

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_hidden_album, parent, false);
        return new VH(view);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        Album album = getItem(position);
        String name = album.name != null && !album.name.trim().isEmpty()
                ? album.name
                : holder.itemView.getContext().getString(R.string.unknown_album);
        String artist = album.artist != null && !album.artist.trim().isEmpty()
                ? album.artist
                : holder.itemView.getContext().getString(R.string.unknown_artist);
        holder.name.setText(name);
        holder.artist.setText(artist);
        holder.unhide.setOnClickListener(v -> {
            if (onUnhide != null) onUnhide.accept(album);
        });
    }

    static class VH extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView artist;
        final MaterialButton unhide;

        VH(@NonNull View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.album_name);
            artist = itemView.findViewById(R.id.album_artist);
            unhide = itemView.findViewById(R.id.btn_unhide);
        }
    }
}

