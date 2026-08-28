package com.zetplay.zetplaymod;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.zetplay.zetplaymod.ChatCommandListener;
import com.zetplay.zetplaymod.ZetPlayAudio;
import com.zetplay.zetplaymod.ZetPlayPlugin;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ZetPlayCommands {

    private static final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "zetplay-download");
        t.setDaemon(true);
        return t;
    });

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            registerCommands(dispatcher);
        });
    }

    private static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        
        // ── /play <song-title> ──────────────────────────────────────────────
        dispatcher.register(
            Commands.literal("play")
                .then(Commands.argument("song-title", StringArgumentType.greedyString())
                    .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                        List.of("<song-title>", "Minecraft Soundtrack"), builder))
                    .executes(context -> {
                        ServerPlayer player = context.getSource().getPlayerOrException();
                        String query = StringArgumentType.getString(context, "song-title");
                        handlePlay(player, query);
                        return 1;
                    })
                )
        );

        // ── /stream <url> ───────────────────────────────────────────────────
        dispatcher.register(
            Commands.literal("stream")
                .then(Commands.argument("url", StringArgumentType.greedyString())
                    .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                        List.of("https://ice1.somafm.com/groovesalad-256-mp3", "<url>"), builder))
                    .executes(context -> {
                        ServerPlayer player = context.getSource().getPlayerOrException();
                        String url = StringArgumentType.getString(context, "url");
                        handleStream(player, url);
                        return 1;
                    })
                )
        );

        // ── /stopstream ─────────────────────────────────────────────────────
        dispatcher.register(
            Commands.literal("stopstream")
                .executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    ChatCommandListener listener = ChatCommandListener.INSTANCE;
                    if (listener != null) {
                        listener.executeStopStream(player);
                    }
                    return 1;
                })
        );

        // ── /streaminfo ─────────────────────────────────────────────────────
        dispatcher.register(
            Commands.literal("streaminfo")
                .executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    ChatCommandListener listener = ChatCommandListener.INSTANCE;
                    if (listener != null) {
                        listener.executeStreamInfo(player);
                    }
                    return 1;
                })
        );

        // ── /shazam / /title ────────────────────────────────────────────────
        dispatcher.register(
            Commands.literal("shazam")
                .executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    ChatCommandListener listener = ChatCommandListener.INSTANCE;
                    if (listener != null) {
                        listener.executeTitle(player);
                    }
                    return 1;
                })
        );
        dispatcher.register(
            Commands.literal("title")
                .executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    ChatCommandListener listener = ChatCommandListener.INSTANCE;
                    if (listener != null) {
                        listener.executeTitle(player);
                    }
                    return 1;
                })
        );

        // ── Queue Control Commands ──────────────────────────────────────────
        dispatcher.register(Commands.literal("skip").executes(c -> handleSkip(c.getSource().getPlayerOrException())));
        dispatcher.register(Commands.literal("pause").executes(c -> handlePause(c.getSource().getPlayerOrException())));
        dispatcher.register(Commands.literal("resume").executes(c -> handleResume(c.getSource().getPlayerOrException())));
        dispatcher.register(Commands.literal("stop").executes(c -> handleStop(c.getSource().getPlayerOrException())));
        dispatcher.register(Commands.literal("queue").executes(c -> handleQueue(c.getSource().getPlayerOrException())));
        dispatcher.register(Commands.literal("q").executes(c -> handleQueue(c.getSource().getPlayerOrException())));
        dispatcher.register(Commands.literal("np").executes(c -> handleNowPlaying(c.getSource().getPlayerOrException())));
    }

    // ── Command Action Handlers ──────────────────────────────────────────────

    private static void handlePlay(ServerPlayer sender, String query) {
        ZetPlayPlugin plugin = ZetPlayPlugin.INSTANCE;
        MinecraftServer server = sender.level().getServer();
        if (plugin == null || server == null) return;

        ChatCommandListener listener = ChatCommandListener.INSTANCE;
        if (listener != null && listener.isStreamRunning()) {
            sender.sendSystemMessage(Component.literal("§c[ZetPlay] A radio stream is active. Use §a/stopstream§c first."));
            return;
        }

        server.getPlayerList().broadcastSystemMessage(Component.literal("§b[ZetPlay] §fSearching: §e" + query + "§f..."), false);

        executor.submit(() -> {
            String title = ZetPlayAudio.resolveTitle(query);
            int pos = plugin.enqueue(query, title);

            server.execute(() -> {
                if (plugin.playQueue.getCurrent() == null && pos == 1) {
                    server.getPlayerList().broadcastSystemMessage(Component.literal("§b[ZetPlay] §f▶ Now playing: §e" + title), false);
                } else {
                    server.getPlayerList().broadcastSystemMessage(Component.literal("§b[ZetPlay] §f➕ Queued §7(#" + pos + ")§f: §e" + title), false);
                }
            });
        });
    }

    private static void handleStream(ServerPlayer sender, String url) {
        ZetPlayPlugin plugin = ZetPlayPlugin.INSTANCE;
        MinecraftServer server = sender.level().getServer();
        ChatCommandListener listener = ChatCommandListener.INSTANCE;
        if (plugin == null || server == null || listener == null) return;

        if (!isValidUrl(url)) {
            sender.sendSystemMessage(Component.literal("§c[ZetPlay] Invalid URL. Must start with http:// or https://"));
            return;
        }
        if (plugin.playQueue.getCurrent() != null) {
            sender.sendSystemMessage(Component.literal("§c[ZetPlay] Music queue active. Use §a/stop§c first."));
            return;
        }
        if (listener.isStreamRunning()) {
            sender.sendSystemMessage(Component.literal("§c[ZetPlay] Already streaming. Use §a/stopstream§c first."));
            return;
        }

        listener.startStream(url, sender, server, plugin);
    }

    private static int handleSkip(ServerPlayer sender) {
        ZetPlayPlugin plugin = ZetPlayPlugin.INSTANCE;
        if (plugin == null) return 0;
        ZetPlayQueue q = plugin.playQueue;
        ZetPlayQueue.Track current = q.getCurrent();
        if (current == null) {
            sender.sendSystemMessage(Component.literal("§e[ZetPlay] Nothing to skip."));
        } else {
            String skipped = current.title();
            q.requestSkip();
            List<ZetPlayQueue.Track> upcoming = q.snapshot();
            if (!upcoming.isEmpty()) {
                broadcast(sender, "§b[ZetPlay] §f⏭ Skipped §e" + skipped + "§f. Up next: §e" + upcoming.get(0).title());
            } else {
                broadcast(sender, "§b[ZetPlay] §f⏭ Skipped §e" + skipped + "§f. Queue empty.");
            }
        }
        return 1;
    }

    private static int handlePause(ServerPlayer sender) {
        ZetPlayPlugin plugin = ZetPlayPlugin.INSTANCE;
        if (plugin == null) return 0;
        ZetPlayQueue q = plugin.playQueue;
        ZetPlayQueue.Track current = q.getCurrent();
        if (current == null) {
            sender.sendSystemMessage(Component.literal("§e[ZetPlay] Nothing is playing."));
        } else if (q.isPaused()) {
            sender.sendSystemMessage(Component.literal("§e[ZetPlay] Already paused."));
        } else {
            q.setPaused(true);
            broadcast(sender, "§b[ZetPlay] §f⏸ Paused §e" + current.title() + "§f.");
        }
        return 1;
    }

    private static int handleResume(ServerPlayer sender) {
        ZetPlayPlugin plugin = ZetPlayPlugin.INSTANCE;
        if (plugin == null) return 0;
        ZetPlayQueue q = plugin.playQueue;
        if (!q.isPaused()) {
            sender.sendSystemMessage(Component.literal("§e[ZetPlay] Not paused."));
        } else {
            q.setPaused(false);
            ZetPlayQueue.Track current = q.getCurrent();
            broadcast(sender, "§b[ZetPlay] §f▶ Resumed" + (current != null ? " §e" + current.title() : "") + "§f.");
        }
        return 1;
    }

    private static int handleStop(ServerPlayer sender) {
        ZetPlayPlugin plugin = ZetPlayPlugin.INSTANCE;
        if (plugin == null) return 0;
        plugin.playQueue.requestStop();
        broadcast(sender, "§b[ZetPlay] §f⏹ Stopped. Queue cleared.");
        return 1;
    }

    private static int handleQueue(ServerPlayer sender) {
        ZetPlayPlugin plugin = ZetPlayPlugin.INSTANCE;
        if (plugin == null) return 0;
        ZetPlayQueue q = plugin.playQueue;
        ZetPlayQueue.Track current = q.getCurrent();
        List<ZetPlayQueue.Track> upcoming = q.snapshot();

        if (current == null && upcoming.isEmpty()) {
            sender.sendSystemMessage(Component.literal("§b[ZetPlay] §fQueue is empty."));
            return 1;
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
        sender.sendSystemMessage(Component.literal(sb.toString()));
        return 1;
    }

    private static int handleNowPlaying(ServerPlayer sender) {
        ZetPlayPlugin plugin = ZetPlayPlugin.INSTANCE;
        if (plugin == null) return 0;
        ZetPlayQueue q = plugin.playQueue;
        ZetPlayQueue.Track current = q.getCurrent();
        if (current != null) {
            sender.sendSystemMessage(Component.literal("§b[ZetPlay] §f🎵 Now playing: §e" + current.title() + (q.isPaused() ? " §7(paused)" : "")));
        } else {
            sender.sendSystemMessage(Component.literal("§b[ZetPlay] §fNothing is playing."));
        }
        return 1;
    }

    private static void broadcast(ServerPlayer sender, String msg) {
        MinecraftServer server = sender.level().getServer();
        if (server != null) {
            server.getPlayerList().broadcastSystemMessage(Component.literal(msg), false);
        }
    }

    private static boolean isValidUrl(String s) {
    try {
        java.net.URL url = java.net.URI.create(s).toURL();
        String proto = url.getProtocol();
        return proto.equals("http") || proto.equals("https");
    } catch (Exception e) {
        return false;
    }
    }
}