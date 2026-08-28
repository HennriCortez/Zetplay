package com.zetplay.zetplaymod;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

public class ChatCommandListener {

    public static ChatCommandListener INSTANCE;

    private volatile RadioStreamer activeStreamer = null;
    private volatile String currentStreamUrl = null;

    public ChatCommandListener() {
        INSTANCE = this;
    }

    public void executeStopStream(ServerPlayer sender) {
        if (!isStreamRunning()) {
            tell(sender, "§e[ZetPlay] No stream is running.");
        } else {
            stopStream();
            broadcast(sender.level().getServer(), "§b[ZetPlay] §f⏹ Radio stream stopped.");
        }
    }

    public void executeStreamInfo(ServerPlayer sender) {
        if (!isStreamRunning()) {
            tell(sender, "§b[ZetPlay] §fNo radio stream is active.");
        } else {
            tell(sender, "§b[ZetPlay] §f📻 Streaming: §e" + currentStreamUrl);
        }
    }

    public void executeTitle(ServerPlayer sender) {
        ZetPlayPlugin plugin = ZetPlayPlugin.INSTANCE;
        MinecraftServer server = sender.level().getServer();
        if (plugin == null || server == null) return;

        if (!isStreamRunning() && plugin.playQueue.getCurrent() == null) {
            tell(sender, "§e[ZetPlay] Nothing is currently streaming audio.");
            return;
        }

        broadcast(server, "§b[ZetPlay] §f🎧 Listening to audio stream...");

        boolean started = plugin.captureAudio(10, rawPcm -> {
            byte[] wavBytes = AudioRecognizer.pcmToWav(rawPcm, ZetPlayConfig.get().sampleRate, 1, 16);
            String songTitle = AudioRecognizer.recognize(wavBytes);

            server.execute(() -> {
                if (songTitle != null) {
                    broadcast(server, "§b[ZetPlay] §f📻 Recognized song: §e" + songTitle);
                } else {
                    broadcast(server, "§c[ZetPlay] Could not identify the playing song.");
                }
            });
        });

        if (!started) {
            tell(sender, "§e[ZetPlay] Audio recognition is already running. Please wait.");
        }
    }

    public void startStream(String url, ServerPlayer sender, MinecraftServer server, ZetPlayPlugin plugin) {
        broadcast(server, "§b[ZetPlay] §f📻 Connecting to stream: §e" + url + "§f...");

        RadioStreamer rs = new RadioStreamer(url, plugin::sendFrame);
        Thread t = new Thread(rs, "zetplay-radio");
        t.setDaemon(true);

        activeStreamer = rs;
        currentStreamUrl = url;
        t.start();

        Thread watchdog = new Thread(() -> {
            try { t.join(); } catch (InterruptedException ignored) {}
            if (activeStreamer == rs) {
                activeStreamer = null;
                currentStreamUrl = null;

                server.execute(() -> {
                    if (rs.errorOccurred) {
                        broadcast(server, "§c[ZetPlay] 📻 Stream ended with error: " + rs.errorMessage);
                    } else if (!rs.isStopped()) {
                        broadcast(server, "§b[ZetPlay] §f📻 Stream ended.");
                    }
                });
            }
        }, "zetplay-radio-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();

        broadcast(server, "§b[ZetPlay] §f📻 Now streaming. Use §a/stopstream§f to stop.");
    }

    public void stopStream() {
        RadioStreamer rs = activeStreamer;
        if (rs != null) rs.stop();
        activeStreamer = null;
        currentStreamUrl = null;
    }

    public boolean isStreamRunning() {
        RadioStreamer rs = activeStreamer;
        if (rs == null) return false;
        if (rs.isStopped()) {
            activeStreamer = null;
            currentStreamUrl = null;
            return false;
        }
        return true;
    }

    private void tell(ServerPlayer player, String msg) {
        player.sendSystemMessage(Component.literal(msg));
    }

    private void broadcast(MinecraftServer server, String msg) {
        server.getPlayerList().broadcastSystemMessage(Component.literal(msg), false);
    }
}