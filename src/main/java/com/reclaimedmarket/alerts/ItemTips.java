package com.reclaimedmarket.alerts;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Adds a few lines under an item's tooltip when you hover it: what a buy order costs, what instant buy costs, and what the craft costs.
 * DISPLAY ONLY. The numbers come from Reclaimed Flip (reclaimedflip.com by default). Nothing is sent to Hypixel and nothing is clicked.
 * Fabric's tooltip event is looked up while the game runs, because its exact shape changes between versions. If it is missing,
 * the mod works as before and only this feature is off.
 */
final class ItemTips {
    private ItemTips() { }

    private static final class Entry { volatile long t; volatile List<String> lines = List.of(); volatile boolean busy; }
    private static final Map<String, Entry> CACHE = new ConcurrentHashMap<>();
    private static final long FRESH_MS = 20_000;

    /** True if the tooltip event was found and hooked. */
    static boolean register(AlertsCore core) {
        try {
            Class<?> cb = Class.forName("net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback");
            Object event = cb.getField("EVENT").get(null);
            Object listener = Proxy.newProxyInstance(cb.getClassLoader(), new Class<?>[] { cb }, (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class) {
                    switch (method.getName()) { case "hashCode": return 0; case "equals": return proxy == args[0]; default: return "ReclaimedFlipTips"; }
                }
                try { add(core, args); } catch (Throwable ignored) { }
                return null;
            });
            Class.forName("net.fabricmc.fabric.api.event.Event").getMethod("register", Object.class).invoke(event, listener);
            return true;
        } catch (Throwable t) { return false; }
    }

    @SuppressWarnings("unchecked")
    private static void add(AlertsCore core, Object[] args) {
        if (!core.tips || args == null || !onHypixel()) return;
        List<Object> lines = null;
        for (Object a : args) if (a instanceof List) lines = (List<Object>) a;
        if (lines == null || lines.isEmpty() || !(lines.get(0) instanceof Component)) return;
        String gearKey = gearData(args);
        if (gearKey != null) addGear(core, gearKey, lines);
        String name = ((Component) lines.get(0)).getString();
        if (name == null || name.isBlank() || name.length() > 80) return;
        Entry e = CACHE.computeIfAbsent(name, k -> new Entry());
        long now = System.currentTimeMillis();
        if (!e.busy && now - e.t > FRESH_MS) {
            e.busy = true;
            Thread t = new Thread(() -> {
                try { e.lines = core.tip(name); } catch (Exception ex) { e.lines = List.of(); }
                e.t = System.currentTimeMillis(); e.busy = false;
            }, "reclaimedmarket-tip");
            t.setDaemon(true); t.start();
        }
        if (e.lines.isEmpty()) return;
        lines.add(Component.literal("Reclaimed Flip").withStyle(ChatFormatting.GOLD));
        for (String l : e.lines) lines.add(Component.literal(l).withStyle(ChatFormatting.GRAY));
        if (CACHE.size() > 2000) CACHE.clear();
    }

    private static final Map<String, Entry> GEAR = new ConcurrentHashMap<>();
    private static final java.util.regex.Pattern NOISE = java.util.regex.Pattern.compile("(uuid|timestamp):\"[^\"]*\",?");

    /** The item's SkyBlock data in Minecraft text form, or null. Read through reflection so a renamed method only switches this off. */
    private static String gearData(Object[] args) {
        try {
            Object stack = null;
            for (Object a : args) if (a != null && a.getClass().getName().endsWith("ItemStack")) stack = a;
            if (stack == null) return null;
            Class<?> dc = Class.forName("net.minecraft.core.component.DataComponents");
            Object type = dc.getField("CUSTOM_DATA").get(null);
            Object cd = null;
            for (java.lang.reflect.Method m : stack.getClass().getMethods())
                if (m.getName().equals("get") && m.getParameterCount() == 1 && m.getParameterTypes()[0].isInstance(type)) { cd = m.invoke(stack, type); break; }
            if (cd == null) return null;
            Object tag = null;
            for (String n : new String[] { "copyTag", "getUnsafe" }) { try { tag = cd.getClass().getMethod(n).invoke(cd); break; } catch (Throwable ignored) { } }
            String s = String.valueOf(tag != null ? tag : cd);
            return s.contains("id:") ? s : null;
        } catch (Throwable t) { return null; }
    }

    private static void addGear(AlertsCore core, String raw, List<Object> lines) {
        String key = NOISE.matcher(raw).replaceAll("");
        Entry e = GEAR.computeIfAbsent(key, k -> new Entry());
        long now = System.currentTimeMillis();
        if (!e.busy && now - e.t > FRESH_MS) {
            e.busy = true;
            Thread t = new Thread(() -> {
                try { e.lines = core.gear(raw); } catch (Exception ex) { e.lines = List.of(); }
                e.t = System.currentTimeMillis(); e.busy = false;
            }, "reclaimedmarket-gear");
            t.setDaemon(true); t.start();
        }
        if (e.lines.isEmpty()) return;
        lines.add(Component.literal("Reclaimed Flip: gear").withStyle(ChatFormatting.GOLD));
        for (String l : e.lines) lines.add(Component.literal(l).withStyle(ChatFormatting.GRAY));
        if (GEAR.size() > 2000) GEAR.clear();
    }

    /** Only on Hypixel. If the server address cannot be read, the lines are shown anyway. */
    private static boolean onHypixel() {
        try {
            Object mc = Class.forName("net.minecraft.client.Minecraft").getMethod("getInstance").invoke(null);
            Object sd = mc.getClass().getMethod("getCurrentServer").invoke(mc);
            if (sd == null) return false;
            Object ip = sd.getClass().getField("ip").get(sd);
            return ip != null && ip.toString().toLowerCase().contains("hypixel");
        } catch (Throwable t) { return true; }
    }
}
