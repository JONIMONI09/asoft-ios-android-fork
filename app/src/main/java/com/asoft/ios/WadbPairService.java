package com.asoft.ios;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;

import java.io.IOException;

/**
 * Pairing with this phone's Wireless debugging (WirelessAdb). Settings shows the six digits in a dialog that
 * closes, and its port with it, as soon as the user leaves that screen, so the code cannot be typed in the app:
 * it goes into this service's notification, which has a reply field (as Shizuku does it), or via the in-app
 * Split-Screen pairing dialog.
 */
public class WadbPairService extends Service {
    static final String KEY_CODE = "code";
    private static final String CHANNEL = "wadb", ACTION_CODE = "code", ACTION_CANCEL = "cancel";
    private static final int ID = 4;
    private static final long GIVE_UP_MS = 10 * 60_000;

    /** What the pairing is doing, for the app's own screen; null when no pairing runs. */
    static volatile String state;
    /** Last pairing port announced by AdbMdns while active; -1 when none seen yet. */
    private static volatile int discoveredPort = -1;

    static final class ParsedInput {
        final String code;
        final int port;

        ParsedInput(String code, int port) {
            this.code = code;
            this.port = port;
        }
    }

    private AdbMdns mdns;
    private volatile int port = -1;
    private volatile boolean pairing, done, foregrounded;
    private String lastShownText;
    private boolean lastShownAsk;
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable giveUp = () -> finish("Pairing stopped: no code came in ten minutes. Start it again from the app.");

    static void start(Context c) {
        c.startForegroundService(new Intent(c, WadbPairService.class));
    }

    static void submitFromUi(Context c, String typed) {
        if (typed == null || typed.trim().isEmpty()) {
            return;
        }
        c.startForegroundService(new Intent(c, WadbPairService.class)
                .setAction(ACTION_CODE)
                .putExtra(KEY_CODE, typed.trim()));
    }

    static boolean running() {
        return state != null;
    }

    static int detectedPort() {
        return discoveredPort;
    }

