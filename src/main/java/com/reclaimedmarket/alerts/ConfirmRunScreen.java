package com.reclaimedmarket.alerts;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Map;

/**
 * The warning shown before "Run on click" can be turned on. Nothing changes unless the player presses the confirm button.
 */
public class ConfirmRunScreen extends Screen {
    private static final String[] LINES = {
            "Run on click sends a command to the server",
            "when you click an alert.",
            "",
            "This mod is not on Hypixel's allowed mods list.",
            "Hypixel treats unlisted mods as disallowed by default.",
            "",
            "We can't promise this is safe for your account.",
            "If Hypixel acts on it, that is your risk, not ours.",
            "",
            "The safer options are Fill command and Open page.",
            "You can change this back at any time."};

    private static final String[] SURE = {
            "Are you sure?",
            "",
            "Each click on an alert will send one command",
            "to the server, as if you had typed it.",
            "",
            "Hypixel has not approved this mod.",
            "You use this option at your own risk."};

    private final AlertsCore core;
    private final Map<String, String> settings;
    private final boolean sure;

    public ConfirmRunScreen(AlertsCore core, Map<String, String> settings) { this(core, settings, false); }

    /** sure=true is the second screen, "Are you sure?". Run on click only turns on after both screens are confirmed. */
    public ConfirmRunScreen(AlertsCore core, Map<String, String> settings, boolean sure) {
        super(Component.literal("Run on click"));
        this.core = core; this.settings = settings; this.sure = sure;
    }

    private String[] lines() { return sure ? SURE : LINES; }

    private void back() {
        Minecraft mc = Minecraft.getInstance();
        Msg.openScreen(mc, mc.gui, new SettingsScreen(core, settings));
    }

    private static final int RED = 0xFFD9534F, WHITE = 0xFFEDEDED, GRAY = 0xFFB9A8A6;

    /** Dark backdrop with a red edge, so this screen is clearly different from the normal menu. Lines are drawn as plain text. */
    public void extractBackground(net.minecraft.client.gui.GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        super.extractBackground(g, mouseX, mouseY, delta);
        g.fillGradient(0, 0, this.width, this.height, 0xD0140404, 0xE0301008);
        int cx = this.width / 2, x1 = Math.max(2, cx - 170), x2 = Math.min(this.width - 2, cx + 170);
        g.fill(x1, 0, x2, this.height, 0xE61A0A0A);
        g.fill(x1, 0, x2, 3, RED);
        g.outline(x1, -1, x2 - x1, this.height + 1, RED);
        g.centeredText(this.font, sure ? "WARNING: CONFIRM RUN ON CLICK" : "WARNING: RUN ON CLICK", cx, 12, RED);
        int y = 34;
        for (String l : lines()) {
            if (l.isEmpty()) { y += 8; continue; }
            g.centeredText(this.font, l, cx, y, l.startsWith("The safer") || l.startsWith("You can") ? GRAY : (sure && l.startsWith("Are you")) ? RED : WHITE);
            y += 12;
        }
    }

    @Override
    protected void init() {
        int cx = this.width / 2, y = 34;
        for (String l : lines()) y += l.isEmpty() ? 8 : 12;
        y = Math.max(y + 10, this.height - 56);
        if (sure) {
            addRenderableWidget(Button.builder(Component.literal("Yes, turn on Run on click"), b -> { core.setClick("run", true); back(); }).bounds(cx - 150, y, 300, 20).build());
            addRenderableWidget(Button.builder(Component.literal("No, go back"), b -> back()).bounds(cx - 150, y + 24, 300, 20).build());
        } else {
            addRenderableWidget(Button.builder(Component.literal("I understand, continue"), b -> Msg.openScreen(Minecraft.getInstance(), Minecraft.getInstance().gui, new ConfirmRunScreen(core, settings, true))).bounds(cx - 150, y, 300, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Keep it safe (cancel)"), b -> back()).bounds(cx - 150, y + 24, 300, 20).build());
        }
    }

    @Override
    public void onClose() { back(); }
}
