package com.reclaimedmarket.alerts;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

import java.util.List;

/**
 * Reclaimed Flip Alerts. DISPLAY ONLY.
 * It shows flips that Reclaimed Flip (running on your own PC) has already found, in chat and above the hotbar.
 * It never clicks, never buys and never acts by itself. Clicking an alert (your click) fills a command into your chat box,
 * opens the app page, or, only if you turned that on behind a warning screen, sends the command. Typing /rm ... is handled here
 * and is NOT sent to the server.
 */
public class ReclaimedAlertsClient implements ClientModInitializer {
    private AlertsCore core;
    private BzHud hud;

    @Override
    public void onInitializeClient() {
        Minecraft mc = Minecraft.getInstance();
        java.nio.file.Path debug = FabricLoader.getInstance().getConfigDir().resolve("reclaimedmarket-alerts-debug.txt");
        boolean[] reported = {false};
        AlertsCore.Output out = new AlertsCore.Output() {
            public boolean ready() { return mc.player != null; }
            private void chat(Component c) {
                mc.execute(() -> { if (mc.player == null) return; if (!Msg.chat(mc.player, mc.gui, c) && !reported[0]) { reported[0] = true; Msg.report(debug, mc.player, mc.gui); } });
            }
            public void say(String text) { chat(prefix().append(Component.literal(text).withStyle(ChatFormatting.WHITE))); }
            public void alert(String title, String detail, String cmd, String hover) {
                final String mode = core.click;
                final String value = mode.equals("open") ? core.base : cmd;
                final Component tip = Component.literal(hover == null ? "" : hover);
                chat(prefix().append(Component.literal(title).withStyle(ChatFormatting.GREEN)
                        .withStyle(st -> (Style) Msg.styled(st, mode, value, tip))));
                chat(Component.literal("    " + detail).withStyle(ChatFormatting.WHITE).withStyle(ChatFormatting.BOLD));
            }
            public void bar(String text) {
                mc.execute(() -> { if (mc.player != null) Msg.overlay(mc.player, mc.gui, Component.literal(text).withStyle(ChatFormatting.GOLD)); });
            }
        };
        core = new AlertsCore(out, FabricLoader.getInstance().getConfigDir().resolve("reclaimedmarket-alerts.properties"));

        // /rm ... is handled by this mod and is not sent to the server.
        ClientSendMessageEvents.ALLOW_COMMAND.register(command -> {
            String c = command.trim();
            if (!c.equals("rm") && !c.startsWith("rm ")) return true;
            String args = c.length() > 2 ? c.substring(3) : "";
            if (args.isBlank() || args.trim().equalsIgnoreCase("menu")) {
                // Open the settings menu a moment later, so the chat window does not close it again.
                Thread m = new Thread(() -> {
                    try {
                        java.util.Map<String, String> st = core.settings();
                        Thread.sleep(150);
                        mc.execute(() -> {
                            SettingsScreen scr = new SettingsScreen(core, st, hud);
                            if (Msg.openScreen(mc, mc.gui, scr)) {
                                Msg.probeScreen(FabricLoader.getInstance().getConfigDir().resolve("reclaimedmarket-alerts-ui-probe.txt"), scr);
                            } else {
                                out.say("The menu could not open on this version. A report was saved in your config folder.");
                                Msg.reportScreens(FabricLoader.getInstance().getConfigDir().resolve("reclaimedmarket-alerts-menu-debug.txt"), mc, mc.gui);
                            }
                        });
                    } catch (Exception e) { out.say("Could not reach Reclaimed Flip. Check your internet or the url in the config."); }
                }, "reclaimedmarket-menu-open");
                m.setDaemon(true); m.start();
                return false;
            }
            if (args.trim().equalsIgnoreCase("stats")) {
                final String me = mc.player == null ? "" : mc.player.getUUID().toString();
                Thread st = new Thread(() -> {
                    try {
                        List<String[]> rows = core.stats(me);
                        if (rows.isEmpty()) out.say("No stats yet.");
                        for (String[] r : rows) out.say(r[0].equals("error") ? r[1] : r[0] + ": " + r[1]);
                    } catch (Exception e) { out.say("Could not reach Reclaimed Flip. Check your internet or the url in the config."); }
                }, "reclaimedmarket-stats");
                st.setDaemon(true); st.start();
                return false;
            }
            Thread t = new Thread(() -> { List<String> lines = core.command(args); for (String l : lines) out.say(l); }, "reclaimedmarket-cmd");
            t.setDaemon(true); t.start();
            return false;
        });
        // Bazaar profit tracker and HUD: local only, reads the Bazaar lines already shown in your chat.
        java.nio.file.Path cfg = FabricLoader.getInstance().getConfigDir();
        hud = new BzHud(cfg.resolve("reclaimedmarket-hud.properties"), new BzTracker(cfg.resolve("reclaimedmarket-bazaar-log.tsv"), System.currentTimeMillis()));
        try {
            net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents.GAME.register((message, overlay) -> { if (!overlay) hud.onChat(message.getString()); });
        } catch (Throwable t) { System.out.println("[Reclaimed Flip] Bazaar tracker could not read chat on this version: " + t); }
        hud.register();
        hud.startSharing(core);
        core.start();
        if (!ItemTips.register(core)) System.out.println("[Reclaimed Flip] Item tooltips are not available on this version. Alerts still work.");
    }

    private static net.minecraft.network.chat.MutableComponent prefix() {
        return Component.literal("[RM] ").withStyle(ChatFormatting.AQUA);
    }
}
