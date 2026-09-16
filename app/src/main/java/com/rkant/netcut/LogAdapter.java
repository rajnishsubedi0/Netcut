package com.rkant.netcut;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class LogAdapter extends RecyclerView.Adapter<LogAdapter.ViewHolder> {

    private List<LogStore.LogItem> logs = new ArrayList<>();

    public void updateLogs(List<LogStore.LogItem> newLogs) {
        logs.clear();
        logs.addAll(newLogs);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_log, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        LogStore.LogItem item = logs.get(position);
        holder.tvLog.setText(item.getFormatted());

        switch (item.level) {
            case "ERROR":
                holder.tvLog.setTextColor(0xFFFF5252);
                break;
            case "WARN":
                holder.tvLog.setTextColor(0xFFFFB300);
                break;
            default:
                holder.tvLog.setTextColor(Color.BLACK);
                break;
        }
    }

    @Override
    public int getItemCount() {
        return logs.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvLog;

        ViewHolder(View v) {
            super(v);
            tvLog = v.findViewById(R.id.tv_log);
        }
    }
}