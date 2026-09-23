package com.rkant.netcut;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.List;

public class LogsActivity extends AppCompatActivity {

    private RecyclerView rvLogs;
    private LogsAdapter adapter;
    private List<String> logsList = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_logs);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.logs_main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        rvLogs = findViewById(R.id.rv_logs);
        MaterialButton btnClear = findViewById(R.id.btn_clear_logs);

        rvLogs.setLayoutManager(new LinearLayoutManager(this));

        adapter = new LogsAdapter(logsList);
        rvLogs.setAdapter(adapter);

        loadLogs();

        btnClear.setOnClickListener(v -> clearLogs());
    }

    private void loadLogs() {
        logsList.clear();
        logsList.addAll(SessionLogManager.getInstance().getLogs());
        adapter.notifyDataSetChanged();
    }

    private void clearLogs() {
        SessionLogManager.getInstance().clear();
        loadLogs();
        Toast.makeText(this, "Logs cleared", Toast.LENGTH_SHORT).show();
    }

    private static class LogsAdapter extends RecyclerView.Adapter<LogsAdapter.ViewHolder> {

        private final List<String> logs;

        public LogsAdapter(List<String> logs) {
            this.logs = logs;
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
            holder.tvLog.setText(logs.get(position));
        }

        @Override
        public int getItemCount() {
            return logs.size();
        }

        static class ViewHolder extends RecyclerView.ViewHolder {
            TextView tvLog;

            ViewHolder(View v) {
                super(v);
                tvLog = v.findViewById(R.id.tv_log_text);
            }
        }
    }
}