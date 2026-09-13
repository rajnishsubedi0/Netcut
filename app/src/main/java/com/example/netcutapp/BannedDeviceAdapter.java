package com.example.netcutapp;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.util.List;

public class BannedDeviceAdapter extends RecyclerView.Adapter<BannedDeviceAdapter.ViewHolder> {
    private List<Device> devices;
    private OnUnbanListener listener;

    public interface OnUnbanListener {
        void onUnbanClick(Device device);
    }

    public BannedDeviceAdapter(List<Device> devices, OnUnbanListener listener) {
        this.devices = devices;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_banned_device, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Device d = devices.get(position);
        holder.tvName.setText(d.getName());
        holder.tvIp.setText(d.getIp());
        holder.tvMac.setText(d.getMac());
        holder.btnUnban.setOnClickListener(v -> listener.onUnbanClick(d));
    }

    @Override
    public int getItemCount() { return devices.size(); }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvName, tvIp, tvMac;
        Button btnUnban;
        ViewHolder(View v) {
            super(v);
            tvName = v.findViewById(R.id.tv_name);
            tvIp = v.findViewById(R.id.tv_ip);
            tvMac = v.findViewById(R.id.tv_mac);
            btnUnban = v.findViewById(R.id.btn_unban);
        }
    }
}