package com.reclaimedmarket.alerts;

/** Opens a web page in your normal browser. Used by the "Join our Discord" button. It only opens the page, nothing is sent. */
final class Link {
    static final String DISCORD = "https://discord.gg/FnQKUwjj2s";

    private Link() { }

    static void open(String url) {
        Thread t = new Thread(() -> {
            try {
                if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
                    java.awt.Desktop.getDesktop().browse(java.net.URI.create(url));
                    return;
                }
            } catch (Throwable ignored) { }
            try {
                String os = System.getProperty("os.name", "").toLowerCase();
                String[] cmd = os.contains("win") ? new String[]{"rundll32", "url.dll,FileProtocolHandler", url}
                        : os.contains("mac") ? new String[]{"open", url} : new String[]{"xdg-open", url};
                new ProcessBuilder(cmd).start();
            } catch (Throwable ignored) { }
        }, "reclaimed-open-link");
        t.setDaemon(true); t.start();
    }
}
