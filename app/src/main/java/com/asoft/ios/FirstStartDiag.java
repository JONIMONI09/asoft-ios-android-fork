package com.asoft.ios;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What the iOS 16 first start's console says, kept for the failure record (SetupRecord) and the app log. On some
 * phones the step sits at 95 % for hours and the record held only the console's last lines, launchd's respawn
 * noise: it could not say whether iOS never finished starting SpringBoard or reached its lock screen and the app
 * missed the lines. Nothing here changes what the setup does; it only records. No Android in here.
 *
 * Marks reads the console line by line as PhoneSetup.firstStart does (the display going on and off, SpringBoard's
 * cover sheet, crashes); Scan reads the whole serial.txt once, when the record is written. A console line is
 * sometimes glued to the one before it (no newline), so every match is a contains().
 */
final class FirstStartDiag {
    private FirstStartDiag() {}

    static final String DISPLAY_ON = "SWEET FB: display on", DISPLAY_OFF = "SWEET FB: display off";
    private static final String COVER = "SBCoverSheetPrimarySlidingViewController", AKS = "SWEET AKS: SpringBoard",
            FAIRPLAY = "exec fairplayd", CRASH = "CRASH ", PANIC = "panic(";

    /** A console file is read up to this many bytes; the head, the window and the map come from what was read. */
    static final long MAX_READ = 64L << 20;
    static final int HEAD = 120, BEFORE = 30, AFTER = 100, TOP_KINDS = 15, KIND_LEN = 100;
    /** The record's lines are cut here (a console line is rarely longer than 200). */
    private static final int KEEP_LINE = 300;
    private static final int KINDS_MAX = 20_000;

    // -- the display events and counters, as the lines come ------------------------------------------

    static final class Event {
        final boolean on, ignored;
        /** Minutes since the first start began, -1 when not known (a record made from the file). */
        final long min;
        /** 1-based console line number. */
        final long line;

        Event(boolean on, boolean ignored, long min, long line) {
            this.on = on;
            this.ignored = ignored;
            this.min = min;
            this.line = line;
        }

        String text() {
            return "display " + (on ? "on" : "off" + (ignored ? " ignored" : "")) + (min >= 0 ? " at min " + min : "")
                    + " (line " + line + ")";
        }
    }

    static final class Marks {
        private static final int KEPT_EVENTS = 12, NOTED_EVENTS = 40, KEPT_CRASHED = 8;

        private final long startMs;
        private final List<Event> events = new ArrayList<>();
        private final LinkedHashSet<String> crashed = new LinkedHashSet<>();
        private long lines, cover, aks, fairplay, crashLines, panics;
        private int total;
        private boolean offSeen, onBeforeOff, onAfterOff;

        /** @startMs: when the first start began; negative when the minutes are not known. */
        Marks(long startMs) {
            this.startMs = startMs;
        }

        /**
         * One console line (without its newline) seen at @nowMs.
         * @return a display event as a line for the app log, null when the line holds none (or the log has
         * had enough of them).
         */
        String feed(String line, long nowMs) {
            lines++;
            if (line.contains(COVER)) {
                cover++;
            }
            if (line.contains(AKS)) {
                aks++;
            }
            if (line.contains(FAIRPLAY)) {
                fairplay++;
            }
            if (line.contains(CRASH)) {
                crashLines++;
                String n = crashName(line);
                if (n != null && crashed.size() < KEPT_CRASHED) {
                    crashed.add(n);
                }
            }
            if (line.contains(PANIC)) {
                panics++;
            }
            boolean off = line.contains(DISPLAY_OFF);
            if (!off && !line.contains(DISPLAY_ON)) {
                return null;
            }
            // (the same order as PhoneSetup's own check: an "off" decides, then an "on")
            Event e = new Event(!off, off && line.contains("ignored"), startMs < 0 ? -1 : Math.max(0, (nowMs - startMs) / SetupLimits.MIN), lines);
            if (off) {
                offSeen = true;
            } else if (offSeen) {
                onAfterOff = true;
            } else {
                onBeforeOff = true;
            }
            total++;
            if (events.size() < KEPT_EVENTS) {
                events.add(e);
            }
            return total <= NOTED_EVENTS ? e.text() : null;
        }

