package com.asoft.ios;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Floating-bar live iOS/QEMU terminal log viewer with text & date filtering and live follow toggle.
 * Helps diagnose black-screen boots and crashes directly from ScreenActivity without leaving iOS.
 */
final class LiveTerminalDialog {
    private static final int MAX_TAIL_BYTES = 96 * 1024;
    private static final long POLL_MS = 1000L;

    private static final int SRC_SERIAL = 0;
    private static final int SRC_STDERR = 1;
    private static final int SRC_APP = 2;

    private LiveTerminalDialog() {}

    static void show(Activity a) {
        if (a == null || a.isFinishing() || a.isDestroyed()) {
            return;
        }
        float d = a.getResources().getDisplayMetrics().density;
        int dp8 = Math.round(8 * d);
        int dp12 = Math.round(12 * d);

        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp12, dp8, dp12, dp8);

        // Top controls: Source selector + Live Follow checkbox
        LinearLayout topRow = new LinearLayout(a);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);
        Spinner spSource = new Spinner(a);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(a, android.R.layout.simple_spinner_item,
                new String[]{"iOS Serial (serial.txt)", "QEMU stderr", "App log (app.txt)"});
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spSource.setAdapter(adapter);
        topRow.addView(spSource, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        CheckBox cbLive = new CheckBox(a);
        cbLive.setText("Live follow");
        cbLive.setChecked(true);
        topRow.addView(cbLive, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(topRow);

        // Filter row: keyword search + date/time filter + Today shortcut
        LinearLayout filterRow = new LinearLayout(a);
        filterRow.setOrientation(LinearLayout.HORIZONTAL);
        filterRow.setGravity(Gravity.CENTER_VERTICAL);

        EditText etSearch = new EditText(a);
        etSearch.setHint("Search text (e.g. panic, AMFI)");
        etSearch.setSingleLine(true);
        etSearch.setTextSize(13);
        filterRow.addView(etSearch, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f));

        EditText etDate = new EditText(a);
        etDate.setHint("Date (MM-dd / HH:)");
        etDate.setSingleLine(true);
        etDate.setTextSize(13);
        filterRow.addView(etDate, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.9f));

        Button btnToday = new Button(a);
        btnToday.setText("Today");
        btnToday.setAllCaps(false);
        btnToday.setTextSize(12);
        btnToday.setOnClickListener(v -> {
            String today = new SimpleDateFormat("MM-dd", Locale.US).format(new Date());
            etDate.setText(today);
            etDate.setSelection(today.length());
        });
        filterRow.addView(btnToday, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(filterRow);

        // Status line (file name, size, matched lines)
        TextView tvMeta = new TextView(a);
        tvMeta.setTextSize(11);
        tvMeta.setPadding(0, dp8 / 2, 0, dp8 / 2);
        root.addView(tvMeta);

        // Monospace dark terminal output pane
        ScrollView scroll = new ScrollView(a);
        scroll.setBackgroundColor(0xff111418);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.round(340 * d));
        TextView tvLog = new TextView(a);
        tvLog.setTypeface(Typeface.MONOSPACE);
        tvLog.setTextSize(11);
        tvLog.setTextColor(0xffe6edf3);
        tvLog.setPadding(dp8, dp8, dp8, dp8);
        tvLog.setTextIsSelectable(true);
        scroll.addView(tvLog);
        root.addView(scroll, slp);

        final Handler handler = new Handler(Looper.getMainLooper());
        final boolean[] closed = {false};
        final int[] source = {SRC_SERIAL};
        final long[] lastLen = {-1L};
        final long[] lastMod = {-1L};
        final String[] lastRaw = {""};

        Runnable renderFiltered = () -> {
            String query = etSearch.getText().toString().trim().toLowerCase(Locale.ROOT);
            String dateFilter = etDate.getText().toString().trim().toLowerCase(Locale.ROOT);
            String raw = lastRaw[0];
            String shown = filterLines(raw, query, dateFilter);
            int totalLines = countLines(raw);
            int shownLines = countLines(shown);
            File f = resolveFile(a, source[0]);
            String fname = f != null ? f.getName() : "none";
            long sz = (f != null && f.isFile()) ? f.length() : 0L;
            tvMeta.setText(String.format(Locale.ROOT,
                    "%s (%.1f KB) · showing %d / %d lines%s",
                    fname, sz / 1024.0, shownLines, totalLines,
                    cbLive.isChecked() ? " · LIVE" : " · PAUSED"));
            tvLog.setText(shown.isEmpty() ? "(no matching lines yet)" : shown);
            if (cbLive.isChecked()) {
                scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
            }
        };

        Runnable loadOnce = () -> new Thread(() -> {
            File f = resolveFile(a, source[0]);
            long len = (f != null && f.isFile()) ? f.length() : -1L;
            long mod = (f != null && f.isFile()) ? f.lastModified() : -1L;
            if (len == lastLen[0] && mod == lastMod[0] && !lastRaw[0].isEmpty()) {
                return;
            }
            String text = readTail(f, MAX_TAIL_BYTES);
            lastLen[0] = len;
            lastMod[0] = mod;
            lastRaw[0] = text;
            a.runOnUiThread(() -> {
                if (!closed[0] && !a.isFinishing() && !a.isDestroyed()) {
                    renderFiltered.run();
                }
            });
        }, "live-terminal-read").start();

        final Runnable[] pollTask = new Runnable[1];
        pollTask[0] = () -> {
            if (closed[0] || a.isFinishing() || a.isDestroyed()) {
                return;
            }
            if (cbLive.isChecked()) {
                loadOnce.run();
            }
            handler.postDelayed(pollTask[0], POLL_MS);
        };

        spSource.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                source[0] = pos;
                lastLen[0] = -1L;
                lastMod[0] = -1L;
                lastRaw[0] = "";
                loadOnce.run();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                renderFiltered.run();
            }
        };
        etSearch.addTextChangedListener(watcher);
        etDate.addTextChangedListener(watcher);
        cbLive.setOnCheckedChangeListener((btn, isChecked) -> {
            renderFiltered.run();
            if (isChecked) {
                loadOnce.run();
            }
        });

        AlertDialog dialog = new AlertDialog.Builder(a)
                .setTitle("iOS Live Terminal")
                .setView(root)
                .setNeutralButton("Refresh", null)
                .setNegativeButton("Close", null)
                .create();
        dialog.setOnDismissListener(d2 -> {
            closed[0] = true;
            handler.removeCallbacks(pollTask[0]);
        });
        dialog.show();
        Button refreshBtn = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
        if (refreshBtn != null) {
            refreshBtn.setOnClickListener(v -> {
                lastLen[0] = -1L;
                loadOnce.run();
            });
        }
        loadOnce.run();
        handler.postDelayed(pollTask[0], POLL_MS);
    }

    private static File resolveFile(Activity a, int src) {
        File run = Guest.runDir(a);
        File logs = new File(a.getFilesDir(), "logs");
        if (src == SRC_SERIAL) {
            File f = new File(run, "serial.txt");
            return f.isFile() ? f : new File(logs, "last-serial.txt");
        }
        if (src == SRC_STDERR) {
            File f = new File(run, "qemu-stderr.txt");
            return f.isFile() ? f : new File(logs, "last-qemu-stderr.txt");
        }
        return new File(logs, "app.txt");
    }

    static String filterLines(String raw, String queryLower, String dateLower) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        if ((queryLower == null || queryLower.isEmpty()) && (dateLower == null || dateLower.isEmpty())) {
            return raw;
        }
        StringBuilder out = new StringBuilder();
        String[] lines = raw.split("\n");
        for (String line : lines) {
            String low = line.toLowerCase(Locale.ROOT);
            if (queryLower != null && !queryLower.isEmpty() && !low.contains(queryLower)) {
                continue;
            }
            if (dateLower != null && !dateLower.isEmpty() && !low.contains(dateLower)) {
                continue;
            }
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(line);
        }
        return out.toString();
    }

    private static int countLines(String s) {
        if (s == null || s.isEmpty()) {
            return 0;
        }
        int n = 1;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') {
                n++;
            }
        }
        return n;
    }

    private static String readTail(File f, int maxBytes) {
        if (f == null || !f.isFile()) {
            return "";
        }
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            long len = raf.length();
            if (len <= 0) {
                return "";
            }
            long start = Math.max(0L, len - maxBytes);
            raf.seek(start);
            byte[] buf = new byte[(int) (len - start)];
            raf.readFully(buf);
            String s = new String(buf, StandardCharsets.ISO_8859_1);
            if (start > 0) {
                int nl = s.indexOf('\n');
                if (nl >= 0 && nl + 1 < s.length()) {
                    s = s.substring(nl + 1);
                }
            }
            return s.replace("\r", "");
        } catch (Exception e) {
            return "(error reading " + f.getName() + ": " + e.getMessage() + ")";
        }
    }
}