    static boolean shouldNotifyPortChange(int incomingPort, int currentPort) {
        return incomingPort > 0 && incomingPort != currentPort;
    }

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }

    private PendingIntent self(String action, int flags) {
        return PendingIntent.getService(this, action.hashCode(), new Intent(this, WadbPairService.class).setAction(action), flags);
    }

    /** The notification: @text, with the reply field when @ask. */
    private Notification notification(String text, boolean ask, boolean ongoing) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        // high: it has to come up over Settings' pairing dialog, where the user is
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Pairing with Wireless debugging", NotificationManager.IMPORTANCE_HIGH));
        Notification.Builder n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("ASoftware iOS: pairing")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setOngoing(ongoing)
                .setOnlyAlertOnce(!ask)
                .setContentIntent(PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE));
        if (ask) {
            RemoteInput in = new RemoteInput.Builder(KEY_CODE).setLabel("Pairing code").build();
            // mutable: Android writes the typed code into it
            n.addAction(new Notification.Action.Builder(null, "Enter the code",
                    self(ACTION_CODE, PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
                    .addRemoteInput(in).build());
        }
        if (ongoing) {
            n.addAction(new Notification.Action.Builder(null, "Cancel",
                    self(ACTION_CANCEL, PendingIntent.FLAG_IMMUTABLE)).build());
        }
        return n.build();
    }

    private void show(String text, boolean ask) {
        if (foregrounded && text.equals(lastShownText) && ask == lastShownAsk) {
            return;
        }
        state = text;
        lastShownText = text;
        lastShownAsk = ask;
        foregrounded = true;
        startForeground(ID, notification(text, ask, true), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
    }

    private static final String SEARCHING = "Open Settings > Developer options > Wireless debugging and tap "
            + "\"Pair device with pairing code\". Stay on that screen: this notification then asks for its code.";

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_CANCEL.equals(action)) {
            finish(null);
            return START_NOT_STICKY;
        }
        if (ACTION_CODE.equals(action)) {
            Bundle b = RemoteInput.getResultsFromIntent(intent);
            CharSequence typed = b == null ? null : b.getCharSequence(KEY_CODE);
            if (typed == null && intent != null) {
                typed = intent.getStringExtra(KEY_CODE);
            }
            if (state == null) {
                show(SEARCHING, false);     // the service was gone (its notification is from another life): start over
                look();
            } else if (typed != null) {
                pair(typed.toString());
            }
            return START_NOT_STICKY;
        }
        done = false;
        show(port > 0 ? "The pairing dialog is open: type its six digits here (Enter the code)." : SEARCHING, port > 0);
        look();
        main.removeCallbacks(giveUp);
        main.postDelayed(giveUp, GIVE_UP_MS);
        return START_NOT_STICKY;
    }

    /** Watches for the pairing dialog's port; the notification asks for the code while there is one. */
    private void look() {
        if (mdns != null) {
            return;
        }
        mdns = new AdbMdns(this, AdbMdns.PAIRING, p -> main.post(() -> {
            if (done || pairing) {
                return;
            }
            if (shouldNotifyPortChange(p, port)) {
                port = p;
                discoveredPort = p;
                AppLog.note(this, "wireless adb: pairing dialog detected on port " + p);
                show("The pairing dialog is open: type its six digits here (Enter the code).", true);
            } else if (p <= 0 && port > 0) {
                port = -1;
                discoveredPort = -1;
                show(SEARCHING, false);
            }
        }));
        mdns.start();
    }

    /** Parses user reply: "123456", "123 456", "123456 37215", or "123456:37215" (WadbPairInputTest). */
    static ParsedInput parsePairInput(String typed, int knownPort) {
        if (typed == null) {
            return new ParsedInput("", knownPort);
        }
        String trimmed = typed.trim();
        if (trimmed.isEmpty()) {
            return new ParsedInput("", knownPort);
        }
        String[] parts = trimmed.split("[\\s:]+");
        if (parts.length == 2) {
            String d0 = parts[0].replaceAll("\\D", "");
            String d1 = parts[1].replaceAll("\\D", "");
            if (d0.length() == 3 && d1.length() == 3) {
                return new ParsedInput(d0 + d1, knownPort);
            }
            int parsedPort = parsePort(d1);
            if (parsedPort > 0 && d0.length() == 6) {
                return new ParsedInput(d0, parsedPort);
            }
        }
        String code = "";
        int explicitPort = -1;
        for (String part : parts) {
            String digits = part.replaceAll("\\D", "");
            if (code.isEmpty() && digits.length() == 6) {
                code = digits;
            } else if (explicitPort <= 0) {
                explicitPort = parsePort(digits);
            }
        }
        if (code.isEmpty() && parts.length > 0) {
            code = parts[0].replaceAll("\\D", "");
        }
        return new ParsedInput(code, explicitPort > 0 ? explicitPort : knownPort);
    }

    private static int parsePort(String digits) {
        if (digits == null || digits.isEmpty()) {
            return -1;
        }
        try {
            int p = Integer.parseInt(digits);
            return p >= 1 && p <= 65535 ? p : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void pair(String typed) {
        ParsedInput parsed = parsePairInput(typed, port);
        String code = parsed.code;
        int p = parsed.port;
        if (code.length() != 6) {
            show("The code must have 6 digits. Tap Enter the code and try again.", true);
            return;
        }
        if (p <= 0) {
            show("The pairing port was not detected automatically yet. Type the 6-digit code, a space, and the port "
                    + "shown under IP address & Port (for example: 123456 37215).", true);
            return;
        }
        int usePort = p;
        pairing = true;
        show("Pairing on port " + usePort + "…", false);
        new Thread(() -> {
            String said;
            boolean ok = false;
            try {
                WirelessAdb.pair(this, usePort, code);
                ok = true;
                MemoryCleaner.setEnabled(this, true);
                String out;
                try {
                    out = WirelessAdb.shell(this, "id -u", 15_000).trim();
                } catch (IOException e) {
                    out = null;
                    AppLog.note(this, "wireless adb: paired, but the first connection failed: " + e);
                }
                said = "2000".equals(out) ? "Paired and connected. The app now frees memory while iOS runs."
                        : "Paired. The connection did not open yet (" + WirelessAdb.status(this) + "); the app tries again when iOS runs.";
            } catch (AdbPairing.WrongCode e) {
                said = "That code did not match. Settings shows a new one now: open \"Pair device with pairing code\" "
                        + "again and tap Enter the code below.";
            } catch (IOException e) {
                AppLog.note(this, "wireless adb: pairing failed: " + e);
                said = "Pairing did not work (" + e.getMessage() + "). Open the pairing dialog again and tap Enter the code.";
            }
            boolean paired = ok;
            String text = said;
            main.post(() -> {
                pairing = false;
                if (paired) {
                    finish(text);
                } else if (!done) {
                    port = -1;      // that dialog is gone; its successor is announced anew
                    discoveredPort = -1;
                    show(text, false);
                }
            });
        }, "wadb-pair").start();
    }

    /** Ends the service; @last (or nothing) stays as an ordinary notification. */
    private void finish(String last) {
        done = true;
        state = null;
        discoveredPort = -1;
        foregrounded = false;
        lastShownText = null;
        main.removeCallbacks(giveUp);
        if (mdns != null) {
            mdns.stop();
            mdns = null;
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        if (last != null) {
            getSystemService(NotificationManager.class).notify(ID + 1, notification(last, false, false));
        }
        stopSelf();
    }

    @Override
    public void onDestroy() {
        state = null;
        discoveredPort = -1;
        foregrounded = false;
        lastShownText = null;
        main.removeCallbacks(giveUp);
        if (mdns != null) {
            mdns.stop();
            mdns = null;
        }
        super.onDestroy();
    }
}
