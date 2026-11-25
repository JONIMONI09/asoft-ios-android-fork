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
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Floating-bar live iOS/QEMU/App terminal log viewer with text & date filtering and live follow toggle.
 * Exposes every log file available in the app, explains when a log is disabled by settings
 * (e.g. qemu.log or kgsl-trace.txt), performs all file I/O and filtering off the UI thread to prevent ANRs,
 * and does zero work when closed or paused.
 */
final class LiveTerminalDialog {
    private static final int MAX_TAIL_BYTES = 48 * 1024;
    private static final long POLL_MS = 1200L;

    private static final class Source {
        final String label;
        final File primary;
        final File fallback;
        final String disabledNotice;

        Source(String label, File primary, File fallback, String disabledNotice) {
            this.label = label;
            this.primary = primary;
            this.fallback = fallback;
            this.disabledNotice = disabledNotice;
        }

        File resolve() {
            if (primary != null && primary.isFile() && primary.length() > 0) {
                return primary;
            }
            if (fallback != null && fallback.isFile() && fallback.length() > 0) {
                return fallback;
            }
            return primary != null ? primary : fallback;
        }
    }

    private LiveTerminalDialog() {}

    private static List<Source> buildSources(Activity a) {
        File run = Guest.runDir(a);
        File logDir = AppLog.dir(a);
        PerfConfig perf = PerfConfig.load(a);
        CompatConfig.Config compat = CompatConfig.load(a);

        List<Source> list = new ArrayList<>();
        list.add(new Source("iOS Serial (serial.txt)",
                new File(run, "serial.txt"), new File(logDir, "last-serial.txt"), null));
        if (perf.disableQemuLog) {
            return list;
        }
        list.add(new Source("App log (app.txt)",
                new File(run, "app.txt"), new File(logDir, "app.txt"), null));
        list.add(new Source("QEMU stderr (qemu-stderr.txt)",
                new File(run, "qemu-stderr.txt"), new File(logDir, "last-qemu-stderr.txt"), null));
        list.add(new Source("QEMU guest errors (qemu.log)",
                new File(run, "qemu.log"), null, null));
        list.add(new Source("QEMU command line (cmdline.txt)",
                new File(run, "cmdline.txt"), null, null));
        list.add(new Source("KGSL & Vulkan shim trace (kgsl-trace.txt)",
                new File(run, CompatConfig.TRACE_FILE), new File(logDir, "last-" + CompatConfig.TRACE_FILE),
                !compat.kgslTrace
                        ? "[Disabled by setting]\nKGSL crash trace is turned OFF in Settings > iOS 26 GPU compatibility (#92) for maximum performance.\nEnable it there only when diagnosing GPU issues."
                        : null));
        list.add(new Source("Boot & stall diagnostics (boot-diag.txt)",
                new File(run, BootDiagnostics.FILE_NAME), new File(logDir, "last-" + BootDiagnostics.FILE_NAME), null));
        list.add(new Source("UI Freeze / ANR log (last-ui-freeze.txt)",
                new File(run, FreezeWatchdog.FILE_NAME), null, null));
        list.add(new Source("App crash log (last-app-crash.txt)",
                new File(run, CrashHandler.CRASH_FILE), null, null));
        list.add(new Source("Engine test log (el0bench.log)",
                new File(a.getExternalFilesDir(null), EngineService.LOG), null, null));
        list.add(new Source("Previous run: iOS Serial",
                new File(logDir, "last-serial.txt"), null, null));
        list.add(new Source("Previous run: QEMU stderr",
                new File(logDir, "last-qemu-stderr.txt"), null, null));

        // Include any kept setup logs from RunLogs
        File[] kept = new File(PhoneSetup26.work(a), "phone/logs").listFiles();
        if (kept != null) {
            for (File f : kept) {
                if (f.isFile() && f.length() > 0 && f.getName().endsWith(".txt")) {
                    list.add(new Source("Setup: " + f.getName(), f, null, null));
                }
            }
        }
        return list;
    }

