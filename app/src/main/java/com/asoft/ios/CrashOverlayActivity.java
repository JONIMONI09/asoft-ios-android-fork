package com.asoft.ios;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Isolated crash / UI-freeze overlay activity (runs in {@code :crash} process).
 * Displays the full stacktrace with Copy, Restart app, and Close actions.
 */
public final class CrashOverlayActivity extends Activity {
    static final String EXTRA_TITLE = "title";
    static final String EXTRA_REPORT = "report";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String title = getIntent().getStringExtra(EXTRA_TITLE);
        if (title == null || title.isEmpty()) {
            title = "App Crash Caught";
        }
        String report = getIntent().getStringExtra(EXTRA_REPORT);
        if (report == null || report.isEmpty()) {
            report = "(no stacktrace captured)";
        }
        final String fullReport = report;

        float d = getResources().getDisplayMetrics().density;
        int dp12 = Math.round(12 * d);
        int dp16 = Math.round(16 * d);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xff0d1117);
        root.setPadding(dp16, Math.round(36 * d), dp16, dp16);

        TextView tvTitle = new TextView(this);
        tvTitle.setText(title);
        tvTitle.setTextColor(0xffff6b6b);
        tvTitle.setTextSize(20);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(tvTitle);

        TextView tvSub = new TextView(this);
        tvSub.setText("ASoftware iOS intercepted an error so you can inspect or copy the stacktrace. Saved to last-app-crash.txt / Live Terminal.");
        tvSub.setTextColor(0xff9ca3af);
        tvSub.setTextSize(13);
        tvSub.setPadding(0, dp12 / 2, 0, dp12);
        root.addView(tvSub);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xff161b22);
        TextView tvStack = new TextView(this);
        tvStack.setText(fullReport);
        tvStack.setTypeface(Typeface.MONOSPACE);
        tvStack.setTextSize(11);
        tvStack.setTextColor(0xffe6edf3);
        tvStack.setPadding(dp12, dp12, dp12, dp12);
        tvStack.setTextIsSelectable(true);
        scroll.addView(tvStack);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(scroll, slp);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END);
        actions.setPadding(0, dp12, 0, 0);

        Button btnCopy = new Button(this);
        btnCopy.setText("Copy stacktrace");
        btnCopy.setAllCaps(false);
        btnCopy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("ASoftware Crash", fullReport));
                Toast.makeText(this, "Stacktrace copied", Toast.LENGTH_SHORT).show();
            }
        });

        Button btnRestart = new Button(this);
        btnRestart.setText("Restart app");
        btnRestart.setAllCaps(false);
        btnRestart.setTextColor(Color.WHITE);
        btnRestart.setOnClickListener(v -> {
            Intent restart = new Intent(this, MainActivity.class);
            restart.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(restart);
            finish();
        });

        Button btnClose = new Button(this);
        btnClose.setText("Close");
        btnClose.setAllCaps(false);
        btnClose.setOnClickListener(v -> finish());

        actions.addView(btnCopy);
        actions.addView(btnRestart);
        actions.addView(btnClose);
        root.addView(actions);
        setContentView(root);
    }
}
