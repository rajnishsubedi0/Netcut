package com.rkant.netcut;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

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
        allDevices.addAll(newDevices);

        Set<String> validMacs = new HashSet<>();
        for (Device d : allDevices) {
            validMacs.add(d.getMac());
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

            String name = d.getName().toLowerCase();
            String ip = d.getIp().toLowerCase();
            String mac = d.getMac().toLowerCase();

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
        if (listener != null) listener.onSelectionChanged();
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

        holder.tvIp.setText("IP: " + d.getIp());
        holder.tvMac.setText("MAC: " + d.getMac());
        holder.tvStatus.setText(d.isOnline() ? "Online" : "Offline");
        holder.tvStatus.setTextColor(d.isOnline() ? 0xFF00C853 : 0xFFFF5252);
        holder.tvLastSeen.setText("Last seen: " + Device.formatLastSeen(d.getLastSeen()));

        String displayName = d.getName();
        if (d.isProtected()) displayName += " 🛡";
        if (d.isBanned()) displayName += " 🚫";
        holder.tvName.setText(displayName);

        if (d.isBanned()) {
            holder.tvName.setTextColor(0xFFD32F2F);
        } else if (d.isProtected()) {
            holder.tvName.setTextColor(0xFF1565C0);
        } else {
            holder.tvName.setTextColor(0xFF212121);
        }

        boolean selected = selectedMacs.contains(d.getMac());

        holder.cbSelect.setOnCheckedChangeListener(null);
        holder.cbSelect.setChecked(selected);
        holder.cbSelect.setOnCheckedChangeListener((buttonView, isChecked) -> {
            toggleSelection(d.getMac());
            if (listener != null) listener.onSelectionChanged();
        });

        if (selected) {
            holder.itemView.setBackgroundColor(0xFFE3F2FD);
        } else if (d.isBanned()) {
            holder.itemView.setBackgroundColor(0xFFFFEBEE);
        } else {
            holder.itemView.setBackgroundColor(0xFFFFFFFF);
        }

        holder.itemView.setOnLongClickListener(v -> {
            toggleSelection(d.getMac());
            if (listener != null) listener.onSelectionChanged();
            return true;
        });

        holder.btnBan.setText(d.isProtected() ? "Protected" : (d.isBanned() ? "Unban" : "Ban"));
        holder.btnBan.setEnabled(!d.isProtected());

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
        CheckBox cbSelect;
        TextView tvName, tvIp, tvMac, tvStatus, tvLastSeen;
        Button btnPing, btnBan, btnDetails;

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