        /** "CRASH assetsd[236] sig 6 ..." gives "assetsd"; null for the lines after it (pc, bt, reason). */
        private static String crashName(String line) {
            int at = line.indexOf(CRASH) + CRASH.length();
            int br = line.indexOf('[', at);
            if (br <= at || br - at > 40 || line.indexOf(" sig ", br) < 0) {
                return null;
            }
            String n = line.substring(at, br);
            return n.indexOf(' ') >= 0 ? null : n;
        }

        long lines() {
            return lines;
        }

        /** One sentence, without a time (a record made from the file). */
        String events() {
            return events(-1);
        }

        /**
         * "display on at min 2 (line 1281); display off ignored at min 3 (line 1397); no second display on after 310
         * min": what the display did, and what it never did. @nowMs: now (negative: no "after N min").
         */
        String events(long nowMs) {
            String after = nowMs >= 0 && startMs >= 0 ? " after " + Math.max(0, (nowMs - startMs) / SetupLimits.MIN) + " min" : "";
            if (total == 0) {
                return "no display on or off in the console" + after;
            }
            StringBuilder b = new StringBuilder();
            for (Event e : events) {
                b.append(b.length() > 0 ? "; " : "").append(e.text());
            }
            if (total > events.size()) {
                b.append("; ").append(total - events.size()).append(" more");
            }
            if (!offSeen) {
                b.append("; no display off").append(after);
            } else if (!onAfterOff) {
                b.append(onBeforeOff ? "; no second display on" : "; no display on").append(after);
            }
            return b.toString();
        }

        /** The counters: lines, SpringBoard's cover sheet, its AKS calls, fairplayd's respawns, crashes, panics. */
        String summary() {
            return lines + " lines; SBCoverSheetPrimarySlidingViewController lines " + cover + "; SWEET AKS: SpringBoard lines " + aks
                    + "; exec fairplayd lines " + fairplay + "; CRASH lines " + crashLines
                    + (crashed.isEmpty() ? "" : " (processes: " + String.join(", ", crashed) + ")") + "; panic( lines " + panics;
        }
    }

    // -- the whole file, once --------------------------------------------------------------------------

    /** What one read of serial.txt found: for the failure record. */
    static final class Scan {
        final Marks marks = new Marks(-1);
        /** The first HEAD lines. */
        final List<String> head = new ArrayList<>();
        /** BEFORE lines, the first "display off" line, AFTER lines; empty when there is no such line. */
        final List<String> around = new ArrayList<>();
        long lines;
        boolean truncated;
        String error = "";
        private final LinkedHashMap<String, int[]> kinds = new LinkedHashMap<>();
        private boolean kindsFull;
        private final ArrayDeque<String> ring = new ArrayDeque<>();
        private long offLine;
        private int before, afterLeft;

        private void line(String raw) {
            lines++;
            marks.feed(raw, 0);
            String k = ConsoleLife.key(raw);
            if (!k.isEmpty()) {
                int[] n = kinds.get(k);
                if (n != null) {
                    n[0]++;
                } else if (kinds.size() < KINDS_MAX) {
                    kinds.put(k, new int[] {1});
                } else {
                    kindsFull = true;
                }
            }
            String keep = null;
            if (lines <= HEAD || offLine == 0 || afterLeft > 0) {
                keep = keep(raw);
            }
            if (lines <= HEAD) {
                head.add(keep);
            }
            if (offLine == 0) {
                if (raw.contains(DISPLAY_OFF)) {
                    offLine = lines;
                    before = ring.size();
                    around.addAll(ring);
                    around.add(keep);
                    afterLeft = AFTER;
                } else {
                    ring.addLast(keep);
                    if (ring.size() > BEFORE) {
                        ring.removeFirst();
                    }
                }
            } else if (afterLeft > 0) {
                around.add(keep);
                afterLeft--;
            }
        }

