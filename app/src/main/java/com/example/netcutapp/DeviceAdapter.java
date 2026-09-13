package com.example.netcutapp;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.util.List;

public class DeviceAdapter extends RecyclerView.Adapter<DeviceAdapter.ViewHolder> {
    private List<Device> devices;
    private OnDeviceActionListener listener;

    public interface OnDeviceActionListener {
        void onBanClick(Device device);
        void onPingClick(Device device);
        void onNameClick(Device device);
    }

    public DeviceAdapter(List<Device> devices, OnDeviceActionListener listener) {
        this.devices = devices;
        this.listener = listener;
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
        holder.tvName.setText(d.getName());
        holder.tvIp.setText(d.getIp());
        holder.tvMac.setText(d.getMac());
        holder.tvStatus.setText(d.isOnline() ? "Online" : "Offline");
        holder.tvStatus.setTextColor(d.isOnline() ? 0xFF00FF00 : 0xFFFF0000);
        holder.btnBan.setText(d.isBanned() ? "Unban" : "Ban");

        // ISSUE 4 FIX: Highlight banned devices visually instead of hiding them
        if (d.isBanned()) {
            holder.itemView.setBackgroundColor(0xFFFFDDDD); // Light red background
            holder.tvName.setTextColor(0xFFD32F2F);         // Red text
        } else {
            holder.itemView.setBackgroundColor(0xFFEEEEEE); // Default gray background
            holder.tvName.setTextColor(0xFF000000);         // Black text
        }

        holder.tvName.setOnClickListener(v -> listener.onNameClick(d));
        holder.btnBan.setOnClickListener(v -> listener.onBanClick(d));
        holder.btnPing.setOnClickListener(v -> listener.onPingClick(d));
    }

    @Override
    public int getItemCount() { return devices.size(); }

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