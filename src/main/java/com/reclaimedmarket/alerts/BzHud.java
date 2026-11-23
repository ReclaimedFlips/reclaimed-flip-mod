package com.reclaimedmarket.alerts;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * The on-screen panel (HUD) and the Bazaar tracker switches. DISPLAY ONLY: it draws text and nothing else.
 * Settings are saved on your PC only. The panel is registered by name lookup, so if this game version
 * moved the HUD API the mod still starts and only the panel is missing (a line is printed in the log).
 */
public final class BzHud {
    private static final int GOLD = 0xFFF0B93A, TEAL = 0xFF7FD6DC, WHITE = 0xFFEDEDED, GRAY = 0xFF9FB4B8, GREEN = 0xFF8DE08D, RED = 0xFFFF8A80;
    public static final String[] CORNERS = {"Top left", "Top right", "Bottom left", "Bottom right"};

    private final Properties p = new Properties();
    private final Path file;
    public final BzTracker tracker;
    /** The one panel, so a screen opened without it (the confirm screen) can still reach it. */
    public static BzHud INSTANCE;

    public BzHud(Path settingsFile, BzTracker tracker) {
        this.file = settingsFile; this.tracker = tracker; INSTANCE = this;
        if (Files.exists(file)) try (InputStream in = Files.newInputStream(file)) { p.load(in); } catch (IOException ignored) { }
    }

    private void save() { try (OutputStream o = Files.newOutputStream(file)) { p.store(o, "Reclaimed Flip HUD. Local only."); } catch (IOException ignored) { } }

    public boolean hudOn() { return !"0".equals(p.getProperty("hud", "1")); }
    public boolean trackOn() { return !"0".equals(p.getProperty("track", "1")); }
    public int corner() { try { return Math.floorMod(Integer.parseInt(p.getProperty("corner", "1")), 4); } catch (NumberFormatException e) { return 1; } }
    public void setHud(boolean v) { p.setProperty("hud", v ? "1" : "0"); save(); }
    public void setTrack(boolean v) { p.setProperty("track", v ? "1" : "0"); save(); }
    public boolean part(String k) { return !"0".equals(p.getProperty("p_" + k, "1")); }
    public void setPart(String k, boolean v) { p.setProperty("p_" + k, v ? "1" : "0"); save(); }
    public static final int[] OPACITY = {50, 65, 80, 95};
    public int opacity() { try { return Integer.parseInt(p.getProperty("opacity", "80")); } catch (NumberFormatException e) { return 80; } }
    public void nextOpacity() { int o = opacity(), n = OPACITY[0]; for (int i = 0; i < OPACITY.length; i++) if (OPACITY[i] > o) { n = OPACITY[i]; break; } p.setProperty("opacity", String.valueOf(n)); save(); }
    public void resetParts() { for (String k : PARTS) p.remove("p_" + k); p.remove("opacity"); save(); }
    /** Keys and names of the parts of the panel that can be switched off. */
    public static final String[] PARTS = {"time", "bars", "hour", "flips", "last"};
    public static final String[] PART_NAMES = {"Session time", "Profit bars", "Total Profit", "Flips", "Last flip"};
    // ---- Optional sharing of the session to the website's Top flippers page. Off by default. Sends only profit, flips and time. ----
    public boolean shareOn() { return "1".equals(p.getProperty("share", "0")); }
    public void setShare(boolean v) { p.setProperty("share", v ? "1" : "0"); save(); }
    public boolean hasKey() { return !p.getProperty("key", "").isEmpty(); }
    public String keyTail() { String k = p.getProperty("key", ""); return k.length() > 4 ? k.substring(k.length() - 4) : ""; }
    public void setKey(String k) { p.setProperty("key", k == null ? "" : k.trim()); save(); }
    private String sid = newSid();
    private static String newSid() { return Long.toString(Math.abs(new java.security.SecureRandom().nextLong()), 36) + Long.toString(System.currentTimeMillis() % 100000, 36); }
    /** Starts a new session: the numbers start from zero and the website sees it as a separate session. */
    public void newSession() { tracker.newSession(System.currentTimeMillis()); sid = newSid(); }

    /** Every two minutes, while sharing is on, a key is saved and there is at least one flip this session. A failure is ignored and tried again later. */
    public void startSharing(AlertsCore core) {
        Thread t = new Thread(() -> {
            while (true) {
                try { Thread.sleep(120_000L); } catch (InterruptedException e) { return; }
                try {
                    if (!shareOn() || !hasKey() || tracker.sessionFlips() <= 0) continue;
                    long secs = Math.min(86400L, Math.max(0L, (System.currentTimeMillis() - tracker.sessionStart()) / 1000L));
                    String json = "{\"key\":\"" + p.getProperty("key", "").replaceAll("[^A-Za-z0-9_-]", "") + "\",\"sid\":\"" + sid + "\",\"profit\":" + Math.round(tracker.sessionProfit()) + ",\"flips\":" + tracker.sessionFlips() + ",\"secs\":" + secs + "}";
                    core.postJson("/api/bz/report", json);
                } catch (Throwable ignored) { }
            }
        }, "reclaimed-share");
        t.setDaemon(true); t.start();
    }

    public void nextCorner() { p.setProperty("corner", String.valueOf((corner() + 1) % 4)); save(); }

    /** Called for every chat line the game shows. Reads Bazaar lines only. */
    public void onChat(String line) { if (trackOn()) tracker.accept(BzTracker.parse(line, System.currentTimeMillis())); }

    public static String coins(double v) {
        String sign = v < 0 ? "-" : "";
        return sign + AlertsCore.shortNum(Math.abs(v));
    }

    private static String clock(long ms) { long m = Math.max(ms, 0) / 60000; return (m / 60) + ":" + (m % 60 < 10 ? "0" : "") + (m % 60); }

