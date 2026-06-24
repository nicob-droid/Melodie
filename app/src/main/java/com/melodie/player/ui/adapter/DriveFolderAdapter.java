package com.melodie.player.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.melodie.player.R;
import com.melodie.player.data.entity.DriveFolder;

public class DriveFolderAdapter extends ListAdapter<DriveFolder, DriveFolderAdapter.ViewHolder> {

    public interface OnFolderClickListener {
        void onFolderClick(DriveFolder folder);
        void onFolderSelected(DriveFolder folder);
    }

    private final OnFolderClickListener listener;

    public DriveFolderAdapter(OnFolderClickListener listener) {
        super(new DiffUtil.ItemCallback<DriveFolder>() {
            @Override
            public boolean areItemsTheSame(@NonNull DriveFolder oldItem, @NonNull DriveFolder newItem) {
                return oldItem.driveId.equals(newItem.driveId);
            }

            @Override
            public boolean areContentsTheSame(@NonNull DriveFolder oldItem, @NonNull DriveFolder newItem) {
                return oldItem.selected == newItem.selected && oldItem.name.equals(newItem.name);
            }
        });
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_drive_folder, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        DriveFolder folder = getItem(position);
        holder.bind(folder, listener);
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        private final TextView folderName;
        private final CheckBox checkBox;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            folderName = itemView.findViewById(R.id.folder_name);
            checkBox = itemView.findViewById(R.id.folder_checkbox);
        }

        void bind(DriveFolder folder, OnFolderClickListener listener) {
            folderName.setText(folder.name);
            checkBox.setChecked(folder.selected);

            checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
                folder.selected = isChecked;
                listener.onFolderSelected(folder);
            });

            itemView.setOnClickListener(v -> listener.onFolderClick(folder));
        }
    }
}

