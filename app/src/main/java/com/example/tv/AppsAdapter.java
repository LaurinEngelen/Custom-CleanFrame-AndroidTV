package com.example.tv;

import android.content.Context;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.cardview.widget.CardView;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class AppsAdapter extends RecyclerView.Adapter<AppsAdapter.ViewHolder> {

    public interface OnAppFocusListener {
        void onAppFocused(AppInfo app, View view, int position);
        void onAppClick(AppInfo app);
    }

    private final Context context;
    private final List<AppInfo> apps;
    private final OnAppFocusListener listener;
    private final float elevationPx;

    public AppsAdapter(Context context, List<AppInfo> apps, OnAppFocusListener listener) {
        this.context = context;
        this.apps = apps;
        this.listener = listener;
        this.elevationPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8, context.getResources().getDisplayMetrics());
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(context).inflate(R.layout.item_app_tile, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        AppInfo app = apps.get(position);
        holder.appTitle.setText(app.label);

        if (app.banner != null) {
            holder.appBanner.setPadding(0, 0, 0, 0);
            holder.appBanner.setImageDrawable(app.banner);
            holder.appBanner.setScaleType(ImageView.ScaleType.CENTER_CROP);
        } else if (app.icon != null) {
            holder.appBanner.setPadding(38, 10, 38, 10);
            holder.appBanner.setImageDrawable(app.icon);
            holder.appBanner.setScaleType(ImageView.ScaleType.FIT_CENTER);
        }

        holder.tileFrame.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                if (holder.tileFrame instanceof CardView) {
                    ((CardView) holder.tileFrame).setCardElevation(elevationPx);
                }
                holder.tileFrame.animate()
                        .scaleX(1.08f)
                        .scaleY(1.08f)
                        .translationZ(12f)
                        .setDuration(160)
                        .setInterpolator(new DecelerateInterpolator(1.5f))
                        .start();
                holder.appTitle.setVisibility(View.VISIBLE);
                if (listener != null) {
                    listener.onAppFocused(app, holder.tileFrame, holder.getAdapterPosition());
                }
            } else {
                if (holder.tileFrame instanceof CardView) {
                    ((CardView) holder.tileFrame).setCardElevation(0f);
                }
                holder.tileFrame.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .translationZ(0f)
                        .setDuration(140)
                        .setInterpolator(new DecelerateInterpolator(1.5f))
                        .start();
                holder.appTitle.setVisibility(View.INVISIBLE);
            }
        });

        holder.tileFrame.setOnClickListener(v -> {
            if (listener != null) {
                listener.onAppClick(app);
            }
        });
    }

    @Override
    public int getItemCount() {
        return apps.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        View tileFrame;
        ImageView appBanner;
        TextView appTitle;

        ViewHolder(View itemView) {
            super(itemView);
            tileFrame = itemView.findViewById(R.id.tile_frame);
            appBanner = itemView.findViewById(R.id.app_logo);
            appTitle = itemView.findViewById(R.id.app_title);
        }
    }
}
