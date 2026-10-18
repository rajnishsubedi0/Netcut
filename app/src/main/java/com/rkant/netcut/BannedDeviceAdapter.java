package com.rkant.netcut;

import android.content.Context;
import android.graphics.Paint;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.List;

public class BannedDeviceAdapter extends RecyclerView.Adapter<BannedDeviceAdapter.ViewHolder> {

    public interface OnBannedDeviceActionListener {
        void onUnbanClick(Device device);
        void onBannedDeviceClick(Device device);
    }

    private List<Device> allDevices = new ArrayList<>();
    private List<Device> filteredDevices = new ArrayList<>();
    private String query = "";
    private final OnBannedDeviceActionListener listener;

    public BannedDeviceAdapter(List<Device> devices, OnBannedDeviceActionListener listener) {
        this.listener = listener;
        updateDevices(devices);
    }

    public void updateDevices(List<Device> newDevices) {
        allDevices.clear();
        if (newDevices != null) {
            allDevices.addAll(newDevices);
        }
        applyFilter();
    }

    public void setFilter(String text) {
        query = text == null ? "" : text.trim().toLowerCase();
        applyFilter();
    }

    private void applyFilter() {
        filteredDevices.clear();
        for (Device d : allDevices) {
            if (query.isEmpty()) {
                filteredDevices.add(d);
                continue;
            }
            String name = d.getName() == null ? "" : d.getName().toLowerCase();
            String ip = d.getIp() == null ? "" : d.getIp().toLowerCase();
            String mac = d.getMac() == null ? "" : d.getMac().toLowerCase();
            if (name.contains(query) || ip.contains(query) || mac.contains(query)) {
                filteredDevices.add(d);
            }
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_banned_device, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Device d = filteredDevices.get(position);
        final Context context = holder.itemView.getContext();

        holder.tvIp.setText(d.getIp() != null && !d.getIp().trim().isEmpty() ? d.getIp() : "N/A");

        holder.tvIp.setTextColor(ContextCompat.getColor(context, R.color.error));
        holder.tvIp.setPaintFlags(holder.tvIp.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);

        holder.tvMac.setText(d.getMac() != null && !d.getMac().trim().isEmpty() ? d.getMac() : "N/A");
        holder.tvLastSeen.setText("Last seen: " + Device.formatLastSeen(d.getLastSeen()));

        if (d.isSaved()) {
            holder.tvStatus.setText("BANNED • SAVED");
        } else {
            holder.tvStatus.setText("BANNED");
        }
        holder.tvStatus.setTextColor(ContextCompat.getColor(context, R.color.error));

        String rawName = d.getRawName();
        if (rawName != null && !rawName.trim().isEmpty() && !rawName.trim().equalsIgnoreCase("Unnamed")) {
            holder.tvNameWatermark.setText(rawName.trim());
            holder.tvNameWatermark.setVisibility(View.VISIBLE);
        } else {
            holder.tvNameWatermark.setText("");
            holder.tvNameWatermark.setVisibility(View.GONE);
        }

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onBannedDeviceClick(d);
        });

        holder.btnUnban.setOnClickListener(v -> {
            if (listener != null) listener.onUnbanClick(d);
        });
    }

    @Override
    public int getItemCount() {
        return filteredDevices.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvNameWatermark, tvIp, tvMac, tvStatus, tvLastSeen;
        MaterialButton btnUnban;

        ViewHolder(View v) {
            super(v);
            tvNameWatermark = v.findViewById(R.id.tv_name_watermark);
            tvIp = v.findViewById(R.id.tv_ip);
            tvMac = v.findViewById(R.id.tv_mac);
            tvStatus = v.findViewById(R.id.tv_status);
            tvLastSeen = v.findViewById(R.id.tv_last_seen);
            btnUnban = v.findViewById(R.id.btn_unban);
        }
    }
}