package com.reclaimedmarket.alerts;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The settings menu (type /rm). Every button just changes an alert filter in Reclaimed Flip on your own PC.
 * Nothing here touches the game or the server. A "dropdown" opens a list of choices to pick from.
 */
public class SettingsScreen extends Screen {
    /** One dropdown: which setting it changes, what to type for the app, and the choices. */
    private static final class Dd {
        final String key, cmd, title, short_; final double[] vals; final String[] labels;
        Dd(String key, String cmd, String title, String short_, double[] vals, String[] labels) { this.key = key; this.cmd = cmd; this.title = title; this.short_ = short_; this.vals = vals; this.labels = labels; }
    }

    private static final Dd PROFIT = new Dd("alert_min_profit", "profit", "Smallest Bazaar profit", "Min profit",
            new double[]{0, 50, 100, 250, 500, 1e3, 5e3, 1e4}, new String[]{"Any", "50", "100", "250", "500", "1k", "5k", "10k"});
    private static final Dd AH_PROFIT = new Dd("alert_ah_min_profit", "ahprofit", "Smallest Auction profit", "Min profit",
            new double[]{0, 5e4, 1e5, 2.5e5, 5e5, 1e6, 5e6, 1e7}, new String[]{"Any", "50k", "100k", "250k", "500k", "1m", "5m", "10m"});
    private static final Dd CONF = new Dd("alert_min_conf", "conf", "Lowest confidence", "Min confidence",
            new double[]{0, 40, 50, 60, 70, 80, 90}, new String[]{"Any", "40", "50", "60", "70", "80", "90"});
    private static final Dd VOL = new Dd("alert_min_volume", "volume", "Smallest weekly Bazaar volume", "Min Bazaar volume",
            new double[]{1e3, 1e4, 5e4, 1e5, 5e5, 1e6}, new String[]{"1k", "10k", "50k", "100k", "500k", "1m"});
    private static final Dd PURSE = new Dd("bankroll", "purse", "Your purse", "Purse",
            new double[]{0}, new String[]{"Not set"});
    private static boolean typed(Dd d) { return d == PURSE || d == PROFIT || d == AH_PROFIT || d == VOL; }

    private static final String[][] TYPES = {{"alert_bz", "bazaar", "Bazaar flips"}, {"alert_ah", "auction", "Auction flips"},
            {"alert_crb", "craftbz", "Crafts, Bazaar"}, {"alert_cra", "craftah", "Crafts, Auction"}};

    private final AlertsCore core;
    private final Map<String, String> s;
    private final List<String> blocks = new ArrayList<>();
    private Dd picking = null;
    private boolean pickingClick = false;
    private boolean pickingParts = false;
    private int tab = 0;
    private final List<Consumer<GuiGraphicsExtractor>> deco = new ArrayList<>();
    private String boxText = "";
    private String note = "Reclaimed Flip Alerts";

    private final BzHud hud;

    public SettingsScreen(AlertsCore core, Map<String, String> settings) { this(core, settings, BzHud.INSTANCE); }

    public SettingsScreen(AlertsCore core, Map<String, String> settings, BzHud hud) {
        super(Component.literal("Reclaimed Flip Alerts"));
        this.core = core; this.s = settings; this.hud = hud;
        String b = settings.getOrDefault("blocks", "");
        if (!b.isEmpty()) for (String x : b.split("\t")) if (!x.isBlank()) blocks.add(x);
    }

    // ---- sending changes to the app (never blocks the game) ----
    private void send(String command) {
        Thread t = new Thread(() -> {
            List<String> r = core.command(command);
            if (!r.isEmpty() && r.get(0).startsWith("Could not reach")) { note = "Cannot reach Reclaimed Flip. Is it running?"; }
        }, "reclaimedmarket-menu");
        t.setDaemon(true); t.start();
    }

    private boolean on(String key) { return "1".equals(s.get(key)); }

