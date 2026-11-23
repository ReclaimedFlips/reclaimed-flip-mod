package com.reclaimedmarket.alerts;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Everything the mod does that is not Minecraft: ask Reclaimed Flip (running on THIS PC) for new alerts, format them,
 * and pass the commands you type to it. It never talks to Hypixel or anyone else, and it only reads and displays.
 */
public final class AlertsCore {
    /** What the Minecraft side must be able to show. */
    public interface Output {
        boolean ready();                              // true while a world is loaded and messages can be shown
        void say(String text);                        // a plain info line in chat
        void alert(String title, String detail, String cmd, String hover); // a flip alert in chat (two lines). cmd and hover can be empty
        void bar(String text);                        // a short line above the hotbar
    }

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public static final String DEFAULT_URL = "https://reclaimedflip.com";
    public String base = DEFAULT_URL;
    public boolean chat = true, bar = true, tips = true;
    public int maxPerPoll = 3, pollSeconds = 2;
    /** What clicking an alert does: "fill" (puts the command in your chat box, you press Enter), "open" (opens the app page) or "run" (sends the command). */
    public String click = "fill";
    public boolean ackRun = false, seenNotice = false;
    // Your own alert filters. They apply when the mod uses the shared server (reclaimedflip.com), whose settings nobody can change from here.
    public boolean alertsOn = true, kBz = true, kAh = true, kCrb = true, kCra = true;
    public double minProfit = 0, minAhProfit = 0, purse = 0; public int minConf = 0;   // purse: coins you can spend on one flip. Alerts stay off until it is set.
    private boolean warnedPurse = false;
    public String localBlocks = "";                    // names to hide, separated by |

    private final Output out;
    private final Path configFile;
    private long since = -1;
    private boolean warnedDown = false, wasConnected = false;
    private int failStreak = 0;                 // checks that failed in a row
    private long lastWarnAt = 0;                // when the last "cannot reach" line was shown
    private volatile boolean running = false;

    public AlertsCore(Output out, Path configFile) {
        this.out = out; this.configFile = configFile; load();
    }

