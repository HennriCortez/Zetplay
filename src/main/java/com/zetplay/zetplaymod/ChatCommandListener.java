package com.zetplay.zetplaymod;

import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.network.chat.ChatType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Listens to in-game chat and handles ZetPlay commands:
 *
 *   Queue (yt-dlp) commands:
 *     !play, !skip, !pause, !resume, !queue (!q), !stop, !np, !help
 *
 *   Radio-stream commands:
 *     !stream <url>  — start a live radio/audio URL (conflicts with yt-dlp queue)
 *     !stopstream    — stop the current radio stream
 *     !streaminfo    — show the URL currently being streamed
 *
 * Only one mode can be active at a time. Starting a queue track while a stream
 * is live (or vice-versa) is blocked with a helpful message.
 */
public class ChatCommandListener implements ServerMessageEvents.ChatMessage {

    // ── radio state ──────────────────────────────────────────────────────────

    /** Currently active RadioStreamer, or null when no stream is running. */
    private volatile RadioStreamer   activeStreamer  = null;
    /** Background thread running the active RadioStreamer. */
    private volatile Thread          streamerThread  = null;
    /** URL that is currently streaming (for !streaminfo). */
    private volatile String          currentStreamUrl = null;

    // ── yt-dlp download thread ───────────────────────────────────────────────

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "zetplay-download");
        t.setDaemon(true);
        return t;
    });

    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void onChatMessage(PlayerChatMessage message, ServerPlayer sender, ChatType.Bound bound) {
        String raw = message.decoratedContent().getString().trim();
        if (!raw.startsWith("!")) return;
        MinecraftServer server = sender.level().getServer();
        if (server == null) return;
        handle(raw, sender, server);
    }

    // ─────────────────────────────────────────────────────────────────────────

    private void handle(String raw, ServerPlayer sender, MinecraftServer server) {
        ZetPlayPlugin plugin = ZetPlayPlugin.INSTANCE;
        if (plugin == null) {
            tell(sender, "§c[ZetPlay] Not ready yet — is Simple Voice Chat loaded?");
            return;
        }

        ZetPlayQueue q = plugin.playQueue;

        // ── !stream <url> ──────────────────────────────────────────────────
        if (raw.startsWith("!stream ")) {
            String url = raw.substring(8).trim();

            if (url.isEmpty()) {
                tell(sender, "§e[ZetPlay] Usage: §a!stream <url>  §7(e.g. https://ice1.somafm.com/groovesalad-256-mp3)");
                return;
            }
            if (!isValidUrl(url)) {
                tell(sender, "§c[ZetPlay] That doesn't look like a valid URL. Make sure it starts with http:// or https://");
                return;
            }

            // Mutual exclusivity: block if yt-dlp queue has something playing
            if (q.getCurrent() != null) {
                tell(sender, "§c[ZetPlay] The music queue is active. Use §a!stop§c first, then §a!stream§c.");
                return;
            }
            // Block if another stream is already running
            if (isStreamRunning()) {
                tell(sender, "§c[ZetPlay] Already streaming. Use §a!stopstream§c first.");
                return;
            }

            startStream(url, sender, server, plugin);
            return;
        }

        // ── !stopstream ───────────────────────────────────────────────────
        if (raw.equals("!stopstream")) {
            if (!isStreamRunning()) {
                tell(sender, "§e[ZetPlay] No stream is running.");
            } else {
                stopStream();
                broadcast(server, "§b[ZetPlay] §f⏹ Radio stream stopped.");
            }
            return;
        }

        // ── !streaminfo ───────────────────────────────────────────────────
        if (raw.equals("!streaminfo")) {
            if (!isStreamRunning()) {
                tell(sender, "§b[ZetPlay] §fNo radio stream is active.");
            } else {
                tell(sender, "§b[ZetPlay] §f📻 Streaming: §e" + currentStreamUrl);
            }
            return;
        }

        // ── Guard: block queue commands while a stream is live ────────────
        boolean isQueueCommand =
            raw.startsWith("!play ") || raw.equals("!skip") || raw.equals("!pause") ||
            raw.equals("!resume") || raw.equals("!stop") || raw.equals("!queue") ||
            raw.equals("!q") || raw.equals("!np");

        if (isQueueCommand && isStreamRunning()) {
            tell(sender, "§c[ZetPlay] A radio stream is active. Use §a!stopstream§c first.");
            return;
        }


        // AUDIORECOGNIZE HANDLE !title
        if (raw.equals("!title") || raw.equals("!shazam")) {
    if (!isStreamRunning() && q.getCurrent() == null) {
        tell(sender, "§e[ZetPlay] Nothing is currently streaming audio.");
        return;
    }

    broadcast(server, "§b[ZetPlay] §f🎧 Listening to audio stream...");

    boolean started = plugin.captureAudio(10, rawPcm -> {
        // Convert raw PCM -> WAV
        byte[] wavBytes = AudioRecognizer.pcmToWav(rawPcm, 48000, 1, 16);

        // Query AudD API
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
    return;
}






        // ── Existing queue commands (unchanged) ───────────────────────────

        if (raw.startsWith("!play ")) {
            String query = raw.substring(6).trim();
            if (query.isEmpty()) {
                tell(sender, "§e[ZetPlay] Usage: !play <song title>");
                return;
            }

            broadcast(server, "§b[ZetPlay] §fSearching: §e" + query + "§f...");

            executor.submit(() -> {
                String title = ZetPlayAudio.resolveTitle(query);
                int pos = plugin.enqueue(query, title);

                server.execute(() -> {
                    if (q.getCurrent() == null && pos == 1) {
                        broadcast(server, "§b[ZetPlay] §f▶ Now playing: §e" + title);
                    } else {
                        broadcast(server, "§b[ZetPlay] §f➕ Queued §7(#" + pos + ")§f: §e" + title);
                    }
                });
            });

        } else if (raw.equals("!skip")) {
            ZetPlayQueue.Track current = q.getCurrent();
            if (current == null) {
                tell(sender, "§e[ZetPlay] Nothing to skip.");
            } else {
                String skipped = current.title();
                q.requestSkip();
                List<ZetPlayQueue.Track> upcoming = q.snapshot();
                if (!upcoming.isEmpty()) {
                    broadcast(server, "§b[ZetPlay] §f⏭ Skipped §e" + skipped
                            + "§f. Up next: §e" + upcoming.get(0).title());
                } else {
                    broadcast(server, "§b[ZetPlay] §f⏭ Skipped §e" + skipped + "§f. Queue empty.");
                }
            }

        } else if (raw.equals("!pause")) {
            ZetPlayQueue.Track current = q.getCurrent();
            if (current == null) {
                tell(sender, "§e[ZetPlay] Nothing is playing.");
            } else if (q.isPaused()) {
                tell(sender, "§e[ZetPlay] Already paused.");
            } else {
                q.setPaused(true);
                broadcast(server, "§b[ZetPlay] §f⏸ Paused §e" + current.title()
                        + "§f. Use §a!resume§f to continue.");
            }

        } else if (raw.equals("!resume")) {
            if (!q.isPaused()) {
                tell(sender, "§e[ZetPlay] Not paused.");
            } else {
                q.setPaused(false);
                ZetPlayQueue.Track current = q.getCurrent();
                broadcast(server, "§b[ZetPlay] §f▶ Resumed"
                        + (current != null ? " §e" + current.title() : "") + "§f.");
            }

        } else if (raw.equals("!stop")) {
            q.requestStop();
            broadcast(server, "§b[ZetPlay] §f⏹ Stopped. Queue cleared.");

        } else if (raw.equals("!queue") || raw.equals("!q")) {
            ZetPlayQueue.Track current = q.getCurrent();
            List<ZetPlayQueue.Track> upcoming = q.snapshot();

            if (current == null && upcoming.isEmpty()) {
                tell(sender, "§b[ZetPlay] §fQueue is empty.");
                return;
            }

            StringBuilder sb = new StringBuilder("§b[ZetPlay] §f🎵 Now: §e");
            sb.append(current != null ? current.title() : "nothing");
            if (!upcoming.isEmpty()) {
                sb.append("\n§fUp next:");
                int i = 1;
                for (ZetPlayQueue.Track t : upcoming) {
                    sb.append("\n  §7").append(i++).append(". §f").append(t.title());
                    if (i > 10) { sb.append("\n  §7... and ").append(upcoming.size() - 10).append(" more"); break; }
                }
            }
            tell(sender, sb.toString());

        } else if (raw.equals("!np")) {
            ZetPlayQueue.Track current = q.getCurrent();
            if (current != null) {
                tell(sender, "§b[ZetPlay] §f🎵 Now playing: §e" + current.title()
                        + (q.isPaused() ? " §7(paused)" : ""));
            } else {
                tell(sender, "§b[ZetPlay] §fNothing is playing.");
            }

        } else if (raw.equals("!help")) {
            tell(sender,
                "§b[ZetPlay] §fCommands:\n" +
                "§7── Queue (yt-dlp) ──────────────\n" +
                "  §a!play <song>§f — Search & queue a track\n" +
                "  §a!skip§f — Skip current track\n" +
                "  §a!pause§f — Pause playback\n" +
                "  §a!resume§f — Resume playback\n" +
                "  §a!queue §7/ §a!q§f — Show queue\n" +
                "  §a!stop§f — Stop & clear queue\n" +
                "  §a!np§f — Show now playing\n" +
                "§7── Radio Stream ────────────────\n" +
                "  §a!stream <url>§f — Start a radio/audio stream\n" +
                "  §a!stopstream§f — Stop the radio stream\n" +
                "  §a!streaminfo§f — Show the current stream URL\n" +
                "§7─────────────────────────────────\n" +
                "  §a!help§f — Show this message\n" +
                "§7Note: queue and stream cannot run at the same time."+
                "§a!title§f — Recognize and show the title of the current live audio stream"
            );
        }
    }

    // ── Radio helpers ─────────────────────────────────────────────────────────

    private void startStream(String url, ServerPlayer sender, MinecraftServer server, ZetPlayPlugin plugin) {
        broadcast(server, "§b[ZetPlay] §f📻 Connecting to stream: §e" + url + "§f...");

        RadioStreamer rs = new RadioStreamer(url, frame -> plugin.sendFrame(frame));

        Thread t = new Thread(rs, "zetplay-radio");
        t.setDaemon(true);

        activeStreamer   = rs;
        streamerThread   = t;
        currentStreamUrl = url;

        t.start();

        // Monitor the thread so we can auto-announce when it dies unexpectedly
        Thread watchdog = new Thread(() -> {
            try { t.join(); } catch (InterruptedException ignored) {}

            // Only broadcast if this is still the stream we launched
            // (avoids a stale message if !stopstream was used)
            if (activeStreamer == rs) {
                activeStreamer    = null;
                streamerThread    = null;
                currentStreamUrl  = null;

                server.execute(() -> {
                    if (rs.errorOccurred) {
                        broadcast(server, "§c[ZetPlay] 📻 Stream ended with an error: " + rs.errorMessage);
                    } else if (!rs.isStopped()) {
                        // Stream ended naturally (URL ran out / server closed connection)
                        broadcast(server, "§b[ZetPlay] §f📻 Stream ended.");
                    }
                    // isStopped() = true means !stopstream was used; already announced
                });
            }
        }, "zetplay-radio-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();

        broadcast(server, "§b[ZetPlay] §f📻 Now streaming. Use §a!stopstream§f to stop.");
    }

    private void stopStream() {
        RadioStreamer rs = activeStreamer;
        if (rs != null) rs.stop();
        activeStreamer   = null;
        streamerThread   = null;
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

    // ── URL validation ────────────────────────────────────────────────────────

    private static boolean isValidUrl(String s) {
        try {
            URL url = new URL(s);
            String proto = url.getProtocol();
            return proto.equals("http") || proto.equals("https");
        } catch (MalformedURLException e) {
            return false;
        }
    }

    // ── Message helpers ───────────────────────────────────────────────────────

    private void tell(ServerPlayer player, String msg) {
        player.sendSystemMessage(Component.literal(msg));
    }

    private void broadcast(MinecraftServer server, String msg) {
        server.getPlayerList().broadcastSystemMessage(Component.literal(msg), false);
    }
}
