package com.rkant.netcut;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class BannedDeviceAdapter extends RecyclerView.Adapter<BannedDeviceAdapter.ViewHolder> {

    public interface OnUnbanListener {
        void onUnbanClick(Device device);
    }

    private List<Device> allDevices = new ArrayList<>();
    private List<Device> filteredDevices = new ArrayList<>();
    private String query = "";
    private final OnUnbanListener listener;

    public BannedDeviceAdapter(List<Device> devices, OnUnbanListener listener) {
        this.listener = listener;
        updateDevices(devices);
    }

    public void updateDevices(List<Device> newDevices) {
        allDevices.clear();
        allDevices.addAll(newDevices);
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

            String name = d.getName().toLowerCase();
            String ip = d.getIp().toLowerCase();
            String mac = d.getMac().toLowerCase();

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

        holder.tvName.setText(d.getName());
        holder.tvIp.setText("IP: " + d.getIp());
        holder.tvMac.setText("MAC: " + d.getMac());
        holder.tvLastSeen.setText("Last seen: " + Device.formatLastSeen(d.getLastSeen()));

        holder.btnUnban.setOnClickListener(v -> {
            if (listener != null) listener.onUnbanClick(d);
        });
    }

    @Override
    public int getItemCount() {
        return filteredDevices.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvName, tvIp, tvMac, tvLastSeen;
        Button btnUnban;

        ViewHolder(View v) {
            super(v);
            tvName = v.findViewById(R.id.tv_name);
            tvIp = v.findViewById(R.id.tv_ip);
            tvMac = v.findViewById(R.id.tv_mac);
            tvLastSeen = v.findViewById(R.id.tv_last_seen);
            btnUnban = v.findViewById(R.id.btn_unban);
        }
    }
}