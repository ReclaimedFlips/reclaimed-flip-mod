package com.reclaimedmarket.alerts;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Local Bazaar profit tracker. Plain Java, no Minecraft code.
 * It only READS the Bazaar lines Hypixel already shows you in chat. Nothing is sent anywhere, and it never clicks or buys.
 * Buys add stock at what you paid. Sells take the oldest stock first (FIFO). Profit = coins received - cost of the stock sold.
 * A sell with no known stock (bought before you started tracking) is counted as "unmatched" and left out of profit.
 * Coins are used exactly as the chat line shows them. No tax is added or removed here.
 */
public final class BzTracker {
    public static final class Event {
        public final long ts; public final boolean buy; public final String item; public final double units, coins;
        Event(long ts, boolean buy, String item, double units, double coins) { this.ts = ts; this.buy = buy; this.item = item; this.units = units; this.coins = coins; }
    }

    /** One finished sale matched against stock. */
    public static final class Flip {
        public final long ts; public final String item; public final double units, cost, revenue;
        Flip(long ts, String item, double units, double cost, double revenue) { this.ts = ts; this.item = item; this.units = units; this.cost = cost; this.revenue = revenue; }
        public double profit() { return revenue - cost; }
    }

    private static final String N = "([0-9][0-9,]*(?:\\.[0-9]+)?)";
    // Lines as Hypixel words them. If a wording is different on your account, add it here. Unknown lines are ignored.
    private static final Pattern CLAIM_BUY = Pattern.compile("Claimed " + N + "x (.+?) worth " + N + " coins bought for .*", Pattern.CASE_INSENSITIVE);
    private static final Pattern CLAIM_SELL = Pattern.compile("Claimed " + N + " coins from selling " + N + "x (.+?) at .*", Pattern.CASE_INSENSITIVE);
    private static final Pattern INSTANT_BUY = Pattern.compile("Bought " + N + "x (.+?) for " + N + " coins.*", Pattern.CASE_INSENSITIVE);
    private static final Pattern INSTANT_SELL = Pattern.compile("Sold " + N + "x (.+?) for " + N + " coins.*", Pattern.CASE_INSENSITIVE);

    private static double d(String s) { return Double.parseDouble(s.replace(",", "")); }

    /** Removes Minecraft colour codes (the section sign plus a character). */
    public static String plain(String s) { return s == null ? "" : s.replaceAll("§.", "").trim(); }

    /** Reads one chat line. Returns null for anything that is not a Bazaar trade line. */
    public static Event parse(String raw, long now) {
        String line = plain(raw);
        if (!line.startsWith("[Bazaar]")) return null;
        line = line.substring(8).trim();
        Matcher m;
        try {
            if ((m = CLAIM_BUY.matcher(line)).matches()) return new Event(now, true, m.group(2).trim(), d(m.group(1)), d(m.group(3)));
            if ((m = CLAIM_SELL.matcher(line)).matches()) return new Event(now, false, m.group(3).trim(), d(m.group(2)), d(m.group(1)));
            if ((m = INSTANT_BUY.matcher(line)).matches()) return new Event(now, true, m.group(2).trim(), d(m.group(1)), d(m.group(3)));
            if ((m = INSTANT_SELL.matcher(line)).matches()) return new Event(now, false, m.group(2).trim(), d(m.group(1)), d(m.group(3)));
        } catch (NumberFormatException e) { return null; }
        return null;
    }

    private static final class Lot { double units, perUnit; Lot(double u, double p) { units = u; perUnit = p; } }

    private final Map<String, Deque<Lot>> stock = new HashMap<>();
    private final List<Event> events = new ArrayList<>();
    private final List<Flip> flips = new ArrayList<>();
    private int unmatched = 0;
    private long sessionStart;
    private final Path file;

    public BzTracker(Path file, long now) { this.file = file; this.sessionStart = now; load(); }

    public synchronized void accept(Event e) {
        if (e == null) return;
        events.add(e); apply(e); save(e);
    }