    // ---- config ----
    private void load() {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(configFile)) { p.load(in); } catch (IOException ignored) { }
        base = p.getProperty("url", base).replaceAll("/+$", "");
        // Before config version 3 the default was the app on this PC. Anyone who never changed it now moves to the shared server.
        if (!"3".equals(p.getProperty("configVersion")) && base.equals("http://127.0.0.1:8765")) base = DEFAULT_URL;
        chat = Boolean.parseBoolean(p.getProperty("chat", "true"));
        bar = Boolean.parseBoolean(p.getProperty("bar", "true"));
        tips = Boolean.parseBoolean(p.getProperty("tips", "true"));
        maxPerPoll = clamp(p.getProperty("maxPerPoll", "3"), 1, 10, 3);
        pollSeconds = clamp(p.getProperty("pollSeconds", "2"), 1, 60, 2);
        if (p.getProperty("configVersion") == null && pollSeconds == 5) pollSeconds = 2;   // older default was 5 seconds
        String c = p.getProperty("click", "fill").trim().toLowerCase();
        click = c.equals("open") || c.equals("run") ? c : "fill";
        ackRun = Boolean.parseBoolean(p.getProperty("ackRun", "false"));
        seenNotice = Boolean.parseBoolean(p.getProperty("seenNotice", "false"));
        alertsOn = Boolean.parseBoolean(p.getProperty("alertsOn", "true"));
        kBz = Boolean.parseBoolean(p.getProperty("kBz", "true")); kAh = Boolean.parseBoolean(p.getProperty("kAh", "true"));
        kCrb = Boolean.parseBoolean(p.getProperty("kCrb", "true")); kCra = Boolean.parseBoolean(p.getProperty("kCra", "true"));
        minProfit = p.containsKey("profitUnit") ? parseDouble(p.getProperty("minProfit", "0"), 0) : 0;   // an older config held a whole-batch amount: start again from Any minAhProfit = parseDouble(p.getProperty("minAhProfit", "0"), 0);
        minConf = clamp(p.getProperty("minConf", "0"), 0, 100, 0); purse = parseDouble(p.getProperty("purse", "0"), 0); localBlocks = p.getProperty("blocks", "");
        if (click.equals("run") && !ackRun) click = "fill";      // run on click only counts after the warning was confirmed
        save();
    }

    private static int clamp(String s, int lo, int hi, int def) {
        try { return Math.max(lo, Math.min(hi, Integer.parseInt(s.trim()))); } catch (NumberFormatException e) { return def; }
    }

    private void save() {
        Properties p = new Properties();
        p.setProperty("url", base); p.setProperty("chat", String.valueOf(chat)); p.setProperty("bar", String.valueOf(bar)); p.setProperty("tips", String.valueOf(tips));
        p.setProperty("maxPerPoll", String.valueOf(maxPerPoll)); p.setProperty("pollSeconds", String.valueOf(pollSeconds));
        p.setProperty("click", click); p.setProperty("ackRun", String.valueOf(ackRun)); p.setProperty("seenNotice", String.valueOf(seenNotice)); p.setProperty("alertsOn", String.valueOf(alertsOn)); p.setProperty("kBz", String.valueOf(kBz)); p.setProperty("kAh", String.valueOf(kAh)); p.setProperty("kCrb", String.valueOf(kCrb)); p.setProperty("kCra", String.valueOf(kCra));
        p.setProperty("profitUnit", "2"); p.setProperty("minProfit", String.valueOf(minProfit)); p.setProperty("minAhProfit", String.valueOf(minAhProfit)); p.setProperty("minConf", String.valueOf(minConf)); p.setProperty("purse", String.valueOf(purse)); p.setProperty("blocks", localBlocks);
        p.setProperty("configVersion", "3");
        try (OutputStream o = Files.newOutputStream(configFile)) { p.store(o, "Reclaimed Flip Alerts. url is where prices come from: https://reclaimedflip.com by default, or http://127.0.0.1:8765 for Reclaimed Flip running on this PC."); } catch (IOException ignored) { }
    }

    /** Change what clicking an alert does. "run" is only accepted from the menu, after its warning screen was confirmed. */
    public synchronized void setClick(String mode, boolean confirmedRun) {
        if (mode.equals("run")) { if (!confirmedRun) return; ackRun = true; click = "run"; }
        else click = mode.equals("open") ? "open" : "fill";
        save();
    }

    public static String clickLabel(String mode) {
        switch (mode) { case "open": return "Open page"; case "run": return "Run on click"; default: return "Fill command"; }
    }

    // ---- your own filters (shared server) ----
    public boolean shared() { return base.equals(DEFAULT_URL); }

    /** True if an alert gets through your filters. f is one alert line: id, kind, name, profit (older servers: whole batch), confidence, detail, command, profit on one item. */
    private boolean passes(String[] f) {
        String k = f[1];
        if (k.equals("bazaar") && !kBz) return false;
        if (k.equals("auction") && !kAh) return false;
        if (k.equals("craft_bz") && !kCrb) return false;
        if (k.equals("craft_ah") && !kCra) return false;
        boolean bzKind = k.equals("bazaar") || k.equals("craft_bz");
        double profit = parseDouble(bzKind && f.length > 7 ? f[7] : f[3], 0);      // the profit on one item for the Bazaar, the profit of the one item for the Auction
        if ((k.equals("bazaar") || k.equals("craft_bz")) && profit < minProfit) return false;
        if ((k.equals("auction") || k.equals("craft_ah")) && profit < minAhProfit) return false;
        if (parseLong(f[4], 0) < minConf) return false;
        if (purse > 0 && f.length > 8 && parseDouble(f[8], 0) > purse) return false;   // costs more than your purse
        String nm = f[2].toLowerCase();
        double price = f.length > 8 ? parseDouble(f[8], -1) : -1;
        for (String b : localBlocks.split("\\|")) if (blockHits(b, k, nm, price)) return false;
        return true;
    }

    /** One block entry against one alert. Understands a bazaar: or auction: start, #ITEM_ID, plain words in the name, and "with:" rules that look for words in the item's description
     *  (reforge, stars, recomb, books, ultimate enchant, gems, runes, skins). A rule like sharpness>=6 works when the description shows a number next to the word. */
    private static boolean blockHits(String raw, String kind, String nm, double price) {
        String b = raw == null ? "" : raw.trim().toLowerCase();
        if (b.isEmpty()) return false;
        java.util.regex.Matcher um = java.util.regex.Pattern.compile("\\s+under:(\\S+)$").matcher(b);      // "... under:5m": only block when the price is below that
        if (um.find()) {
            double lim = parseAmount(um.group(1));
            if (lim <= 0 || price < 0 || price >= lim) return false;      // no price known or not cheap enough: it still alerts
            b = b.substring(0, um.start()).trim();
            if (b.isEmpty()) return false;
        }
        boolean bz = kind.equals("bazaar") || kind.equals("craft_bz");
        if (b.startsWith("bazaar:")) { if (!bz) return false; b = b.substring(7).trim(); }
        else if (b.startsWith("auction:")) { if (bz) return false; b = b.substring(8).trim(); }
        if (b.isEmpty()) return false;
        int w = b.indexOf("with:");
        if (w < 0) return itemHit(b, nm);
        String item = b.substring(0, w).trim();
        if (!item.isEmpty() && !itemHit(item, nm)) return false;
        boolean any = false;
        for (String c : b.substring(w).split("with:")) {
            c = c.trim().replace('_', ' ');
            if (c.isEmpty()) continue;
            any = true;
            if (!condHit(c, nm)) return false;
        }
        return any;
    }

    /** An item's own name for exact blocking: lower case, without a "(description)" tail, a [Lvl 10] start or star marks. "Ice Rune I" and "Ice Rune II" stay different. */
    public static String exactName(String s) {
        String t = s == null ? "" : s.toLowerCase();
        int p = t.indexOf(" ("); if (p > 0) t = t.substring(0, p);
        t = t.replaceAll("^\\[lvl \\d+\\]\\s*", "").replaceAll("[\\u272a\\u278a-\\u278e\\s]+$", "");
        return t.trim();
    }

    private static boolean itemHit(String item, String nm) {
        if (item.startsWith("=")) return exactName(nm).equals(exactName(item.substring(1)));      // exactly this item, not others that share words with it
        if (item.startsWith("#")) {                                   // an exact item picked from the list: match its name
            String[] seg = item.substring(1).split(";");
            String id = seg[0].equals("pet") && seg.length > 1 ? seg[1] : seg[0];
            return nm.contains(id.replace('_', ' '));
        }
        return nm.contains(item);
    }

    private static boolean condHit(String c, String nm) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(.+?)\\s*(>=|<=|=|>|<)\\s*(\\d+)$").matcher(c);
        if (!m.matches()) return nm.contains(c);
        String name = java.util.regex.Pattern.quote(m.group(1).trim()), op = m.group(2); double n = Double.parseDouble(m.group(3));
        for (String re : new String[]{name + "\\s+(\\d+)", "(\\d+)\\s+" + name}) {          // "sharpness 6" or "5 stars"
            java.util.regex.Matcher t = java.util.regex.Pattern.compile(re).matcher(nm);
            while (t.find()) {
                double v = Double.parseDouble(t.group(1));
                if ((op.equals(">=") && v >= n) || (op.equals("<=") && v <= n) || (op.equals("=") && v == n) || (op.equals(">") && v > n) || (op.equals("<") && v < n)) return true;
            }
        }
        return false;
    }

    /** A purse typed by the player: any amount above 0, with k, m or b. Returns -1 when it is not a usable amount. */
    public static double parsePurse(String s) {
        try { double v = parseCoins(s); return (v > 0 && v < 1e13 && !Double.isNaN(v)) ? Math.rint(v) : -1; } catch (Exception e) { return -1; }
    }
    public static double parseAmount(String s) {
        try { double v = parseCoins(s); return (v >= 0 && v < 1e13 && !Double.isNaN(v)) ? Math.rint(v) : -1; } catch (Exception e) { return -1; }
    }
    public boolean purseSet() { return purse > 0; }

    private static double parseCoins(String s) {
        String t = s.trim().toLowerCase().replace(",", ""); double m = 1;
        if (t.endsWith("k")) { m = 1e3; t = t.substring(0, t.length() - 1); } else if (t.endsWith("m")) { m = 1e6; t = t.substring(0, t.length() - 1); } else if (t.endsWith("b")) { m = 1e9; t = t.substring(0, t.length() - 1); }
        return Double.parseDouble(t) * m;
    }

    /** /rm commands for the shared server. Everything here is stored on your PC only and changes nothing on the server. */
    private List<String> localCommand(String cmd, String arg, List<String> res) {
        switch (cmd) {
            case "purse": {
                if (arg.isEmpty()) { res.add(purse > 0 ? "Your purse: " + shortNum(purse) + ". Change it with /rm purse 20m (k, m and b work, any amount)." : "Your purse is not set. Type /rm purse 50m (any amount, k, m and b work). Alerts stay off until you do."); return res; }
                double v = parsePurse(arg);
                if (v < 0) { res.add("Type an amount above 0, for example /rm purse 50m or /rm purse 12500000."); return res; }
                purse = v; save(); res.add("Purse set to " + shortNum(v) + ". Flips that cost more than this are hidden."); return res;
            }
            case "on": case "off":
                if (cmd.equals("on") && purse <= 0) { res.add("Set your purse first, for example /rm purse 50m. Alerts stay off until you do."); return res; }
                alertsOn = cmd.equals("on"); save(); res.add("Alerts " + cmd + " (only for you)."); return res;
            case "profit": case "ahprofit": {
                boolean bz = cmd.equals("profit");
                if (arg.isEmpty()) { res.add((bz ? "Smallest Bazaar profit: " : "Smallest Auction profit: ") + shortNum(bz ? minProfit : minAhProfit)); return res; }
                try { double v = Math.max(0, parseCoins(arg)); if (bz) minProfit = v; else minAhProfit = v; save(); res.add((bz ? "Smallest Bazaar profit" : "Smallest Auction profit") + " set to " + shortNum(v) + "."); }
                catch (Exception e) { res.add("Use a number, for example /rm " + cmd + " 20000 (k, m and b work: 20k, 1.5m)."); }
                return res;
            }
            case "conf": {
                if (arg.isEmpty()) { res.add("Lowest confidence: " + minConf); return res; }
                try { minConf = Math.max(0, Math.min(100, (int) Math.round(parseCoins(arg)))); save(); res.add("Lowest confidence set to " + minConf + "."); }
                catch (Exception e) { res.add("Use a number from 0 to 100, for example /rm conf 60."); }
                return res;
            }
            case "bazaar": case "auction": case "craftbz": case "craftah": {
                if (!arg.equals("on") && !arg.equals("off")) { res.add("Use /rm " + cmd + " on or /rm " + cmd + " off."); return res; }
                boolean v = arg.equals("on");
                if (cmd.equals("bazaar")) kBz = v; else if (cmd.equals("auction")) kAh = v; else if (cmd.equals("craftbz")) kCrb = v; else kCra = v;
                save(); res.add(cmd + " alerts " + arg + "."); return res;
            }
            case "block": {
                if (arg.isEmpty()) { res.add("Use /rm block followed by part of an item name, for example /rm block seeds."); return res; }
                boolean have = false;
                for (String e : localBlocks.split("\\|")) if (e.trim().equalsIgnoreCase(arg)) have = true;      // exact entry, so "rune" and "with:rune" can both be blocked
                if (!have) localBlocks = localBlocks.isEmpty() ? arg : localBlocks + "|" + arg;
                save(); res.add("Blocked: " + arg + "."); return res;
            }
            case "unblock": {
                StringBuilder sb = new StringBuilder(); boolean found = false;
                for (String b : localBlocks.split("\\|")) { if (b.isBlank()) continue; if (b.equalsIgnoreCase(arg)) { found = true; continue; } if (sb.length() > 0) sb.append('|'); sb.append(b); }
                localBlocks = sb.toString(); save(); res.add(found ? "Unblocked: " + arg + "." : arg + " was not on your block list."); return res;
            }
            case "blocked": case "blocklist":
                res.add("Blocked: " + (localBlocks.isBlank() ? "nothing" : localBlocks.replace("|", ", "))); return res;
            case "status": case "help": case "":
                res.add("Alerts " + (alertsOn ? "on" : "off") + ". Bazaar " + (kBz ? "on" : "off") + ", Auction " + (kAh ? "on" : "off") + ", Crafts (Bazaar) " + (kCrb ? "on" : "off") + ", Crafts (Auction) " + (kCra ? "on" : "off") + ".");
                res.add("Purse: " + (purse > 0 ? shortNum(purse) : "not set (alerts are off until you set it: /rm purse 50m)") + ".");
                res.add("Smallest profit: Bazaar " + shortNum(minProfit) + ", Auction " + shortNum(minAhProfit) + ". Lowest confidence: " + minConf + ".");
                res.add("Commands: /rm on|off, purse, profit, ahprofit, conf, bazaar|auction|craftbz|craftah on|off, block, unblock, blocked, find, stats, chat, bar, tips, click, menu.");
                return res;
            default:
                res.add("That setting is fixed on the shared server (reclaimedflip.com). To change it, run your own Reclaimed Flip and set url=http://127.0.0.1:8765 in the mod config.");
                return res;
        }
    }

    // ---- formatting ----
    public static String shortNum(double n) {
        double a = Math.abs(n);
        if (a >= 1e9) return String.format("%.2fB", n / 1e9);
        if (a >= 1e6) return String.format("%.2fM", n / 1e6);
        if (a >= 1e4) return String.format("%.1fk", n / 1e3);
        if (a >= 100) return String.format("%,.0f", n);
        return String.format("%.1f", n);
    }

    static String kindLabel(String kind) {
        switch (kind) {
            case "bazaar": return "Bazaar";
            case "auction": return "Auction";
            case "craft_bz": return "Craft, Bazaar";
            case "craft_ah": return "Craft, Auction House";
            default: return kind;
        }
    }

    // ---- polling ----
    /** One check. Returns how many new alerts were shown (or counted when the world is not loaded). */
    public int pollOnce() {
        String body;
        try { body = get("/api/alerts/mod?v=2&since=" + since); }
        catch (Exception e) {
            failStreak++;                          // one slow or failed check is normal (a server restart, a network blip): only warn after several in a row, and at most once every 10 minutes
            if (!warnedDown && failStreak >= 4 && System.currentTimeMillis() - lastWarnAt > 600_000L && out.ready()) { out.say("Cannot reach Reclaimed Flip (" + base + "), so alerts are paused. They resume by themselves when it is reachable again."); warnedDown = true; lastWarnAt = System.currentTimeMillis(); }
            wasConnected = false; return 0;
        }
        failStreak = 0;
        if (warnedDown) { warnedDown = false; if (out.ready()) out.say("Connected to Reclaimed Flip again."); }
        String[] lines = body.split("\n");
        if (lines.length == 0 || !lines[0].startsWith("latest\t")) return 0;
        String[] head = lines[0].split("\t");
        long latest; try { latest = Long.parseLong(head[1]); } catch (Exception e) { return 0; }
        boolean on = head.length > 2 && head[2].equals("on") && (!shared() || (alertsOn && purse > 0));
        if (shared() && purse <= 0 && !warnedPurse && out.ready()) { warnedPurse = true; out.say("Reclaimed Flip alerts are waiting for your purse. Type /rm purse 50m (any amount you can spend on one flip; k, m and b work) or open /rm menu."); }
        if (!wasConnected) { wasConnected = true; if (since < 0) { since = latest; return 0; } }
        if (latest < since) { since = latest; return 0; }          // the alert list was cleared
        List<String[]> items = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) { String[] f = lines[i].split("\t", 9); if (f.length >= 6 && (!shared() || passes(f))) items.add(f); }
        if (items.isEmpty()) { since = Math.max(since, latest); return 0; }
        if (on && out.ready() && !seenNotice) {
            seenNotice = true; save();
            out.say("This is an unofficial mod. It only shows flips from Reclaimed Flip (" + (base.equals(DEFAULT_URL) ? "reclaimedflip.com" : base) + ").");
            out.say("Hypixel does not list it as an approved mod, and unlisted mods count as disallowed by default. Use it at your own risk.");
            out.say("Clicking an alert only fills a command into your chat box. You press Enter yourself. Type /rm menu to change this.");
            out.say("Opening an auction or the Bazaar from anywhere needs a Booster Cookie. Without one, go to the Hub's Auction House first.");
        }
        int shown = 0;
        for (String[] f : items) {
            since = Math.max(since, parseLong(f[0], since));
            if (!on || !out.ready()) continue;
            if (shown < maxPerPoll) { show(f); shown++; }
        }
        since = Math.max(since, latest);
        int extra = items.size() - shown;
        if (on && out.ready() && extra > 0) out.say("+" + extra + " more flip" + (extra == 1 ? "" : "s") + ". See the Alerts tab in Reclaimed Flip.");
        return items.size();
    }

    private static double parseDouble(String s, double def) { try { return Double.parseDouble(s); } catch (NumberFormatException e) { return def; } }
    private static long parseLong(String s, long def) { try { return Long.parseLong(s); } catch (NumberFormatException e) { return def; } }

    private void show(String[] f) {
        String name = f[2], kind = kindLabel(f[1]), conf = f[4], detail = f[5];
        String cmd = f.length > 6 ? f[6].trim() : "";
        // One profit number: on one item for the Bazaar (when the server sends it), the item's own profit for the Auction.
        boolean bzKind = f[1].equals("bazaar") || f[1].equals("craft_bz");
        String profitTxt = shortNum(bzKind && f.length > 7 ? parseDouble(f[7], 0) : parseLong(f[3], 0));
        String gain = "+" + profitTxt + " profit";
        String hover = name + "\n" + kind + "\n" + detail + "\nProfit " + profitTxt + ", confidence " + conf
                + (cmd.isEmpty() ? "" : "\n\nClick: " + (click.equals("open") ? "open Reclaimed Flip" : click.equals("run") ? "send " + cmd : "put " + cmd + " in your chat box"));
        if (chat) out.alert(name + "  " + gain, kind + ": " + detail, cmd, hover);
        if (bar) out.bar(name + ": " + gain);
    }

    // ---- commands typed in game as /rm ... ----
    /** Handles the text after "/rm". Returns the lines to print. Blocks briefly while it asks the app. */
    public List<String> command(String args) {
        String a = args == null ? "" : args.trim();
        String[] parts = a.split("\\s+", 2);
        String cmd = parts[0].toLowerCase(), arg = parts.length > 1 ? parts[1].trim().toLowerCase() : "";
        List<String> res = new ArrayList<>();
        if (cmd.equals("chat") || cmd.equals("bar")) {
            if (!arg.equals("on") && !arg.equals("off")) { res.add("Use /rm " + cmd + " on or /rm " + cmd + " off."); return res; }
            if (cmd.equals("chat")) chat = arg.equals("on"); else bar = arg.equals("on");
            save(); res.add((cmd.equals("chat") ? "Chat alerts " : "On-screen alerts ") + arg + "."); return res;
        }
        if (cmd.equals("tips")) {
            if (!arg.equals("on") && !arg.equals("off")) { res.add("Item tooltips are " + (tips ? "on" : "off") + ". Use /rm tips on or /rm tips off."); return res; }
            tips = arg.equals("on"); save(); res.add("Item tooltips " + arg + ". " + (tips ? "Hover an item on Hypixel to see its buy order, instant buy and craft cost." : "")); return res;
        }
        if (cmd.equals("click")) {
            if (arg.equals("fill") || arg.equals("open")) { setClick(arg, false); res.add("Clicking an alert will now: " + clickLabel(arg).toLowerCase() + "."); }
            else if (arg.equals("run")) res.add("Run on click can only be turned on in /rm menu, where a warning is shown first.");
            else res.add("Clicking an alert currently: " + clickLabel(click).toLowerCase() + ". Use /rm click fill or /rm click open. Run on click is in /rm menu.");
            return res;
        }
        if (cmd.equals("find") || cmd.equals("search")) {
            if (arg.length() < 2) { res.add("Use /rm find followed by an item name, for example /rm find hyperion."); return res; }
            try {
                List<String[]> hit = items(arg);
                if (hit.isEmpty()) { res.add("No item found for \"" + arg + "\"."); return res; }
                res.add(hit.get(0)[1] + (hit.size() > 1 ? "  (" + (hit.size() - 1) + " more match" + (hit.size() > 2 ? "es" : "") + ": " + hit.get(1)[1] + (hit.size() > 2 ? ", ..." : "") + ")" : ""));
                List<String> t = tip(hit.get(0)[1]); if (t.isEmpty()) res.add("No price data for this item right now."); else res.addAll(t);
            } catch (Exception e) { res.add("Could not reach Reclaimed Flip. Check your internet or the url in the config."); }
            return res;
        }
        if (shared()) return localCommand(cmd, arg, res);
        try { for (String line : post("/api/mod/cmd", a.isEmpty() ? "help" : a).split("\n")) if (!line.isBlank()) res.add(line); }
        catch (Exception e) { res.add("Could not reach Reclaimed Flip. Check your internet or the url in the config."); }
        return res;
    }

    // ---- settings for the menu ----
    /** Current alert settings from the app. "block" entries are collected under the key "blocks", separated by a tab. */
    public java.util.Map<String, String> settings() throws Exception {
        java.util.Map<String, String> m = new java.util.LinkedHashMap<>(); StringBuilder blocks = new StringBuilder();
        for (String line : get("/api/mod/settings").split("\n")) {
            String[] f = line.split("\t", 2); if (f.length < 2 || f[0].isEmpty()) continue;
            if (f[0].equals("block")) { if (blocks.length() > 0) blocks.append('\t'); blocks.append(f[1]); } else m.put(f[0], f[1].trim());
        }
        if (shared()) {      // on the shared server these are your own settings, kept on your PC
            m.put("alerts_on", (alertsOn && purse > 0) ? "1" : "0"); m.put("bankroll", String.valueOf((long) purse)); m.put("alert_min_profit", String.valueOf((long) minProfit)); m.put("alert_ah_min_profit", String.valueOf((long) minAhProfit));
            m.put("alert_min_conf", String.valueOf(minConf)); m.put("alert_bz", kBz ? "1" : "0"); m.put("alert_ah", kAh ? "1" : "0"); m.put("alert_crb", kCrb ? "1" : "0"); m.put("alert_cra", kCra ? "1" : "0");
            return withBlocks(m, localBlocks.isBlank() ? "" : localBlocks.replace("|", "\t"));
        }
        m.put("blocks", blocks.toString()); return m;
    }

    private static java.util.Map<String, String> withBlocks(java.util.Map<String, String> m, String b) { m.put("blocks", b); return m; }

    /** Known SkyBlock items that match a name or an item id. Each entry is {id, name}. */
    public List<String[]> items(String q) throws Exception { return items(q, "both"); }

    /** Items matching a search. scope "bazaar" lists only what the Bazaar sells, "auction" only what the Auction House sells. */
    public List<String[]> items(String q, String scope) throws Exception {
        List<String[]> out = new ArrayList<>();
        String enc = java.net.URLEncoder.encode(q == null ? "" : q.trim(), StandardCharsets.UTF_8);
        String sc = "bazaar".equals(scope) || "auction".equals(scope) ? "&scope=" + scope : "";
        for (String line : get("/api/mod/items?q=" + enc + sc).split("\n")) {
            String[] f = line.split("\t", 2); if (f.length == 2 && !f[0].isEmpty()) out.add(f);
        }
        return out;
    }

    /** Your auction stats ({label, value} rows) for a linked Minecraft account. Asks Reclaimed Flip only. A row labelled "error" holds a message. */
    public List<String[]> stats(String uuid) throws Exception {
        List<String[]> out = new ArrayList<>();
        for (String line : get("/api/mod/stats?uuid=" + java.net.URLEncoder.encode(uuid == null ? "" : uuid, StandardCharsets.UTF_8)).split("\n")) {
            String[] f = line.split("\t", 2); if (f.length == 2 && !f[0].isEmpty()) out.add(f);
        }
        return out;
    }

    /** Price lines for the item whose tooltip is open. Asks Reclaimed Flip only (reclaimedflip.com by default). */
    public List<String> tip(String itemName) throws Exception {
        List<String> out = new ArrayList<>();
        for (String line : get("/api/mod/tip?name=" + java.net.URLEncoder.encode(itemName, StandardCharsets.UTF_8)).split("\n")) if (!line.isBlank()) out.add(line.trim());
        return out;
    }

    /** Worth lines for a piece of gear. snbt is the item's data in Minecraft text form. Asks the app only. */
    public List<String> gear(String snbt) throws Exception {
        List<String> out = new ArrayList<>();
        for (String line : post("/api/mod/gear", snbt).split("\n")) if (!line.isBlank()) out.add(line.trim());
        return out;
    }

    // ---- loop ----
    public void start() {
        if (running) return; running = true;
        Thread t = new Thread(() -> {
            while (running) {
                try { pollOnce(); } catch (Exception ignored) { }
                try { Thread.sleep(pollSeconds * 1000L); } catch (InterruptedException e) { return; }
            }
        }, "reclaimedmarket-alerts");
        t.setDaemon(true); t.start();
    }

    // ---- http ----
    private String get(String path) throws Exception {
        HttpRequest r = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(5)).header("User-Agent", "ReclaimedFlipMod/0.6").GET().build();
        HttpResponse<String> resp = HTTP.send(r, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) throw new IOException("HTTP " + resp.statusCode());
        return resp.body();
    }

    /** Sends a small JSON body (used for the opt-in Bazaar session sharing). */
    public String postJson(String path, String json) throws Exception {
        HttpRequest r = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(8)).header("User-Agent", "ReclaimedFlipMod/0.8")
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)).build();
        HttpResponse<String> resp = HTTP.send(r, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) throw new IOException("HTTP " + resp.statusCode());
        return resp.body();
    }

    private String post(String path, String text) throws Exception {
        HttpRequest r = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(5)).header("User-Agent", "ReclaimedFlipMod/0.6")
                .header("Content-Type", "text/plain; charset=utf-8").POST(HttpRequest.BodyPublishers.ofString(text, StandardCharsets.UTF_8)).build();
        HttpResponse<String> resp = HTTP.send(r, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) throw new IOException("HTTP " + resp.statusCode());
        return resp.body();
    }
}