        int distinct() {
            return kinds.size();
        }

        /** "412 distinct kinds in 1500 lines", then the TOP_KINDS commonest as "count x kind". */
        List<String> map() {
            List<Map.Entry<String, int[]>> all = new ArrayList<>(kinds.entrySet());
            Collections.sort(all, (a, b) -> Integer.compare(b.getValue()[0], a.getValue()[0]));      // (stable: ties keep their first-seen order)
            List<String> out = new ArrayList<>();
            out.add(kinds.size() + " distinct line kinds (digits collapsed) in " + lines + " lines"
                    + (kindsFull ? "; new kinds were no longer counted after " + KINDS_MAX : "")
                    + (truncated ? "; only the first " + (MAX_READ >> 20) + " MB were read" : "") + "; the " + TOP_KINDS + " commonest:");
            for (int i = 0; i < Math.min(TOP_KINDS, all.size()); i++) {
                String k = all.get(i).getKey();
                out.add(all.get(i).getValue()[0] + " x " + (k.length() > KIND_LEN ? k.substring(0, KIND_LEN) : k));
            }
            return out;
        }

        /** The window around the first display off, with a first line that says which lines it holds. */
        List<String> window() {
            if (around.isEmpty()) {
                return around;
            }
            List<String> out = new ArrayList<>(around.size() + 1);
            out.add("(lines " + (offLine - before) + " to " + (offLine + around.size() - 1 - before) + "; the first display off is line "
                    + offLine + ")");
            out.addAll(around);
            return out;
        }

        /** Reads @in as console text: lines end at "\n" only, as PhoneSetup's own reader splits them. */
        Scan read(Reader in, long maxChars) {
            StringBuilder b = new StringBuilder();
            char[] buf = new char[1 << 16];
            long seen = 0;
            try {
                int n;
                while (seen < maxChars && (n = in.read(buf, 0, (int) Math.min(buf.length, maxChars - seen))) > 0) {
                    seen += n;
                    for (int i = 0; i < n; i++) {
                        char c = buf[i];
                        if (c == '\n') {
                            line(b.length() > 0 && b.charAt(b.length() - 1) == '\r' ? b.substring(0, b.length() - 1) : b.toString());
                            b.setLength(0);
                        } else if (b.length() < 16_384) {     // a line that never ends is no line
                            b.append(c);
                        }
                    }
                }
                truncated = seen >= maxChars && in.read() >= 0;
            } catch (IOException e) {
                error = e.toString();
            }
            if (b.length() > 0) {
                line(b.toString());
            }
            return this;
        }
    }

    /** serial.txt read through once (up to MAX_READ); never throws, a failure is in Scan.error. */
    static Scan scan(File serial) {
        Scan s = new Scan();
        try (Reader in = new InputStreamReader(new FileInputStream(serial), StandardCharsets.ISO_8859_1)) {
            s.read(in, MAX_READ);
        } catch (IOException e) {
            s.error = e.toString();
        }
        return s;
    }