    private void apply(Event e) {
        String key = e.item.toLowerCase();
        if (e.buy) { stock.computeIfAbsent(key, k -> new ArrayDeque<>()).addLast(new Lot(e.units, e.coins / e.units)); return; }
        Deque<Lot> q = stock.get(key);
        double need = e.units, cost = 0, got = 0;
        while (need > 1e-9 && q != null && !q.isEmpty()) {
            Lot l = q.peekFirst(); double take = Math.min(l.units, need);
            cost += take * l.perUnit; got += take; need -= take; l.units -= take;
            if (l.units <= 1e-9) q.pollFirst();
        }
        if (got <= 1e-9) { unmatched++; return; }
        flips.add(new Flip(e.ts, e.item, got, cost, e.coins * (got / e.units)));
        if (need > 1e-9) unmatched++;
    }

    public synchronized void newSession(long now) { sessionStart = now; stock.clear(); unmatched = 0; }

    /** Profit of the flips since the session began (or since `since` if given). */
    public synchronized double profit(long since) { double p = 0; for (Flip f : flips) if (f.ts >= since) p += f.profit(); return p; }
    public synchronized double sessionProfit() { return profit(sessionStart); }
    public synchronized int sessionFlips() { int n = 0; for (Flip f : flips) if (f.ts >= sessionStart) n++; return n; }
    public synchronized double allTimeProfit() { return profit(0); }
    public synchronized int unmatched() { return unmatched; }
    public synchronized long sessionStart() { return sessionStart; }
    /** Profits of the last n flips of this session, oldest first. */
    public synchronized double[] recentProfits(int n) {
        List<Double> l = new ArrayList<>();
        for (int i = flips.size() - 1; i >= 0 && l.size() < n; i--) if (flips.get(i).ts >= sessionStart) l.add(0, flips.get(i).profit());
        double[] r = new double[l.size()]; for (int i = 0; i < r.length; i++) r[i] = l.get(i);
        return r;
    }
    public synchronized Flip last() { for (int i = flips.size() - 1; i >= 0; i--) if (flips.get(i).ts >= sessionStart) return flips.get(i); return null; }

    /** Coins per hour since the session began, using the time of the first flip so idle time before it is not counted. */
    public synchronized double perHour(long now) {
        long first = -1; for (Flip f : flips) if (f.ts >= sessionStart) { first = f.ts; break; }
        if (first < 0) return 0;
        double hours = Math.max(now - first, 60_000L) / 3_600_000.0;     // at least one minute, so one early flip does not look huge
        return sessionProfit() / hours;
    }

    // ---- storage: one tab separated line per event, replayed on start. Local file only. ----
    private void load() {
        if (file == null || !Files.exists(file)) return;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String[] p = line.split("\t");
                if (p.length < 5) continue;
                try { Event e = new Event(Long.parseLong(p[0]), p[1].equals("B"), p[2], Double.parseDouble(p[3]), Double.parseDouble(p[4])); events.add(e); apply(e); }
                catch (NumberFormatException ignored) { }
            }
        } catch (IOException ignored) { }
        // Every start is a fresh session: the saved history is kept only for the "All time" total.
        // Stock bought in an earlier session is forgotten, so a sell only counts when its buy was also this session.
        stock.clear(); unmatched = 0;
    }

    private void save(Event e) {
        if (file == null) return;
        try {
            Files.writeString(file, e.ts + "\t" + (e.buy ? "B" : "S") + "\t" + e.item.replace('\t', ' ') + "\t" + e.units + "\t" + e.coins + "\n",
                    StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException ignored) { }
    }

    /** Deletes the saved history on this PC and starts from nothing. */
    public synchronized void clearAll(long now) {
        stock.clear(); events.clear(); flips.clear(); unmatched = 0; sessionStart = now;
        if (file != null) try { Files.deleteIfExists(file); } catch (IOException ignored) { }
    }
}
