package com.melodie.player.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.melodie.player.R;
import com.melodie.player.data.entity.DriveAudio;

public class DriveAudioAdapter extends ListAdapter<DriveAudio, DriveAudioAdapter.ViewHolder> {

    public interface OnAudioClickListener {
        void onAudioClick(DriveAudio audio);
    }

    private final OnAudioClickListener listener;

    public DriveAudioAdapter(OnAudioClickListener listener) {
        super(new DiffUtil.ItemCallback<DriveAudio>() {
            @Override
            public boolean areItemsTheSame(@NonNull DriveAudio oldItem, @NonNull DriveAudio newItem) {
                return oldItem.fileId.equals(newItem.fileId);
            }

            @Override
            public boolean areContentsTheSame(@NonNull DriveAudio oldItem, @NonNull DriveAudio newItem) {
                return oldItem.fileName.equals(newItem.fileName) && oldItem.downloaded == newItem.downloaded;
            }
        });
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_drive_audio, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        DriveAudio audio = getItem(position);
        holder.bind(audio, listener);
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        private final TextView audioName;
        private final ImageView downloadIcon;
        private final TextView fileSize;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            audioName = itemView.findViewById(R.id.audio_name);
            downloadIcon = itemView.findViewById(R.id.download_icon);
            fileSize = itemView.findViewById(R.id.file_size);
        }

        void bind(DriveAudio audio, OnAudioClickListener listener) {
            audioName.setText(audio.fileName);
            fileSize.setText(formatFileSize(audio.fileSize));

            if (audio.downloaded) {
                downloadIcon.setImageResource(android.R.drawable.ic_menu_view);
            } else {
                downloadIcon.setImageResource(android.R.drawable.ic_menu_save);
            }

            itemView.setOnClickListener(v -> listener.onAudioClick(audio));
        }

        private String formatFileSize(long bytes) {
            if (bytes < 1024) return bytes + " B";
            int z = (63 - Long.numberOfLeadingZeros(bytes)) / 10;
            return String.format("%.1f %sB", (double) bytes / (1L << (z * 10)), " KMGTPE".charAt(z));
        }
    }
}

