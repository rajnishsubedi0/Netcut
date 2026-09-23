package com.rkant.netcut;

import android.content.Context;
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

public class SavedDeviceAdapter extends RecyclerView.Adapter<SavedDeviceAdapter.ViewHolder> {

    public interface OnSavedDeviceActionListener {
        void onRemoveSavedClick(Device device);
        void onSavedDeviceClick(Device device);
    }

    private List<Device> allDevices = new ArrayList<>();
    private List<Device> filteredDevices = new ArrayList<>();
    private String query = "";
    private final OnSavedDeviceActionListener listener;

    public SavedDeviceAdapter(List<Device> devices, OnSavedDeviceActionListener listener) {
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
                .inflate(R.layout.item_saved_device, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Device d = filteredDevices.get(position);
        final Context context = holder.itemView.getContext();

        holder.tvIp.setText(d.getIp() != null && !d.getIp().trim().isEmpty()
                ? d.getIp()
                : "N/A");

        holder.tvMac.setText(d.getMac() != null && !d.getMac().trim().isEmpty()
                ? d.getMac()
                : "N/A");

        holder.tvLastSeen.setText("Last seen: " + Device.formatLastSeen(d.getLastSeen()));

        holder.tvStatus.setText("SAVED");
        holder.tvStatus.setTextColor(ContextCompat.getColor(context, R.color.warning));

        String displayName = d.getName();
        if (d.isSaved()) displayName += " ⭐";
        if (d.isProtected()) displayName += " 🛡";
        if (d.isBanned()) displayName += " 🚫";

        holder.tvName.setText(displayName);

        if (d.isBanned()) {
            holder.tvName.setTextColor(ContextCompat.getColor(context, R.color.error));
        } else if (d.isProtected()) {
            holder.tvName.setTextColor(ContextCompat.getColor(context, R.color.primary));
        } else {
            holder.tvName.setTextColor(ContextCompat.getColor(context, R.color.text_primary));
        }

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onSavedDeviceClick(d);
            }
        });

        holder.btnRemoveSaved.setOnClickListener(v -> {
            if (listener != null) {
                listener.onRemoveSavedClick(d);
            }
        });
    }

    @Override
    public int getItemCount() {
        return filteredDevices.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvName, tvIp, tvMac, tvStatus, tvLastSeen;
        MaterialButton btnRemoveSaved;

        ViewHolder(View v) {
            super(v);
            tvName = v.findViewById(R.id.tv_name);
            tvIp = v.findViewById(R.id.tv_ip);
            tvMac = v.findViewById(R.id.tv_mac);
            tvStatus = v.findViewById(R.id.tv_status);
            tvLastSeen = v.findViewById(R.id.tv_last_seen);
            btnRemoveSaved = v.findViewById(R.id.btn_remove_saved);
        }
    }
}