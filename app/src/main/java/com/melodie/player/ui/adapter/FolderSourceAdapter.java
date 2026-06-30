package com.melodie.player.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.switchmaterial.SwitchMaterial;
import com.melodie.player.R;
import com.melodie.player.data.entity.FolderSource;

    public class FolderSourceAdapter extends ListAdapter<FolderSource, RecyclerView.ViewHolder> {

    public interface OnFolderSourceActionListener {
        void onEnabledChanged(FolderSource source, boolean enabled);
        void onRemoveClicked(FolderSource source);
    }

    private final OnFolderSourceActionListener listener;

    public FolderSourceAdapter(OnFolderSourceActionListener listener) {
        super(new DiffUtil.ItemCallback<>() {
            @Override
            public boolean areItemsTheSame(@NonNull FolderSource oldItem, @NonNull FolderSource newItem) {
                return oldItem.id == newItem.id;
            }

            @Override
            public boolean areContentsTheSame(@NonNull FolderSource oldItem, @NonNull FolderSource newItem) {
                return oldItem.enabled == newItem.enabled
                        && oldItem.displayName.equals(newItem.displayName)
                        && oldItem.treeUri.equals(newItem.treeUri);
            }
        });
        this.listener = listener;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_folder_source, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        ((ViewHolder) holder).bind(getItem(position), listener);
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        private final TextView title;
        private final TextView subtitle;
        private final SwitchMaterial enabledSwitch;
        private final Button removeButton;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.source_name);
            subtitle = itemView.findViewById(R.id.source_uri);
            enabledSwitch = itemView.findViewById(R.id.source_enabled);
            removeButton = itemView.findViewById(R.id.source_remove);
        }

        void bind(FolderSource source, OnFolderSourceActionListener listener) {
            boolean isDriveSource = source.treeUri.startsWith("drive://folder/");

            title.setText(source.displayName);
            subtitle.setText(isDriveSource ? "Google Drive" : source.treeUri);
            enabledSwitch.setOnCheckedChangeListener(null);
            enabledSwitch.setChecked(source.enabled);
            enabledSwitch.setText(source.enabled
                    ? itemView.getContext().getString(R.string.folders_source_enabled)
                    : itemView.getContext().getString(R.string.folders_source_disabled));
            enabledSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
                enabledSwitch.setText(isChecked
                        ? itemView.getContext().getString(R.string.folders_source_enabled)
                        : itemView.getContext().getString(R.string.folders_source_disabled));
                listener.onEnabledChanged(source, isChecked);
            });
            removeButton.setOnClickListener(v -> listener.onRemoveClicked(source));
        }
    }
}