    /** The panel (style B). With g == null it only measures and returns {width, height}; otherwise it draws at x, y. Parts can be switched off. */
    public int[] paint(GuiGraphicsExtractor g, int x, int y) {
        Minecraft mc = Minecraft.getInstance();
        long now = System.currentTimeMillis();
        double sp = tracker.sessionProfit();
        BzTracker.Flip last = tracker.last();
        String title = "CURRENT SESSION", time = clock(now - tracker.sessionStart());
        String big = (sp > 0 ? "+" : "") + coins(sp);
        java.util.List<String[]> rows = new java.util.ArrayList<>();
        if (part("hour")) rows.add(new String[]{"Total Profit", (sp > 0 ? "+" : "") + coins(sp)});
        if (part("flips")) rows.add(new String[]{"Flips", String.valueOf(tracker.sessionFlips())});
        if (part("last")) rows.add(new String[]{"Last Flip", last == null ? "None yet" : (last.profit() >= 0 ? "+" : "") + coins(last.profit()) + " " + last.item});
        int labW = 0, valW = 0;
        for (String[] r : rows) { labW = Math.max(labW, mc.font.width(r[0])); valW = Math.max(valW, mc.font.width(r[1])); }
        int head = mc.font.width(title) + (part("time") ? 10 + mc.font.width(time) : 0);
        int w = Math.min(Math.max(Math.max(head, labW + 12 + valW), 112), 190), pad = 7, barH = 16;
        boolean bars = part("bars");
        int bw = w + pad * 2, bh = 5 + 12 + 12 + (bars ? barH + 8 : 0) + (rows.isEmpty() ? 0 : 4 + rows.size() * 11) + 5;
        if (g == null) return new int[]{bw, bh};
        int alpha = Math.max(0, Math.min(255, opacity() * 255 / 100));
        g.fill(x, y, x + bw, y + bh, (alpha << 24) | 0x061E22);
        g.fill(x, y, x + 2, y + bh, TEAL);
        int tx = x + pad + 2, ty = y + 5;
        g.text(mc.font, title, tx, ty, GOLD);
        if (part("time")) g.text(mc.font, time, x + bw - pad - mc.font.width(time), ty, GRAY);
        ty += 12;
        g.text(mc.font, big, tx, ty, sp < 0 ? RED : sp > 0 ? GREEN : WHITE);
        ty += 12;
        if (bars) {
            double[] pr = tracker.recentProfits(8); double mx = 1; for (double v : pr) mx = Math.max(mx, Math.abs(v));
            int base = ty + barH;
            for (int i = 0; i < pr.length; i++) {
                int bh2 = Math.max(2, (int) Math.round(Math.abs(pr[i]) / mx * barH)), bx = tx + i * 9;
                g.fill(bx, base - bh2, bx + 7, base, pr[i] < 0 ? RED : GREEN);
            }
            if (pr.length == 0) g.text(mc.font, "Waiting for a flip", tx, ty + 4, GRAY);
            ty = base + 8;
        }
        if (!rows.isEmpty()) {
            g.fill(tx, ty - 3, x + bw - pad, ty - 2, 0x407FD6DC);
            for (String[] r : rows) {
                g.text(mc.font, r[0], tx, ty, GRAY);
                String v = r[1]; int avail = x + bw - pad - tx - labW - 8;
                while (v.length() > 4 && mc.font.width(v) > avail) v = v.substring(0, v.length() - 2) + ".";
                g.text(mc.font, v, x + bw - pad - mc.font.width(v), ty, r[0].equals("Last Flip") && last != null && last.profit() < 0 ? RED : WHITE);
                ty += 11;
            }
        }
        return new int[]{bw, bh};
    }

    private void draw(GuiGraphicsExtractor g) {
        if (!hudOn()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        int[] sz = paint(null, 0, 0);
        int sw = mc.getWindow().getGuiScaledWidth(), sh = mc.getWindow().getGuiScaledHeight();
        int c = corner(), x = (c == 1 || c == 3) ? sw - sz[0] - 6 : 6, y = (c == 2 || c == 3) ? sh - sz[1] - 24 : 6;
        paint(g, x, y);
    }

    /** Registers the panel with Fabric's HUD API by name. Returns false if this version does not have it. */
    public boolean register() {
        try {
            Class<?> reg = Class.forName("net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry");
            Class<?> el = Class.forName("net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement");
            Class<?> van = Class.forName("net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements");
            Class<?> idc = idClass();
            Object id = idc.getMethod("fromNamespaceAndPath", String.class, String.class).invoke(null, "reclaimedmarketalerts", "bazaar_hud");
            Object after = van.getField("CHAT").get(null);
            Object element = Proxy.newProxyInstance(el.getClassLoader(), new Class<?>[]{el}, (proxy, m, args) -> {
                if (m.getDeclaringClass() == Object.class) return m.getName().equals("toString") ? "ReclaimedHud" : null;
                if (args != null && args.length > 0 && args[0] instanceof GuiGraphicsExtractor) { try { draw((GuiGraphicsExtractor) args[0]); } catch (Throwable ignored) { } }
                return null;
            });
            for (Method m : reg.getMethods()) if (m.getName().equals("attachElementAfter") && m.getParameterCount() == 3) { m.invoke(null, after, id, element); return true; }
        } catch (Throwable t) { System.out.println("[Reclaimed Flip] HUD not available on this version: " + t); }
        return false;
    }

    private static Class<?> idClass() throws ClassNotFoundException {
        try { return Class.forName("net.minecraft.resources.Identifier"); } catch (ClassNotFoundException e) { return Class.forName("net.minecraft.resources.ResourceLocation"); }
    }
}