    /** A console line as the record keeps it: cut, control characters out. */
    private static String keep(String l) {
        String s = l.length() > KEEP_LINE ? l.substring(0, KEEP_LINE) + "…" : l;
        StringBuilder b = null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 32 && c != '\t') {
                if (b == null) {
                    b = new StringBuilder(s);
                }
                b.setCharAt(i, '.');
            }
        }
        return b == null ? s : b.toString();
    }

    // -- what the give-up saw (PhoneSetup.firstStart hands it to SetupRecord) ---------------------------

    static final class GiveUp {
        final long atMs;
        /** Marks.events() with the minutes. */
        final String events;
        /** ledText of the display LED at that moment. */
        final String led;
        /** The section "the screen when the first start gave up". */
        final List<String> screen;

        GiveUp(long atMs, String events, String led, List<String> screen) {
            this.atMs = atMs;
            this.events = events;
            this.led = led;
            this.screen = screen;
        }
    }

    /** The newest give-up of the first start in this process, null before one (or when a new first start began). */
    static volatile GiveUp lastGiveUp;

    // -- the display LED -------------------------------------------------------------------------------

    /** The guest's display power from QEMU's LED byte (Guest.ledsFile): -1 when the file cannot be read. */
    static int readLed(File f) {
        try (RandomAccessFile in = new RandomAccessFile(f, "r")) {
            return in.read();
        } catch (IOException e) {
            return -1;
        }
    }

    /** "off" while XNU lights Scroll Lock (iOS has its display off), "on" otherwise, "not readable" for -1. */
    static String ledText(int ledByteOrMinus1) {
        if (ledByteOrMinus1 < 0) {
            return "not readable";
        }
        return (ledByteOrMinus1 & Guest.SCROLL_LOCK_LED) != 0 ? "off" : "on";
    }

    // -- the lines of the app log ------------------------------------------------------------------------

    /** The every-10-minutes line: how busy the console is and when it last showed something new. */
    static String progressLine(long min, long lines, long newLines, int kinds, long lastNewKindMinAgo, String led) {
        return String.format(Locale.ROOT, "first start %d min: console %d lines (+%d in the last 10 min), new line kinds %d, "
                + "last new kind %s, LED %s", min, lines, newLines, kinds,
                lastNewKindMinAgo < 1 ? "just now" : lastNewKindMinAgo + " min ago", led);
    }

    /** The locale words Installer.prepareBoot puts into the boot-args, and whether each was accepted. */
    static String localeLine(String tz, String region, boolean clock24) {
        String w = " " + GuestImage.withLocale("", tz, region, clock24) + " ";
        return "boot-args locale: tz=" + shown(tz, w.contains(" asoft_tz=")) + " region=" + shown(region, w.contains(" asoft_region="))
                + " clock=" + (clock24 ? "24" : "12");
    }

    private static String shown(String v, boolean written) {
        if (written) {
            return v;
        }
        return v == null || v.isEmpty() ? "(empty, not written)" : v + " (not accepted, not written)";
    }

    // -- the record ----------------------------------------------------------------------------------------

    // (no "console" in these names: CrashReport gives such a section twice the room in the shortened GitHub link,
    // and the console's own tail, which keeps that weight, must stay the biggest part of the link)
    static final String S_MAP = "first start, the commonest line kinds of serial.txt (top " + TOP_KINDS + ")",
            S_HEAD = "first start, the first " + HEAD + " lines of serial.txt",
            S_AROUND = "first start, around the first display off in serial.txt (" + BEFORE + " before, " + AFTER + " after)",
            S_SCREEN = "the screen when the first start gave up";

    /**
     * What the first start's record adds to its header (@h) and sections (@s, before the console's tail): the
     * display events and counters, the LED and the screen at the give-up (@g, null when the step stopped another
     * way), the console's map, its first lines, and the lines around the first display off.
     */
    static void addRecordParts(LinkedHashMap<String, String> h, LinkedHashMap<String, List<String>> s, Scan scan, GiveUp g) {
        h.put("first start console", (g != null ? g.events : scan.marks.events()) + " | " + scan.marks.summary()
                + (scan.truncated ? " (the first " + (MAX_READ >> 20) + " MB)" : "") + (scan.error.isEmpty() ? "" : " (read error: " + scan.error + ")"));
        if (g != null) {
            h.put("display LED at give-up", g.led);
            s.put(S_SCREEN, g.screen);
        }
        s.put(S_MAP, scan.map());
        s.put(S_HEAD, scan.head.isEmpty() ? Collections.singletonList("(the console file is empty or missing)") : scan.head);
        s.put(S_AROUND, scan.around.isEmpty() ? Collections.singletonList("(the console has no display off line)") : scan.window());
    }
}
