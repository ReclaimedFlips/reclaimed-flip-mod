package com.reclaimedmarket.alerts;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * Shows text to the player. Minecraft renames these methods between versions, so this looks for the right one while the game runs
 * instead of naming it at build time. It only ever shows text to you. If nothing fits it writes a small report file so the right
 * name can be added.
 */
final class Msg {
    private Msg() { }

    private static Method find(Object target, String name, Object comp, boolean withBool) {
        if (target == null) return null;
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(name)) continue;
                Class<?>[] p = m.getParameterTypes();
                if (withBool ? !(p.length == 2 && p[0].isInstance(comp) && p[1] == boolean.class) : !(p.length == 1 && p[0].isInstance(comp))) continue;
                try { m.setAccessible(true); } catch (Exception ignored) { }
                return m;
            }
        }
        return null;
    }

    private static Method findNoArg(Object target, String name) {
        if (target == null) return null;
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass())
            for (Method m : c.getDeclaredMethods())
                if (m.getName().equals(name) && m.getParameterCount() == 0) { try { m.setAccessible(true); } catch (Exception ignored) { } return m; }
        return null;
    }

    /** Any method on the target whose name matches and that takes the text (and optionally a boolean). */
    private static Method scan(Object target, String regex, Object comp, boolean withBool) {
        if (target == null) return null;
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().matches(regex)) continue;
                Class<?>[] p = m.getParameterTypes();
                if (withBool ? !(p.length == 2 && p[0].isInstance(comp) && p[1] == boolean.class) : !(p.length == 1 && p[0].isInstance(comp))) continue;
                try { m.setAccessible(true); } catch (Exception ignored) { }
                return m;
            }
        }
        return null;
    }

    // ---- click and hover on chat lines ----
    /** A style with a hover tooltip and (when mode is fill, run or open) a click action. Minecraft changed its click and hover classes in 1.21.5, so both shapes are tried. On any failure the style comes back unchanged and the line is still shown. */
    static Object styled(Object style, String mode, String value, Object hoverComp) {
        Object st = style;
        try {
            Object hover = null;
            try { Class<?> k = Class.forName("net.minecraft.network.chat.HoverEvent$ShowText"); hover = k.getConstructors()[0].newInstance(hoverComp); } catch (Throwable ignored) { }
            if (hover == null) {
                try {
                    Class<?> he = Class.forName("net.minecraft.network.chat.HoverEvent"), act = Class.forName("net.minecraft.network.chat.HoverEvent$Action");
                    Object showText = act.getField("SHOW_TEXT").get(null);
                    for (java.lang.reflect.Constructor<?> c : he.getConstructors()) if (c.getParameterCount() == 2) { hover = c.newInstance(showText, hoverComp); break; }
                } catch (Throwable ignored) { }
            }
            if (hover != null) { Method m = styleMethod(st, "withHoverEvent", hover); if (m != null) st = m.invoke(st, hover); }
        } catch (Throwable ignored) { }
        try {
            Object click = null;
            if (value != null && !value.isEmpty()) {
                String nameNew = mode.equals("run") ? "RunCommand" : mode.equals("open") ? "OpenUrl" : "SuggestCommand";
                try {
                    Class<?> k = Class.forName("net.minecraft.network.chat.ClickEvent$" + nameNew);
                    if (mode.equals("open")) click = k.getConstructors()[0].newInstance(java.net.URI.create(value));
                    else click = k.getConstructors()[0].newInstance(mode.equals("run") && value.startsWith("/") ? value.substring(1) : value);
                } catch (Throwable ignored) { }
                if (click == null) {
                    try {
                        Class<?> ce = Class.forName("net.minecraft.network.chat.ClickEvent"), act = Class.forName("net.minecraft.network.chat.ClickEvent$Action");
                        Object a = act.getField(mode.equals("run") ? "RUN_COMMAND" : mode.equals("open") ? "OPEN_URL" : "SUGGEST_COMMAND").get(null);
                        for (java.lang.reflect.Constructor<?> c : ce.getConstructors()) if (c.getParameterCount() == 2) { click = c.newInstance(a, value); break; }
                    } catch (Throwable ignored) { }
                }
            }
            if (click != null) { Method m = styleMethod(st, "withClickEvent", click); if (m != null) st = m.invoke(st, click); }
        } catch (Throwable ignored) { }
        return st;
    }

    private static Method styleMethod(Object style, String name, Object arg) {
        for (Method m : style.getClass().getMethods())
            if (m.getName().equals(name) && m.getParameterCount() == 1 && m.getParameterTypes()[0].isInstance(arg)) return m;
        return null;
    }

    /** A line in the chat window. */
    static boolean chat(Object player, Object gui, Object comp) {
        try {
            Method m = find(player, "sendSystemMessage", comp, false);
            if (m != null) { m.invoke(player, comp); return true; }
            m = find(player, "displayClientMessage", comp, true);
            if (m != null) { m.invoke(player, comp, false); return true; }
            Method g = findNoArg(gui, "getChat");
            if (g != null) {
                Object chat = g.invoke(gui);
                for (String n : new String[]{"addMessage", "addClientSystemMessage"}) {
                    Method a = find(chat, n, comp, false);
                    if (a != null) { a.invoke(chat, comp); return true; }
                }
            }
            m = scan(player, "(?i).*(system|clientmessage).*", comp, false);
            if (m != null && !m.getName().toLowerCase().contains("overlay")) { m.invoke(player, comp); return true; }
        } catch (Exception ignored) { }
        return false;
    }

    /** A line above the hotbar. */
    static boolean overlay(Object player, Object gui, Object comp) {
        try {
            Method m = find(player, "sendOverlayMessage", comp, false);
            if (m != null) { m.invoke(player, comp); return true; }
            m = find(gui, "setOverlayMessage", comp, true);
            if (m != null) { m.invoke(gui, comp, false); return true; }
            m = find(player, "displayClientMessage", comp, true);
            if (m != null) { m.invoke(player, comp, true); return true; }
            m = scan(player, "(?i).*(overlay|actionbar).*", comp, false);
            if (m != null) { m.invoke(player, comp); return true; }
            m = scan(gui, "(?i).*(overlay|actionbar).*", comp, true);
            if (m != null) { m.invoke(gui, comp, false); return true; }
            m = scan(gui, "(?i).*(overlay|actionbar).*", comp, false);
            if (m != null) { m.invoke(gui, comp); return true; }
        } catch (Exception ignored) { }
        return false;
    }

    /** Lists the message-related methods that exist, so the right name can be found if the lookup above fails. */
    static void report(Path file, Object player, Object gui) {
        List<String> out = new ArrayList<>();
        out.add("Reclaimed Flip Alerts could not find a way to show text. Send this file to the developer.");
        for (Object t : new Object[]{player, gui}) {
            if (t == null) { out.add("(missing object)"); continue; }
            out.add("== " + t.getClass().getName());
            TreeSet<String> names = new TreeSet<>();
            for (Class<?> c = t.getClass(); c != null; c = c.getSuperclass())
                for (Method m : c.getDeclaredMethods())
                    if (m.getName().matches("(?i).*(message|overlay|chat|system|actionbar|hud|title).*")) {
                        StringBuilder sb = new StringBuilder(c.getSimpleName() + "." + m.getName() + "(");
                        Class<?>[] p = m.getParameterTypes();
                        for (int i = 0; i < p.length; i++) sb.append(i > 0 ? ", " : "").append(p[i].getSimpleName());
                        names.add(sb.append(")").toString());
                    }
            out.addAll(names);
        }
        try { Files.write(file, out, StandardCharsets.UTF_8); } catch (IOException ignored) { }
    }

    /** Opens a screen. Minecraft has renamed this before, so look for a method that takes a screen. */
    static boolean openScreen(Object mc, Object gui, Object screen) {
        try {
            for (Object t : new Object[]{mc, gui}) {
                if (t == null) continue;
                Method m = find(t, "setScreen", screen, false);
                if (m != null) { m.invoke(t, screen); return true; }
            }
            for (Object t : new Object[]{mc, gui}) {
                if (t == null) continue;
                for (Class<?> c = t.getClass(); c != null; c = c.getSuperclass()) {
                    for (Method m : c.getDeclaredMethods()) {
                        String n = m.getName().toLowerCase();
                        if (!n.contains("screen") || n.startsWith("get") || n.startsWith("is") || n.contains("remove") || n.contains("close") || n.contains("clear")) continue;
                        Class<?>[] p = m.getParameterTypes();
                        if (p.length != 1 || !p[0].isInstance(screen)) continue;
                        try { m.setAccessible(true); m.invoke(t, screen); return true; } catch (Exception ignored) { }
                    }
                }
            }
        } catch (Exception ignored) { }
        return false;
    }

    /** Lists screen-related methods so the right name can be found if opening a screen fails. */
    static void reportScreens(Path file, Object mc, Object gui) {
        List<String> out = new ArrayList<>();
        out.add("Reclaimed Flip Alerts could not open its menu. Send this file to the developer.");
        for (Object t : new Object[]{mc, gui}) {
            if (t == null) { out.add("(missing object)"); continue; }
            out.add("== " + t.getClass().getName());
            TreeSet<String> names = new TreeSet<>();
            for (Class<?> c = t.getClass(); c != null; c = c.getSuperclass())
                for (Method m : c.getDeclaredMethods())
                    if (m.getName().matches("(?i).*(screen|menu|open|gui).*")) {
                        StringBuilder sb = new StringBuilder(c.getSimpleName() + "." + m.getName() + "(");
                        Class<?>[] p = m.getParameterTypes();
                        for (int i = 0; i < p.length; i++) sb.append(i > 0 ? ", " : "").append(p[i].getSimpleName());
                        names.add(sb.append(")").toString());
                    }
            out.addAll(names);
        }
        try { Files.write(file, out, StandardCharsets.UTF_8); } catch (IOException ignored) { }
    }

    /** Writes the drawing-related methods of a screen and of the drawing helper its render method takes, so a proper background can be added. */
    static void probeScreen(Path file, Object screen) {
        List<String> out = new ArrayList<>();
        out.add("Reclaimed Flip Alerts menu probe. Send this file to the developer.");
        TreeSet<String> classes = new TreeSet<>(); java.util.Set<Class<?>> helpers = new java.util.LinkedHashSet<>();
        try {
            for (Class<?> c = screen.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Method m : c.getDeclaredMethods()) {
                    if (!m.getName().matches("(?i).*(render|extract|background|draw|blur).*")) continue;
                    StringBuilder sb = new StringBuilder(c.getSimpleName() + "." + m.getName() + "(");
                    Class<?>[] p = m.getParameterTypes();
                    for (int i = 0; i < p.length; i++) {
                        sb.append(i > 0 ? ", " : "").append(p[i].getName());
                        if (p[i].getSimpleName().matches("(?i).*(graphics|extractor|guirender).*")) helpers.add(p[i]);
                    }
                    classes.add(sb.append(")").toString());
                }
            }
            out.addAll(classes);
            for (Class<?> h : helpers) {
                out.add("== helper " + h.getName());
                TreeSet<String> ms = new TreeSet<>();
                for (Method m : h.getDeclaredMethods()) {
                    if (!m.getName().matches("(?i).*(fill|text|string|rect|blit|gradient|outline|border|sprite|pose).*")) continue;
                    StringBuilder sb = new StringBuilder(m.getName() + "(");
                    Class<?>[] p = m.getParameterTypes();
                    for (int i = 0; i < p.length; i++) sb.append(i > 0 ? ", " : "").append(p[i].getSimpleName());
                    ms.add(sb.append(")").toString());
                }
                out.addAll(ms);
            }
        } catch (Throwable t) { out.add("probe failed: " + t); }
        try { Files.write(file, out, StandardCharsets.UTF_8); } catch (IOException ignored) { }
    }
}
