package com.peugeot308.oem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Interpreta o LRC da LRCLIB; não depende de bibliotecas extras ou de serviços. */
public final class SyncedLyrics {
    private SyncedLyrics() { }
    public static final class Line {
        public final long startMs;
        public final String text;
        public Line(long startMs, String text) { this.startMs = startMs; this.text = text; }
    }
    private static final Pattern TIME = Pattern.compile("\\[(\\d{1,3}):(\\d{2})(?:\\.(\\d{1,3}))?\\]");
    public static List<Line> parse(String raw) {
        ArrayList<Line> lines = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) return lines;
        String[] rows = raw.replace("\r", "").split("\n");
        for (String row : rows) {
            Matcher m = TIME.matcher(row);
            ArrayList<Long> times = new ArrayList<>();
            int lastEnd = 0;
            while (m.find()) {
                int mins = Integer.parseInt(m.group(1));
                int sec = Integer.parseInt(m.group(2));
                if (sec > 59) continue;
                String sub = m.group(3);
                int millis = sub == null ? 0 : sub.length() == 1 ? Integer.parseInt(sub) * 100
                        : sub.length() == 2 ? Integer.parseInt(sub) * 10 : Integer.parseInt(sub);
                times.add(((long) mins * 60 + sec) * 1000 + millis);
                lastEnd = m.end();
            }
            if (times.isEmpty()) continue; // ignora tags LRC como [ar:...], [offset:...] etc.
            String words = row.substring(lastEnd).trim();
            for (Long ms : times) lines.add(new Line(ms, words));
        }
        Collections.sort(lines, Comparator.comparingLong(a -> a.startMs));
        return lines;
    }
    public static int activeLine(List<Line> lines,long playbackMs) {
        if(lines==null || lines.isEmpty() || playbackMs<lines.get(0).startMs) return -1;
        int lo=0,hi=lines.size()-1;
        while(lo<=hi) {
            int mid=(lo+hi)>>>1;
            if(lines.get(mid).startMs<=playbackMs)lo=mid+1;else hi=mid-1;
        }
        return hi;
    }
}
