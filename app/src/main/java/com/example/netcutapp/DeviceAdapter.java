package com.example.netcutapp;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.List;

public class DeviceAdapter extends RecyclerView.Adapter<DeviceAdapter.ViewHolder> {
    private List<Device> devices;
    private OnDeviceActionListener listener;

    public interface OnDeviceActionListener {
        void onBanClick(Device device, int position);
        void onPingClick(Device device);
        void onNameClick(Device device);
    }

    public DeviceAdapter(List<Device> devices, OnDeviceActionListener listener) {
        this.devices = new ArrayList<>(devices); // Create a mutable copy
        this.listener = listener;
    }

    /**
     * ✅ Smoothly update the list without recreating the adapter
     * This prevents jerky UI movements and scroll position resets
     */
    public void updateDevices(List<Device> newDevices) {
        this.devices.clear();
        this.devices.addAll(newDevices);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_device, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Device d = devices.get(position);

        holder.tvIp.setText(d.getIp());
        holder.tvMac.setText(d.getMac());
        holder.tvStatus.setText(d.isOnline() ? "Online" : "Offline");
        holder.tvStatus.setTextColor(d.isOnline() ? 0xFF00C853 : 0xFFFF5252); // Green or Red
        holder.btnBan.setText(d.isBanned() ? "Unban" : "Ban");

        // ✅ VISUAL HIGHLIGHTING FOR BANNED DEVICES
        if (d.isBanned()) {
            holder.itemView.setBackgroundColor(0xFFFFEBEE); // Light red background
            holder.tvName.setTextColor(0xFFD32F2F);         // Dark red text
            holder.tvName.setText(d.getName() + " 🚫");     // Add a banned icon to the name
        } else {
            holder.itemView.setBackgroundColor(0xFFFFFFFF); // Clean white background
            holder.tvName.setTextColor(0xFF212121);         // Standard dark text
            holder.tvName.setText(d.getName());             // Normal name
        }

        holder.tvName.setOnClickListener(v -> listener.onNameClick(d));

        // ✅ PASS THE CURRENT POSITION TO THE LISTENER for instant UI updates
        holder.btnBan.setOnClickListener(v -> listener.onBanClick(d, holder.getBindingAdapterPosition()));
        holder.btnPing.setOnClickListener(v -> listener.onPingClick(d));
    }

    @Override
    public int getItemCount() {
        return devices.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvName, tvIp, tvMac, tvStatus;
        Button btnBan, btnPing;

        ViewHolder(View v) {
            super(v);
            tvName = v.findViewById(R.id.tv_name);
            tvIp = v.findViewById(R.id.tv_ip);
            tvMac = v.findViewById(R.id.tv_mac);
            tvStatus = v.findViewById(R.id.tv_status);
            btnBan = v.findViewById(R.id.btn_ban);
            btnPing = v.findViewById(R.id.btn_ping);
        }
    }
}