    private static String num(double v) { return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v); }

    private String current(Dd d) {
        double v; try { v = Double.parseDouble(s.getOrDefault(d.key, "0")); } catch (NumberFormatException e) { v = 0; }
        for (int i = 0; i < d.vals.length; i++) if (Math.abs(d.vals[i] - v) < 1e-9) return d.labels[i];
        return AlertsCore.shortNum(v);
    }

    // ---- look: dark teal backdrop, one card per tab, label + short explanation + small button (Reclaimed Flip colours) ----
    private static final int GOLD = 0xFFF0B93A, TEAL = 0xFF7FD6DC, WHITE = 0xFFEDEDED, GRAY = 0xFF9FB4B8;
    private static final int ROW = 25, HEAD = 20, PAD = 4, PW = 360;
    private static final String[] TABS = {"Bazaar", "Auction", "Alerts", "Search", "Stats", "Profit"};

    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        super.extractBackground(g, mouseX, mouseY, delta);
        g.fillGradient(0, 0, this.width, this.height, 0xD0041418, 0xE60B3A41);
        for (Consumer<GuiGraphicsExtractor> d : deco) d.accept(g);
    }

    private void text(int x, int y, String s, int color) { deco.add(g -> g.text(this.font, s, x, y, color)); }

    private int px() { return this.width / 2 - PW / 2; }

    /** The card behind a tab's rows. Returns the y of the first row. */
    private int card(int y, String title, int h) {
        final int x = px();
        deco.add(g -> { g.fill(x, y, x + PW, y + h, 0xC00A2F35); g.outline(x, y, PW, h, 0x557FD6DC); g.fill(x, y, x + 3, y + h, TEAL); });
        text(x + 9, y + 6, title, GOLD);
        return y + HEAD;
    }

    /** One setting: a label, a one-line explanation under it, and a small button on the right. */
    private Button row(int y, int index, String label, String desc, String shown, Button.OnPress press) {
        final int x = px();
        if (index > 0) deco.add(g -> g.fill(x + 9, y, x + PW - 9, y + 1, 0x337FD6DC));
        text(x + 9, y + 4, label, WHITE);
        text(x + 9, y + 14, desc, GRAY);
        return addRenderableWidget(Button.builder(Component.literal(shown), press).bounds(x + PW - 104, y + 4, 96, 16).build());
    }

    private void header(int cx, String sub) {
        deco.add(g -> g.fill(0, 0, this.width, 3, TEAL));
        deco.add(g -> g.centeredText(this.font, "RECLAIMED FLIP", cx, 8, GOLD));
        deco.add(g -> g.centeredText(this.font, sub, cx, 19, GRAY));
    }

    private void rebuild() { if (box != null) boxText = box.getValue(); rebuildWidgets(); }

    private EditBox box;
    private EditBox priceBox;
    private String priceText = "";      // optional: only block items cheaper than this

    private static String onOff(boolean b) { return b ? "On" : "Off"; }

    @Override
    protected void init() {
        box = null; deco.clear();
        int cx = this.width / 2;
        if (pickingClick) { initClickPicker(cx); return; }
        if (pickingParts) { initPartsScreen(cx); return; }
        if (picking != null) { initPicker(cx); return; }
        header(cx, "Click to change. Saves instantly");
        int n = TABS.length, x = px(), tw = (PW - 4 * (n - 1)) / n;
        for (int i = 0; i < n; i++) {
            final int idx = i;
            Component label = Component.literal(TABS[i]);
            if (i == tab) label = Component.literal(TABS[i]).withStyle(ChatFormatting.GOLD);
            addRenderableWidget(Button.builder(label, btn -> { if (tab != idx) { tab = idx; boxText = ""; if (idx == 4 && statRows.isEmpty()) loadStats(); rebuild(); } }).bounds(x + i * (tw + 4), 32, tw, 18).build());
        }
        deco.add(g -> g.fill(x + tab * (tw + 4), 50, x + tab * (tw + 4) + tw, 52, GOLD));
        if (tab == 0) initBazaarTab(); else if (tab == 1) initAuctionTab(); else if (tab == 2) initGeneralTab(); else if (tab == 3) initSearchTab(); else if (tab == 4) initStatsTab(); else initProfitTab();
        addRenderableWidget(Button.builder(Component.literal("Done"), btn -> this.onClose()).bounds(x + PW - 56, 6, 56, 18).build());
        addRenderableWidget(Button.builder(Component.literal("Join our Discord"), btn -> Link.open(Link.DISCORD)).bounds(x + (PW - 150) / 2, this.height - 24, 150, 18).build());
    }

    /** A row with an On/Off button for one of the flip types. */
    private void toggleRow(int y, int index, String key, String cmd, String label, String desc) {
        row(y, index, label, desc, onOff(on(key)), btn -> {
            boolean now = !on(key); s.put(key, now ? "1" : "0"); send(cmd + (now ? " on" : " off")); btn.setMessage(Component.literal(onOff(now)));
        });
    }

    /** A row whose button opens a list of choices. */
    private void pickRow(int y, int index, Dd dd, String desc) {
        row(y, index, dd.short_, desc, current(dd) + "", btn -> { picking = dd; rebuild(); });
    }

    private void initBazaarTab() {
        int y = card(56, "Bazaar alerts", HEAD + 4 * ROW + PAD), i = 0;
        toggleRow(y, i++, "alert_bz", "bazaar", "Bazaar flips", "Buy order, then sell offer");
        y += ROW; toggleRow(y, i++, "alert_crb", "craftbz", "Crafts, Bazaar", "Craft from Bazaar, sell on Bazaar");
        y += ROW; pickRow(y, i++, PROFIT, "Shows only flips with this profit or more");
        y += ROW; pickRow(y, i, VOL, "Hides items that sell slowly");
        scope = "bazaar"; initBlockSection(y + ROW + PAD + 4);
    }

    private void initAuctionTab() {
        int y = card(56, "Auction alerts", HEAD + 3 * ROW + PAD), i = 0;
        toggleRow(y, i++, "alert_ah", "auction", "Auction flips", "Auction flip alerts, Buy It Now only");
        y += ROW; toggleRow(y, i++, "alert_cra", "craftah", "Crafts, Auction", "Craft from Bazaar, sell at Auction");
        y += ROW; pickRow(y, i, AH_PROFIT, "Shows only items with this profit or more");
        scope = "auction"; initBlockSection(y + ROW + PAD + 4);
    }

    private void initGeneralTab() {
        int y = card(56, "All alerts", HEAD + 6 * ROW + PAD), i = 0;
        row(y, i++, "Alerts", "Turn flip alerts on or off", onOff(on("alerts_on")), btn -> {
            boolean now = !on("alerts_on");
            double pv = 0; try { pv = Double.parseDouble(s.getOrDefault("bankroll", "0")); } catch (NumberFormatException e) { }
            if (now && pv <= 0) { purseNote = "Set your purse first. Alerts cannot start without it."; picking = PURSE; rebuild(); return; }
            s.put("alerts_on", now ? "1" : "0"); send(now ? "on" : "off"); btn.setMessage(Component.literal(onOff(now)));
        });
        y += ROW; pickRow(y, i++, PURSE, "Required. Coins you can spend on one flip");
        y += ROW; pickRow(y, i++, CONF, "Estimated risk of a flip. Higher = safer");
        y += ROW; row(y, i++, "Chat lines", "Each alert as a chat message", onOff(core.chat), btn -> { core.command("chat " + (core.chat ? "off" : "on")); btn.setMessage(Component.literal(onOff(core.chat))); });
        y += ROW; row(y, i++, "Above hotbar", "A short line above your hotbar", onOff(core.bar), btn -> { core.command("bar " + (core.bar ? "off" : "on")); btn.setMessage(Component.literal(onOff(core.bar))); });
        y += ROW; row(y, i, "Click alert", "What a click does. Fill is safest", AlertsCore.clickLabel(core.click) + "", btn -> { pickingClick = true; rebuild(); });
    }

    private final List<String[]> sugg = new ArrayList<>();
    private String suggNote = "";

    private String scope = "both";   // which side a new block applies to: both, bazaar or auction

    /** Short tag shown on a chip for a one-sided block. */
    private static String tag(String entry) {
        String e = entry.toLowerCase();
        return e.startsWith("bazaar:") ? "BZ " : e.startsWith("auction:") ? "AH " : "";
    }

    private static String bare(String entry) {
        int i = entry.indexOf(':');
        if (i > 0) { String h = entry.substring(0, i).toLowerCase(); if (h.equals("bazaar") || h.equals("auction")) return entry.substring(i + 1).trim(); }
        return entry;
    }

    private String scopePrefix() { return scope.equals("both") ? "" : scope + ":"; }

    private static String itemLabel(String full) {
        String entry = bare(full);
        java.util.regex.Matcher um = java.util.regex.Pattern.compile("\\s+under:(\\S+)$", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(entry);
        if (um.find()) return itemLabel(entry.substring(0, um.start())) + " <" + um.group(1);      // "has rune <5m"
        if (entry.toLowerCase().contains("with:")) {            // a modifier rule: "with:rune" shows as "has rune", "#HYPERION with:withered" as "Hyperion has withered"
            String[] p = entry.split("(?i)\\s*with:\\s*");
            StringBuilder c = new StringBuilder();
            for (int i = 1; i < p.length; i++) { if (c.length() > 0) c.append(" + "); c.append(p[i].trim()); }
            String item = p.length > 0 ? p[0].trim() : "";
            return (item.isEmpty() ? "has " : itemLabel(item) + " has ") + c;
        }
        if (entry.startsWith("=")) return entry.substring(1).trim();
        if (!entry.startsWith("#")) return entry;
        StringBuilder sb = new StringBuilder(); boolean up = true;
        for (char ch : entry.substring(1).replace('_', ' ').replace(';', ' ').toLowerCase().toCharArray()) { sb.append(up ? Character.toUpperCase(ch) : ch); up = ch == ' '; }
        return sb.toString();
    }

    private static String cut(String s, int n) { return s.length() > n ? s.substring(0, n - 1) + "." : s; }

    /** Look up what the player typed (an item name or an item id) in the app's list of real SkyBlock items. */
    private void find(String text) {
        final String q = text.trim();
        if (q.isEmpty()) { suggNote = "Type an item name or id first."; sugg.clear(); rebuild(); return; }
        Thread th = new Thread(() -> {
            List<String[]> found; String note = "";
            try { found = core.items(q, scope); if (found.isEmpty()) note = "No item found for \"" + cut(q, 24) + "\". Check the name or id."; }
            catch (Exception e) { found = new ArrayList<>(); note = "Cannot reach Reclaimed Flip. Is it running?"; }
            final List<String[]> res = found; final String n = note;
            net.minecraft.client.Minecraft.getInstance().execute(() -> { sugg.clear(); sugg.addAll(res); suggNote = n; rebuild(); });
        }, "reclaimedmarket-find");
        th.setDaemon(true); th.start();
    }

    /** The block list for one side. Draws a card at y and returns nothing; the scope is fixed by the tab it sits in. */
    private void initBlockSection(int top) {
        // Every blocked item gets its own full-width row, so its whole name can be read. Search results use two columns only when every name fits in one.
        final int x = px(), perRow = 1, chipW = PW - 16;
        final List<String> view = new ArrayList<>();
        for (String b : blocks) { String t = tag(b); if (t.isEmpty() || (t.startsWith("BZ") == scope.equals("bazaar"))) view.add(b); }
        final int shownN = Math.min(view.size(), 3), chipRows = Math.max(1, shownN);
        final int colW = (PW - 16 - 4) / 2;
        boolean fits = true;
        for (int i = 0; i < Math.min(sugg.size(), 6); i++) if (this.font.width(sugg.get(i)[1]) > colW - 10) fits = false;
        final boolean twoCol = fits;
        final int suggN = Math.min(sugg.size(), twoCol ? 4 : 3), suggRows = twoCol ? (suggN + 1) / 2 : suggN;
        final boolean more = sugg.size() > suggN;
        int below = suggN > 0 ? 6 + suggRows * 22 + (more ? 12 : 0) : (suggNote.isEmpty() ? 0 : 16);
        final int helpH = 0;                                  // the how-to text makes room while search results are showing
        int h = HEAD + 14 + chipRows * 22 + 6 + 24 + 24 + 24 + below + 4 + helpH + PAD;
        int y = card(top, scope.equals("bazaar") ? "Bazaar items you never want alerts for" : "Auction items you never want alerts for", h);
        text(x + 9, y + 2, view.isEmpty() ? "Nothing blocked yet. Type a word, then pick how to block it." : "Click a block to remove it from the list.", GRAY);
        int cy = y + 14;
        for (int i = 0; i < shownN; i++) {
            final String entry = view.get(i);
            addRenderableWidget(Button.builder(Component.literal(fit(tag(entry) + itemLabel(entry), chipW - 12 - this.font.width("  x")) + "  x"), btn -> { blocks.remove(entry); send("unblock " + entry); rebuild(); })
                    .bounds(x + 8, cy + i * 22, chipW, 18).build());
        }
        int iy = cy + chipRows * 22 + 6;
        if (view.size() > shownN) text(x + 9, iy - 10, "+" + (view.size() - shownN) + " more. Type /rm blocked to see all.", GRAY);
        box = new EditBox(this.font, x + 8, iy, PW - 16 - 62, 18, Component.literal("Item name or id"));
        box.setMaxLength(60); box.setValue(boxText); addRenderableWidget(box);
        addRenderableWidget(Button.builder(Component.literal("Find"), btn -> { boxText = box.getValue(); find(boxText); }).bounds(x + PW - 8 - 58, iy, 58, 18).build());
        text(x + 9, iy + 26, "Only if under", GRAY);
        priceBox = new EditBox(this.font, x + 90, iy + 22, PW - 8 - 90, 18, Component.literal("Only block below this price"));
        priceBox.setMaxLength(12); priceBox.setValue(priceText); priceBox.setResponder(v -> priceText = v); addRenderableWidget(priceBox);
        final int bw = (PW - 16 - 4) / 2;
        addRenderableWidget(Button.builder(Component.literal("Block this item"), btn -> addExact(box.getValue().trim())).bounds(x + 8, iy + 44, bw, 18).build());
        addRenderableWidget(Button.builder(Component.literal("Block this modifier"), btn -> addBlock(box.getValue().trim(), true)).bounds(x + 8 + bw + 4, iy + 44, bw, 18).build());
        int sy = iy + 70;
        if (suggN > 0) {
            int sw = twoCol ? colW : PW - 16;
            for (int i = 0; i < suggN; i++) {
                final String[] it = sugg.get(i);
                addRenderableWidget(Button.builder(Component.literal(fit(it[1], sw - 10)), btn -> {
                    addExactName(it[1]);
                }).bounds(x + 8 + (twoCol ? (i % 2) * (sw + 4) : 0), sy + 6 + (twoCol ? i / 2 : i) * 22, sw, 18).build());
            }
            if (more) text(x + 9, sy + 6 + suggRows * 22 + 2, "+" + (sugg.size() - suggN) + " more. Type more letters to narrow it.", GRAY);
        } else if (!suggNote.isEmpty()) text(x + 9, sy + 4, suggNote, GRAY);
    }

    /** Shortens a name only when it is wider than the box, keeping its ending (the level or number) so nothing can be mistaken for something else. */
    private String fit(String t, int px) {
        if (this.font.width(t) <= px) return t;
        String tail = t.length() > 8 ? t.substring(t.length() - 8) : t;
        for (int n = t.length() - 10; n > 2; n--) { String c = t.substring(0, n) + ".." + tail; if (this.font.width(c) <= px) return c; }
        return tail;
    }

    /** Shortens a long name in the middle so its ending (such as the level) stays visible. */
    private static String cutMid(String s, int n) { return s.length() <= n ? s : s.substring(0, n - 10) + ".." + s.substring(s.length() - 8); }

    /** Blocks exactly one item by its full name, with the scope and the price limit. */
    private void addExactName(String name) {
        String lim = priceText == null ? "" : priceText.trim();
        if (!lim.isEmpty() && AlertsCore.parseAmount(lim) <= 0) { suggNote = "Price must be like 500k or 5m, or leave it empty."; sugg.clear(); rebuild(); return; }
        String entry = scopePrefix() + "=" + name.trim() + (lim.isEmpty() ? "" : " under:" + lim.toLowerCase().replace(" ", ""));
        if (blocks.stream().anyMatch(b -> b.equalsIgnoreCase(entry))) { suggNote = "Already blocked."; sugg.clear(); rebuild(); return; }
        blocks.add(entry); send("block " + entry);
        sugg.clear(); suggNote = ""; boxText = ""; priceText = ""; rebuild();
    }

    /** "Block this item": looks the typed name up. An exact match is blocked at once; otherwise the matches are listed so you click the one you mean. */
    private void addExact(String word) {
        final String w = word == null ? "" : word.trim();
        if (w.length() < 2) { suggNote = "Type the item name first."; sugg.clear(); rebuild(); return; }
        Thread th = new Thread(() -> {
            List<String[]> found; String note = "";
            try { found = core.items(w, scope); } catch (Exception e) { found = new ArrayList<>(); note = "Cannot reach Reclaimed Flip. Is it running?"; }
            final List<String[]> res = found; final String n0 = note;
            net.minecraft.client.Minecraft.getInstance().execute(() -> {
                String[] hit = null; int same = 0;
                for (String[] it : res) if (AlertsCore.exactName(it[1]).equals(AlertsCore.exactName(w))) { hit = it; same++; }
                if (same == 1) { addExactName(hit[1]); return; }
                sugg.clear(); sugg.addAll(res);
                suggNote = !n0.isEmpty() ? n0 : res.isEmpty() ? "No item called \"" + cut(w, 24) + "\". Check the name." : "Click the exact item you mean:";
                rebuild();
            });
        }, "reclaimedmarket-block");
        th.setDaemon(true); th.start();
    }

    /** Adds a block from the typed word: by name (every item with the word in its name) or by modifier (gear that has it, such as a rune). */
    private void addBlock(String word, boolean modifier) {
        String w = word == null ? "" : word.trim();
        if (w.length() < 2) { suggNote = "Type at least two letters first."; sugg.clear(); rebuild(); return; }
        if (w.toLowerCase().contains("with:")) { suggNote = "Just type the word, without with:."; sugg.clear(); rebuild(); return; }
        String lim = priceText == null ? "" : priceText.trim();
        if (!lim.isEmpty() && AlertsCore.parseAmount(lim) <= 0) { suggNote = "Price must be like 500k or 5m, or leave it empty."; sugg.clear(); rebuild(); return; }
        String entry = scopePrefix() + (modifier ? "with:" : "") + w + (lim.isEmpty() ? "" : " under:" + lim.toLowerCase().replace(" ", ""));
        if (blocks.stream().anyMatch(b -> b.equalsIgnoreCase(entry))) { suggNote = "Already blocked."; sugg.clear(); rebuild(); return; }
        blocks.add(entry); send("block " + entry);
        sugg.clear(); suggNote = ""; boxText = ""; priceText = ""; rebuild();
    }

    // ---- Profit tab: the local Bazaar tracker and the on-screen panel. Nothing leaves your PC. ----
    private void initProfitTab() {
        final int x = px();
        int y = card(56, "Bazaar profit tracker", HEAD + 7 * ROW + 6 * 11 + 30 + PAD), i = 0;
        row(y, i++, "Tracker", "Reads Bazaar lines in your chat. Local only", onOff(hud.trackOn()), btn -> { hud.setTrack(!hud.trackOn()); btn.setMessage(Component.literal(onOff(hud.trackOn()))); });
        y += ROW; row(y, i++, "Panel on screen", "Shows session profit while you play", onOff(hud.hudOn()), btn -> { hud.setHud(!hud.hudOn()); btn.setMessage(Component.literal(onOff(hud.hudOn()))); });
        y += ROW; row(y, i++, "Panel corner", "Where the panel sits", BzHud.CORNERS[hud.corner()], btn -> { hud.nextCorner(); btn.setMessage(Component.literal(BzHud.CORNERS[hud.corner()])); });
        y += ROW; row(y, i++, "Panel parts", "Choose what the panel shows", "Edit", btn -> { pickingParts = true; rebuild(); });
        y += ROW; row(y, i++, "New session", "Starts the session numbers from zero", "Reset", btn -> { hud.newSession(); rebuild(); });
        y += ROW; row(y, i++, "Share session", "Off by default. Sends only profit, flips and time", onOff(hud.shareOn()), btn -> { hud.setShare(!hud.shareOn()); btn.setMessage(Component.literal(onOff(hud.shareOn()))); });
        y += ROW; text(x + 9, y + 6, "Mod key", GRAY);
        final EditBox keyBox = new EditBox(this.font, x + 70, y + 2, PW - 8 - 70 - 66, 18, Component.literal("Mod key from your account page"));
        keyBox.setMaxLength(80); keyBox.setHint(Component.literal(hud.hasKey() ? "saved, ends in " + hud.keyTail() : "paste your key")); addRenderableWidget(keyBox);
        addRenderableWidget(Button.builder(Component.literal("Save"), btn -> { String v = keyBox.getValue().trim(); if (!v.isEmpty()) { hud.setKey(v); rebuild(); } }).bounds(x + PW - 8 - 60, y + 2, 60, 18).build());
        y += ROW + 4;
        final long now = System.currentTimeMillis();
        final BzTracker t = hud.tracker;
        final String[][] lines = {
                {"Session profit", BzHud.coins(t.sessionProfit())}, {"Per hour", BzHud.coins(t.perHour(now))},
                {"Flips this session", String.valueOf(t.sessionFlips())}, {"All time", BzHud.coins(t.allTimeProfit())},
                {"Sales not matched", t.unmatched() + (t.unmatched() > 0 ? " (bought before tracking)" : "")}};
        for (int k = 0; k < lines.length; k++) {
            text(x + 9, y + k * 11, lines[k][0], GRAY);
            text(x + 120, y + k * 11, lines[k][1], lines[k][1].startsWith("-") ? 0xFFFF8A80 : WHITE);
        }
        y += lines.length * 11 + 6;
        text(x + 9, y, "Profit counts coins exactly as your chat shows them.", GRAY);
        addRenderableWidget(Button.builder(Component.literal("Erase history"), btn -> { hud.tracker.clearAll(System.currentTimeMillis()); rebuild(); }).bounds(x + PW - 8 - 96, y + 12, 96, 16).build());
    }

    // ---- Search tab: find any item and see what Reclaimed Flip knows about it. Display only. ----
    private final List<String[]> sres = new ArrayList<>();
    private final List<String> sdetail = new ArrayList<>();
    private String sname = "", snote = "";

    private void searchFor(String text) {
        final String q = text.trim();
        if (q.length() < 2) { snote = "Type at least two letters of an item name."; sres.clear(); sdetail.clear(); rebuild(); return; }
        Thread th = new Thread(() -> {
            List<String[]> found; String note = "";
            try { found = core.items(q); if (found.isEmpty()) note = "No item found for \"" + cut(q, 24) + "\"."; }
            catch (Exception e) { found = new ArrayList<>(); note = "Cannot reach Reclaimed Flip. Is it running?"; }
            final List<String[]> res = found; final String n = note;
            net.minecraft.client.Minecraft.getInstance().execute(() -> { sres.clear(); sres.addAll(res); sdetail.clear(); sname = ""; snote = n; rebuild(); });
        }, "reclaimedmarket-search");
        th.setDaemon(true); th.start();
    }

    private void showDetail(String name) {
        sname = name; sdetail.clear(); sdetail.add("Loading..."); rebuild();
        Thread th = new Thread(() -> {
            List<String> lines;
            try { lines = core.tip(name); if (lines.isEmpty()) lines = List.of("No price data for this item right now."); }
            catch (Exception e) { lines = List.of("Cannot reach Reclaimed Flip. Is it running?"); }
            final List<String> res = lines;
            net.minecraft.client.Minecraft.getInstance().execute(() -> { if (name.equals(sname)) { sdetail.clear(); sdetail.addAll(res); rebuild(); } });
        }, "reclaimedmarket-detail");
        th.setDaemon(true); th.start();
    }

    private void initSearchTab() {
        final int x = px();
        final int resN = Math.min(sres.size(), 6), resRows = (resN + 1) / 2, detN = Math.min(sdetail.size(), 7);
        int h = HEAD + 6 + 24 + (resN > 0 ? 6 + resRows * 22 : 14) + (detN > 0 ? 8 + detN * 11 + 12 : 0) + PAD;
        int y = card(56, "Search any item", h);
        text(x + 9, y + 2, "Prices come from Reclaimed Flip. Nothing is sent to Hypixel.", GRAY);
        int iy = y + 14;
        box = new EditBox(this.font, x + 8, iy, PW - 16 - 62, 18, Component.literal("Item name"));
        box.setMaxLength(60); box.setValue(boxText); addRenderableWidget(box);
        addRenderableWidget(Button.builder(Component.literal("Search"), btn -> { boxText = box.getValue(); searchFor(boxText); }).bounds(x + PW - 8 - 58, iy, 58, 18).build());
        int sy = iy + 24;
        if (resN > 0) {
            int sw = (PW - 16 - 4) / 2;
            for (int i = 0; i < resN; i++) {
                final String[] it = sres.get(i);
                Component label = Component.literal(cut(it[1], 26));
                if (it[1].equals(sname)) label = Component.literal(cut(it[1], 26)).withStyle(ChatFormatting.GOLD);
                addRenderableWidget(Button.builder(label, btn -> showDetail(it[1])).bounds(x + 8 + (i % 2) * (sw + 4), sy + (i / 2) * 22, sw, 18).build());
            }
            sy += resRows * 22 + 6;
        } else { text(x + 9, sy, snote.isEmpty() ? "Type an item name, like Hyperion, then press Search." : snote, GRAY); sy += 14; }
        if (detN > 0) {
            final int sepY = sy;
            deco.add(g -> g.fill(x + 9, sepY, x + PW - 9, sepY + 1, 0x337FD6DC));
            text(x + 9, sy + 4, cut(sname, 40), GOLD);
            for (int i = 0; i < detN; i++) text(x + 9, sy + 15 + i * 11, cut(sdetail.get(i), 62), WHITE);
        }
    }

    // ---- Stats tab: your own verified auction results, from Reclaimed Flip. Display only. ----
    private final List<String[]> statRows = new ArrayList<>();
    private String statNote = "";

    private static String myUuid() {
        try { net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance(); return mc.player == null ? "" : mc.player.getUUID().toString(); }
        catch (Throwable t) { return ""; }
    }

    private void loadStats() {
        final String u = myUuid();
        if (u.isEmpty()) { statNote = "Join a world first so I know who you are."; return; }
        statNote = "Loading...";
        Thread th = new Thread(() -> {
            List<String[]> rows; String note = "";
            try { rows = core.stats(u); if (rows.isEmpty()) note = "No stats yet."; else if (rows.get(0)[0].equals("error")) { note = rows.get(0)[1]; rows = new ArrayList<>(); } }
            catch (Exception e) { rows = new ArrayList<>(); note = "Cannot reach Reclaimed Flip. Is it running?"; }
            final List<String[]> res = rows; final String n = note;
            net.minecraft.client.Minecraft.getInstance().execute(() -> { statRows.clear(); statRows.addAll(res); statNote = n; rebuild(); });
        }, "reclaimedmarket-stats");
        th.setDaemon(true); th.start();
    }

    private void initStatsTab() {
        final int x = px(), n = Math.max(statRows.size(), 1);
        int y = card(56, "Your auction stats", HEAD + 14 + n * 14 + 22 + PAD);
        text(x + 9, y + 2, "Verified flips and sales seen since you linked.", GRAY);
        int ry = y + 16;
        if (statRows.isEmpty()) text(x + 9, ry, statNote.isEmpty() ? "Press Refresh." : statNote, GRAY);
        for (String[] r : statRows) {
            final int yy = ry; final String lab = r[0], val = r[1];
            text(x + 9, yy, lab, GRAY);
            int col = val.contains(" -") && lab.equals("Worst flip") || val.startsWith("-") ? 0xFFFF8A80 : (lab.equals("Net profit") || lab.equals("Best flip")) ? 0xFF8DE08D : WHITE;
            text(x + 120, yy, cut(val, 44), col);
            ry += 14;
        }
        addRenderableWidget(Button.builder(Component.literal("Refresh"), btn -> loadStats()).bounds(x + PW - 8 - 70, ry + 4, 70, 18).build());
    }

    private String purseNote = "";

    /** The purse is typed in, any amount. There are no preset choices. */
    private void initPurse(int cx) {
        final Dd d = picking;
        header(cx, d == PURSE ? "Your purse" : d.title);
        text(cx - 150, 40, d == PURSE ? "Type how many coins you can spend on one flip." : "Type your own amount. 0 means no minimum.", WHITE);
        text(cx - 150, 52, d == PURSE ? "k, m and b work: 20m, 1.5b. Alerts stay off until set." : "k, m and b work: 250k, 1.5m, 12500000.", GRAY);
        box = new EditBox(this.font, cx - 150, 70, 200, 18, Component.literal("Purse"));
        box.setMaxLength(14); box.setValue(boxText); addRenderableWidget(box);
        final Runnable ok = () -> {
            double v = d == PURSE ? AlertsCore.parsePurse(box.getValue()) : AlertsCore.parseAmount(box.getValue());
            if (v < 0) { boxText = box.getValue(); purseNote = d == PURSE ? "Type an amount above 0, for example 50m." : "Type a number, for example 250k or 0."; rebuild(); return; }
            s.put(d.key, num(v)); send(d.cmd + " " + num(v)); purseNote = ""; boxText = ""; picking = null; rebuild();
        };
        addRenderableWidget(Button.builder(Component.literal("Save"), btn -> ok.run()).bounds(cx + 56, 69, 94, 20).build());
        if (!purseNote.isEmpty()) text(cx - 150, 96, purseNote, 0xFFFF8A80);
        addRenderableWidget(Button.builder(Component.literal("Back"), btn -> { purseNote = ""; boxText = ""; picking = null; rebuild(); }).bounds(cx - 50, this.height - 26, 100, 20).build());
    }

    private void initPicker(int cx) {
        final Dd d = picking;
        if (typed(d)) { initPurse(cx); return; }
        header(cx, d.title);
        int perRow = 3, w = 100, y = 50, step = 24;
        for (int i = 0; i < d.vals.length; i++) {
            final int idx = i; int col = i % perRow, row = i / perRow;
            addRenderableWidget(Button.builder(Component.literal(d.labels[i]), btn -> {
                s.put(d.key, num(d.vals[idx])); send(d.cmd + " " + num(d.vals[idx])); picking = null; rebuild();
            }).bounds(cx - (perRow * w) / 2 + col * w, y + row * step, w - 4, 20).build());
        }
        addRenderableWidget(Button.builder(Component.literal("Back"), btn -> { picking = null; rebuild(); }).bounds(cx - 50, this.height - 26, 100, 20).build());
    }

    /** Live preview of the panel with a tick beside each part. Changes show at once. */
    private void initPartsScreen(int cx) {
        header(cx, "Choose what the panel shows");
        final int px0 = cx - 175, py0 = 40;
        deco.add(g -> hud.paint(g, px0, py0));
        int bx = cx + 20, y = 40;
        for (int i = 0; i < BzHud.PARTS.length; i++) {
            final String key = BzHud.PARTS[i], name = BzHud.PART_NAMES[i];
            addRenderableWidget(Button.builder(Component.literal((hud.part(key) ? "[x] " : "[  ] ") + name), btn -> { hud.setPart(key, !hud.part(key)); rebuild(); }).bounds(bx, y, 150, 18).build());
            y += 22;
        }
        addRenderableWidget(Button.builder(Component.literal("Opacity: " + hud.opacity() + "%"), btn -> { hud.nextOpacity(); rebuild(); }).bounds(bx, y + 4, 150, 18).build());
        addRenderableWidget(Button.builder(Component.literal("Reset"), btn -> { hud.resetParts(); rebuild(); }).bounds(bx, y + 26, 72, 18).build());
        addRenderableWidget(Button.builder(Component.literal("Back"), btn -> { pickingParts = false; rebuild(); }).bounds(cx - 50, this.height - 26, 100, 20).build());
    }

    private void initClickPicker(int cx) {
        header(cx, "What happens when you click an alert");
        String[][] opts = {
                {"fill", "Fill command (safest)", "Puts the command in your chat box. You press Enter."},
                {"open", "Open page", "Opens Reclaimed Flip in your browser."},
                {"run", "Run on click (warning)", "Sends the command to the server when you click."}};
        int y = 44;
        for (String[] o : opts) {
            final String mode = o[0];
            addRenderableWidget(Button.builder(Component.literal((core.click.equals(mode) ? "> " : "") + o[1]), btn -> {
                if (mode.equals("run")) {
                    pickingClick = false;
                    Msg.openScreen(net.minecraft.client.Minecraft.getInstance(), net.minecraft.client.Minecraft.getInstance().gui, new ConfirmRunScreen(core, s));
                    return;
                }
                core.setClick(mode, false); pickingClick = false; rebuild();
            }).bounds(cx - 130, y, 260, 20).build());
            text(cx - 128, y + 24, o[2], GRAY);
            y += 50;
        }
        addRenderableWidget(Button.builder(Component.literal("Back"), btn -> { pickingClick = false; rebuild(); }).bounds(cx - 50, this.height - 26, 100, 20).build());
    }
}