    static void show(Activity a) {
        if (a == null || a.isFinishing() || a.isDestroyed()) {
            return;
        }
        final List<Source> sources = buildSources(a);
        String[] labels = new String[sources.size()];
        for (int i = 0; i < sources.size(); i++) {
            labels[i] = sources.get(i).label;
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
        ArrayAdapter<String> adapter = new ArrayAdapter<>(a, android.R.layout.simple_spinner_item, labels);
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
        etSearch.setHint("Search (e.g. input:, panic)");
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

        TextView tvMeta = new TextView(a);
        tvMeta.setTextSize(11);
        tvMeta.setPadding(0, dp8 / 2, 0, dp8 / 2);
        root.addView(tvMeta);

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
        final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "live-terminal-io");
            t.setDaemon(true);
            return t;
        });
        final boolean[] closed = {false};
        final int[] selIndex = {0};
        final long[] lastLen = {-1L};
        final long[] lastMod = {-1L};
        final String[] lastRaw = {""};
        final int[] gen = {0};

        Runnable refreshAsync = () -> {
            if (closed[0]) {
                return;
            }
            final int myGen = ++gen[0];
            final Source src = sources.get(selIndex[0]);
            final String q = etSearch.getText().toString().trim().toLowerCase(Locale.ROOT);
            final String dt = etDate.getText().toString().trim().toLowerCase(Locale.ROOT);
            final boolean live = cbLive.isChecked();
            worker.execute(() -> {
                if (closed[0] || myGen != gen[0]) {
                    return;
                }
                if (src.disabledNotice != null) {
                    String meta = src.label + " · DISABLED";
                    a.runOnUiThread(() -> {
                        if (closed[0] || a.isFinishing() || a.isDestroyed() || myGen != gen[0]) {
                            return;
                        }
                        tvMeta.setText(meta);
                        tvLog.setText(src.disabledNotice);
                    });
                    return;
                }
                File f = src.resolve();
                long len = (f != null && f.isFile()) ? f.length() : 0L;
                long mod = (f != null && f.isFile()) ? f.lastModified() : 0L;
                if (len != lastLen[0] || mod != lastMod[0]) {
                    lastLen[0] = len;
                    lastMod[0] = mod;
                    lastRaw[0] = len > 0 ? readTail(f, MAX_TAIL_BYTES) : "";
                }
                String raw = lastRaw[0];
                int totalLines = countLines(raw);
                String filtered = filterLines(raw, q, dt);
                int shownLines = countLines(filtered);
                String displayText = filtered.isEmpty()
                        ? (raw.isEmpty() ? "(log file is empty or not created yet)" : "(no lines match the current filter)")
                        : filtered;
                String fname = f != null ? f.getName() : "none";
                String modeNote = sources.size() == 1 ? " · iOS only" : "";
                String meta = String.format(Locale.ROOT,
                        "%s (%.1f KB) · showing %d / %d lines%s%s",
                        fname, len / 1024.0, shownLines, totalLines,
                        live ? " · LIVE" : " · PAUSED", modeNote);
                a.runOnUiThread(() -> {
                    if (closed[0] || a.isFinishing() || a.isDestroyed() || myGen != gen[0]) {
                        return;
                    }
                    tvMeta.setText(meta);
                    tvLog.setText(displayText);
                    if (live) {
                        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
                    }
                });
            });
        };

        final Runnable[] pollTask = new Runnable[1];
        pollTask[0] = () -> {
            if (closed[0] || a.isFinishing() || a.isDestroyed()) {
                return;
            }
            if (cbLive.isChecked()) {
                refreshAsync.run();
                handler.postDelayed(pollTask[0], POLL_MS);
            }
        };

        spSource.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                selIndex[0] = pos;
                lastLen[0] = -1L;
                lastMod[0] = -1L;
                lastRaw[0] = "";
                refreshAsync.run();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                refreshAsync.run();
            }
        };
        etSearch.addTextChangedListener(watcher);
        etDate.addTextChangedListener(watcher);
        cbLive.setOnCheckedChangeListener((btn, isChecked) -> {
            handler.removeCallbacks(pollTask[0]);
            refreshAsync.run();
            if (isChecked) {
                handler.postDelayed(pollTask[0], POLL_MS);
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
            worker.shutdownNow();
        });
        dialog.show();
        Button refreshBtn = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
        if (refreshBtn != null) {
            refreshBtn.setOnClickListener(v -> {
                lastLen[0] = -1L;
                refreshAsync.run();
            });
        }
        refreshAsync.run();
        handler.postDelayed(pollTask[0], POLL_MS);
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
