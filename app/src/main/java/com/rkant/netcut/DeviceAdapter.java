package com.rkant.netcut;

import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.checkbox.MaterialCheckBox;

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
            if (d.getMac() != null) validMacs.add(d.getMac());
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
            // Null safety checks to prevent crashes
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
        View contextView = holder.itemView;

        // Text setup with null safety
        holder.tvIp.setText("IP: " + (d.getIp() != null ? d.getIp() : "N/A"));
        holder.tvMac.setText("MAC: " + (d.getMac() != null ? d.getMac() : "N/A"));
        holder.tvLastSeen.setText("Last seen: " + Device.formatLastSeen(d.getLastSeen()));

        boolean isOnline = d.isOnline();
        holder.tvStatus.setText(isOnline ? "ONLINE" : "OFFLINE");

        // Dynamic Status Badge Color
        if (isOnline) {
            holder.tvStatus.setTextColor(ContextCompat.getColor(contextView.getContext(), R.color.success));
        } else {
            holder.tvStatus.setTextColor(ContextCompat.getColor(contextView.getContext(), R.color.text_tertiary));
        }

        // Name setup with emojis
        String displayName = d.getName() != null && !d.getName().isEmpty() ? d.getName() : "Unnamed Device";
        if (d.isProtected()) displayName += " 🛡";
        if (d.isBanned()) displayName += " 🚫";
        holder.tvName.setText(displayName);

        // Name text color based on state (Using theme colors instead of hardcoded hex)
        if (d.isBanned()) {
            holder.tvName.setTextColor(ContextCompat.getColor(contextView.getContext(), R.color.error));
        } else if (d.isProtected()) {
            holder.tvName.setTextColor(ContextCompat.getColor(contextView.getContext(), R.color.primary));
        } else {
            holder.tvName.setTextColor(ContextCompat.getColor(contextView.getContext(), R.color.text_primary));
        }

        // Checkbox logic
        boolean selected = selectedMacs.contains(d.getMac());
        holder.cbSelect.setOnCheckedChangeListener(null); // Prevent trigger loop
        holder.cbSelect.setChecked(selected);
        holder.cbSelect.setOnCheckedChangeListener((buttonView, isChecked) -> {
            toggleSelection(d.getMac());
            if (listener != null) listener.onSelectionChanged();
        });

        holder.itemView.setOnLongClickListener(v -> {
            toggleSelection(d.getMac());
            if (listener != null) listener.onSelectionChanged();
            return true;
        });

        // Ban Button logic (Using MaterialButton and theme colors)
        boolean isProtected = d.isProtected();
        boolean isBanned = d.isBanned();

        if (isProtected) {
            holder.btnBan.setText("Protected");
            holder.btnBan.setEnabled(false);
            holder.btnBan.setBackgroundTintList(ColorStateList.valueOf(
                    ContextCompat.getColor(contextView.getContext(), R.color.surface_variant)));
            holder.btnBan.setTextColor(ContextCompat.getColor(contextView.getContext(), R.color.text_tertiary));
        } else {
            holder.btnBan.setEnabled(true);
            if (isBanned) {
                holder.btnBan.setText("Unban");
                holder.btnBan.setBackgroundTintList(ColorStateList.valueOf(
                        ContextCompat.getColor(contextView.getContext(), R.color.success)));
                holder.btnBan.setTextColor(ContextCompat.getColor(contextView.getContext(), R.color.on_success));
            } else {
                holder.btnBan.setText("Ban");
                holder.btnBan.setBackgroundTintList(ColorStateList.valueOf(
                        ContextCompat.getColor(contextView.getContext(), R.color.error)));
                holder.btnBan.setTextColor(ContextCompat.getColor(contextView.getContext(), R.color.on_error));
            }
        }

        // Click Listeners
        holder.btnBan.setOnClickListener(v -> {
            if (listener != null) listener.onBanClick(d);
        });
        holder.btnPing.setOnClickListener(v -> {
            if (listener != null) listener.onPingClick(d);
        });
        holder.btnDetails.setOnClickListener(v -> {
            if (listener != null) listener.onDetailsClick(d);
        });
        holder.tvName.setOnClickListener(v -> {
            if (listener != null) listener.onDetailsClick(d);
        });
    }

    @Override
    public int getItemCount() {
        return filteredDevices.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        MaterialCheckBox cbSelect;
        TextView tvName, tvIp, tvMac, tvStatus, tvLastSeen;
        MaterialButton btnPing, btnBan, btnDetails;

        ViewHolder(View v) {
            super(v);
            cbSelect = v.findViewById(R.id.cb_select);
            tvName = v.findViewById(R.id.tv_name);
            tvIp = v.findViewById(R.id.tv_ip);
            tvMac = v.findViewById(R.id.tv_mac);
            tvStatus = v.findViewById(R.id.tv_status);
            tvLastSeen = v.findViewById(R.id.tv_last_seen);
            btnPing = v.findViewById(R.id.btn_ping);
            btnBan = v.findViewById(R.id.btn_ban);
            btnDetails = v.findViewById(R.id.btn_details);
        }
    }
}