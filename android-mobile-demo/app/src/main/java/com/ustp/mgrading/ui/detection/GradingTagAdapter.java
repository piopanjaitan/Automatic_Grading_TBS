package com.ustp.mgrading.ui.detection;

import android.graphics.BitmapFactory;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.ustp.mgrading.data.local.GradingTag;
import com.ustp.mgrading.databinding.ItemGradingTagBinding;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class GradingTagAdapter extends RecyclerView.Adapter<GradingTagAdapter.TagViewHolder> {
    private final List<GradingTag> tags = new ArrayList<>();
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.US);

    public void submit(List<GradingTag> newTags) {
        tags.clear();
        if (newTags != null) {
            tags.addAll(newTags);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public TagViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemGradingTagBinding binding = ItemGradingTagBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false);
        return new TagViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull TagViewHolder holder, int position) {
        holder.bind(tags.get(position));
    }

    @Override
    public int getItemCount() {
        return tags.size();
    }

    class TagViewHolder extends RecyclerView.ViewHolder {
        private final ItemGradingTagBinding binding;

        TagViewHolder(ItemGradingTagBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(GradingTag tag) {
            binding.tagTitleText.setText(tag.getTagCode() + " - " + tag.getLabel());
            String syncText = "Sync " + tag.getSyncStatus();
            if ("FAILED".equals(tag.getSyncStatus()) && tag.getSyncError() != null && !tag.getSyncError().trim().isEmpty()) {
                syncText += ": " + tag.getSyncError();
            }
            binding.tagMetaText.setText(String.format(Locale.US, "Confidence %.0f%% · %s",
                    tag.getConfidence() * 100f, syncText));
            binding.tagSeenText.setText(String.format(Locale.US, "Terlihat %dx, terakhir %s",
                    tag.getSeenCount(), timeFormat.format(new Date(tag.getLastSeenAt()))));
            binding.thumbImage.setImageBitmap(BitmapFactory.decodeFile(tag.getCropPath()));
        }
    }
}
