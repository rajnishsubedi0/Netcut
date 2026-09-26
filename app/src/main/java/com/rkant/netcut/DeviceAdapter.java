package com.rkant.netcut;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class DeviceAdapter extends RecyclerView.Adapter<DeviceAdapter.ViewHolder> {

    public interface OnDeviceActionListener {
        void onBanClick(Device device);
        void onPingClick(Device device);
        void onDetailsClick(Device device);
        void onSelectionChanged();
    }

    private List<Device> allDevices = new ArrayList<>();
    private List<Device> filteredDevices = new ArrayList<>();
    private final Set<String> selectedMacs = new HashSet<>();
    private String query = "";
    private final OnDeviceActionListener listener;

    public DeviceAdapter(List<Device> devices, OnDeviceActionListener listener) {
        this.listener = listener;
        updateDevices(devices);
    }

    public void updateDevices(List<Device> newDevices) {
        allDevices.clear();
        if (newDevices != null) {
            allDevices.addAll(newDevices);
        }

        Set<String> validMacs = new HashSet<>();
        for (Device d : allDevices) {
            if (d.getMac() != null && !d.getMac().isEmpty()) {
                validMacs.add(d.getMac());
            }
        }
        selectedMacs.retainAll(validMacs);
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

    public void toggleSelection(String mac) {
        if (mac == null || mac.isEmpty()) return;

        if (selectedMacs.contains(mac)) {
            selectedMacs.remove(mac);
        } else {
            selectedMacs.add(mac);
        }
        notifyDataSetChanged();
    }

    public void clearSelection() {
        selectedMacs.clear();
        notifyDataSetChanged();
        if (listener != null) {
            listener.onSelectionChanged();
        }
    }

    public boolean isSelectionActive() {
        return !selectedMacs.isEmpty();
    }

    public int getSelectedItemCount() {
        return selectedMacs.size();
    }

    public List<Device> getSelectedDevices() {
        List<Device> selected = new ArrayList<>();
        for (Device d : allDevices) {
            if (selectedMacs.contains(d.getMac())) {
                selected.add(d);
            }
        }
        return selected;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_device, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Device d = filteredDevices.get(position);
        final Context context = holder.itemView.getContext();
        final String mac = d.getMac() == null ? "" : d.getMac();

        holder.tvIp.setText(d.getIp() != null ? d.getIp() : "N/A");
        holder.tvMac.setText(d.getMac() != null ? d.getMac() : "N/A");
        holder.tvLastSeen.setText("Last seen: " + Device.formatLastSeen(d.getLastSeen()));

        boolean isOnline = d.isOnline();
        holder.tvStatus.setText(isOnline ? "ONLINE" : "OFFLINE");
        holder.tvStatus.setTextColor(ContextCompat.getColor(
                context,
                isOnline ? R.color.success : R.color.text_tertiary
        ));

        String displayName = d.getName() != null && !d.getName().isEmpty()
                ? d.getName()
                : "Unnamed Device";

        if (d.isProtected()) displayName += " 🛡";
        if (d.isBanned()) displayName += " 🚫";

        holder.tvName.setText(displayName);

        // Brand: prefer the value resolved during the scan (offline table or
        // online lookup); fall back to the offline table for safety.
        String vendor = d.getVendor();
        if (vendor == null || vendor.isEmpty()) {
            vendor = OuiLookup.describe(d.getMac());
        }
        if (vendor != null && !vendor.isEmpty()) {
            holder.tvVendor.setText(vendor);
            holder.tvVendor.setVisibility(View.VISIBLE);
        } else {
            holder.tvVendor.setVisibility(View.GONE);
        }

        if (d.isBanned()) {
            holder.tvName.setTextColor(ContextCompat.getColor(context, R.color.error));
        } else if (d.isProtected()) {
            holder.tvName.setTextColor(ContextCompat.getColor(context, R.color.primary));
        } else {
            holder.tvName.setTextColor(ContextCompat.getColor(context, R.color.text_primary));
        }

        boolean selected = selectedMacs.contains(mac);
        holder.itemView.setSelected(selected);
        holder.itemView.setActivated(selected);
        holder.itemView.refreshDrawableState();

        holder.itemView.setOnClickListener(v -> {
            if (isSelectionActive()) {
                toggleSelection(mac);
                if (listener != null) listener.onSelectionChanged();
            } else {
                if (listener != null) listener.onDetailsClick(d);
            }
        });

        holder.itemView.setOnLongClickListener(v -> {
            if (!mac.isEmpty()) {
                toggleSelection(mac);
                if (listener != null) listener.onSelectionChanged();
            }
            return true;
        });

        boolean isProtected = d.isProtected();
        boolean isBanned = d.isBanned();

        if (isProtected) {
            holder.btnBan.setText("Protected");
            holder.btnBan.setEnabled(false);
            holder.btnBan.setBackgroundTintList(ColorStateList.valueOf(
                    ContextCompat.getColor(context, R.color.surface_variant)));
            holder.btnBan.setTextColor(ContextCompat.getColor(context, R.color.text_tertiary));
        } else {
            holder.btnBan.setEnabled(true);

            if (isBanned) {
                holder.btnBan.setText("Unban");
                holder.btnBan.setBackgroundTintList(ColorStateList.valueOf(
                        ContextCompat.getColor(context, R.color.success)));
                holder.btnBan.setTextColor(ContextCompat.getColor(context, R.color.on_success));
            } else {
                holder.btnBan.setText("Ban");
                holder.btnBan.setBackgroundTintList(ColorStateList.valueOf(
                        ContextCompat.getColor(context, R.color.error)));
                holder.btnBan.setTextColor(ContextCompat.getColor(context, R.color.on_error));
            }
        }

        holder.btnBan.setOnClickListener(v -> {
            if (listener != null) listener.onBanClick(d);
        });
    }

    @Override
    public int getItemCount() {
        return filteredDevices.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvName, tvIp, tvMac, tvStatus, tvLastSeen, tvVendor;
        MaterialButton btnBan;

        ViewHolder(View v) {
            super(v);
            tvName = v.findViewById(R.id.tv_name);
            tvIp = v.findViewById(R.id.tv_ip);
            tvMac = v.findViewById(R.id.tv_mac);
            tvStatus = v.findViewById(R.id.tv_status);
            tvLastSeen = v.findViewById(R.id.tv_last_seen);
            tvVendor = v.findViewById(R.id.tv_vendor);
            btnBan = v.findViewById(R.id.btn_ban);
        }
    }
}