package com.rkant.netcut;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

public class LogsActivity extends AppCompatActivity {

    private RecyclerView rvLogs;
    private LogAdapter adapter;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_logs);

        rvLogs = findViewById(R.id.rv_logs);
        Button btnClear = findViewById(R.id.btn_clear_logs);
        Button btnClose = findViewById(R.id.btn_close_logs);

        rvLogs.setLayoutManager(new LinearLayoutManager(this));
        adapter = new LogAdapter();
        rvLogs.setAdapter(adapter);

        btnClear.setOnClickListener(v -> {
            LogStore.clear();
            refresh();
        });

        btnClose.setOnClickListener(v -> finish());

        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(refreshRunnable);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(refreshRunnable);
    }

    private void refresh() {
        adapter.updateLogs(LogStore.getLogs());
        if (adapter.getItemCount() > 0) {
            rvLogs.scrollToPosition(adapter.getItemCount() - 1);
        }
    }
}