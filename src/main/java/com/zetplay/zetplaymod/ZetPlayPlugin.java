package com.zetplay.zetplaymod;

import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.VolumeCategory;
import de.maxhenkel.voicechat.api.audiochannel.StaticAudioChannel;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.PlayerConnectedEvent;
import de.maxhenkel.voicechat.api.events.PlayerDisconnectedEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStoppedEvent;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.concurrent.Future;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;




import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;

public class ZetPlayPlugin implements VoicechatPlugin {

    // Stable UUID for the ZetPlay audio channel
    private static final UUID ZETPLAY_CHANNEL_ID =
            UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");

    // ID of the volume-mixer category shown to players. Must be unique.
    private static final String ZETPLAY_CATEGORY_ID = "zetplay_music";

    // Singleton so ChatCommandListener can reach the queue
    static ZetPlayPlugin INSTANCE;

    private VoicechatApi api;
    private VoicechatServerApi serverApi;

    private StaticAudioChannel channel;
    private OpusEncoder encoder;

    // PCM frames waiting to be encoded and sent
    private final LinkedBlockingQueue<short[]> pcmQueue = new LinkedBlockingQueue<>(200);

    // Playback queue shared with ChatCommandListener
    final ZetPlayQueue playQueue = new ZetPlayQueue();

    private Thread sendThread;
    private Thread playThread;
    private volatile boolean running = false;

    // AUDIORECOGNIZER
    private final ByteArrayOutputStream captureStream = new ByteArrayOutputStream();
    private volatile boolean isCapturing = false;
    private volatile int targetCaptureBytes = 0;
    private volatile Consumer<byte[]> captureCallback = null;

    public synchronized boolean captureAudio(int seconds, Consumer<byte[]> onComplete) {
    if (isCapturing) return false;
    captureStream.reset();
    targetCaptureBytes = seconds * ZetPlayConfig.get().sampleRate * 2;
    captureCallback = onComplete;
    isCapturing = true;
    return true;
}




    public ZetPlayPlugin() {
        INSTANCE = this;
    }

    @Override
    public String getPluginId() {
        return ZetPlayMod.MOD_ID;
    }

    @Override
    public void initialize(VoicechatApi api) {
        this.api = api;
        ZetPlayMod.LOGGER.info("[ZetPlay] SVC plugin initialized.");
    }

    @Override
    public void registerEvents(EventRegistration reg) {
        reg.registerEvent(VoicechatServerStartedEvent.class,  this::onServerStarted);
        reg.registerEvent(VoicechatServerStoppedEvent.class,  this::onServerStopped);
        reg.registerEvent(PlayerConnectedEvent.class,         this::onPlayerConnected);
        reg.registerEvent(PlayerDisconnectedEvent.class,      this::onPlayerDisconnected);
    }

    // ── SVC events ────────────────────────────────────────────────────────────

    private void onServerStarted(VoicechatServerStartedEvent event) {
        serverApi = event.getVoicechat();
        encoder   = api.createEncoder();

        // Register a dedicated volume category so "ZetPlay" gets its own
        // slider in the client's voice chat volume mixer, instead of being
        // lumped in under the default/master volume.
        VolumeCategory zetplayCategory = serverApi.volumeCategoryBuilder()
                .setId(ZETPLAY_CATEGORY_ID)
                .setName("ZetPlay")
                .setDescription("Volume of music played via !play")
                .build();
        serverApi.registerVolumeCategory(zetplayCategory);

        // Create a static channel and put it in that category
        channel = serverApi.createStaticAudioChannel(ZETPLAY_CHANNEL_ID);

        if (channel == null) {
            ZetPlayMod.LOGGER.warn("[ZetPlay] Could not create static audio channel.");
            return;
        }

        channel.setCategory(ZETPLAY_CATEGORY_ID);

        ZetPlayMod.LOGGER.info("[ZetPlay] Static audio channel 'ZetPlay' created with volume category.");
        running = true;
        startSendThread();
        startPlayThread();
    }

    private void onServerStopped(VoicechatServerStoppedEvent event) {
        running = false;
        playQueue.requestStop();
        if (sendThread  != null) sendThread.interrupt();
        if (playThread  != null) playThread.interrupt();
        if (encoder     != null) { encoder.close(); encoder = null; }
        channel   = null;
        serverApi = null;
    }

    private void onPlayerConnected(PlayerConnectedEvent event) {
        if (channel != null) {
            channel.addTarget(event.getConnection());
        }
    }

    private void onPlayerDisconnected(PlayerDisconnectedEvent event) {
        // SVC removes the connection from channels automatically
    }

    // ── Audio send thread (encodes PCM → Opus → SVC channel) ─────────────────

private void startSendThread() {
    sendThread = new Thread(() -> {
        long nextDeadline = System.nanoTime();
        while (running) {
            try {
                short[] pcm = pcmQueue.poll(20, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (pcm == null) pcm = new short[ZetPlayAudio.getFrameSamples()];

                // ── CAPTURE TAP FOR AUDIO RECOGNITION ──────────────────────
                if (isCapturing) {
                    synchronized (captureStream) {
                        for (short sample : pcm) {
                            captureStream.write(sample & 0xFF);
                            captureStream.write((sample >> 8) & 0xFF);
                        }
                        if (captureStream.size() >= targetCaptureBytes) {
                            isCapturing = false;
                            byte[] rawPcm = captureStream.toByteArray();
                            Consumer<byte[]> cb = captureCallback;
                            if (cb != null) {
                                new Thread(() -> cb.accept(rawPcm)).start();
                            }
                        }
                    }
                }
                // ──────────────────────────────────────────────────────────

                if (encoder == null || channel == null) {
                    nextDeadline += 20 * 1_000_000L;
                    continue;
                }
                byte[] opus = encoder.encode(pcm);
                if (opus != null && opus.length > 0) {
                    channel.send(opus);
                }
                nextDeadline += 20 * 1_000_000L;
                long sleepNanos = nextDeadline - System.nanoTime();
                if (sleepNanos > 0) TimeUnit.NANOSECONDS.sleep(sleepNanos);
                else nextDeadline = System.nanoTime();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                ZetPlayMod.LOGGER.error("[ZetPlay] Send thread error", e);
            }
        }
    }, "zetplay-send");
    sendThread.setDaemon(true);
    sendThread.start();
}

    // ── Play thread (dequeues tracks, streams PCM) ────────────────────────────

    private void startPlayThread() {
    playThread = new Thread(() -> {
        Future<Path> prefetchFuture = null;
        ZetPlayQueue.Track prefetchedTrack = null;

        while (running) {
            ZetPlayQueue.Track track = playQueue.poll();
            if (track == null) {
                try { Thread.sleep(200); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); break;
                }
                continue;
            }

            playQueue.setCurrent(track);
            playQueue.clearSkip();
            playQueue.clearStop();
            ZetPlayMod.LOGGER.info("[ZetPlay] Now playing: {}", track.title());

            try {
                Path downloaded;

                // Use prefetched file if it's for this track
                if (prefetchFuture != null && track.equals(prefetchedTrack)) {
                    downloaded = prefetchFuture.get(); // already done or nearly done
                    prefetchFuture = null;
                    prefetchedTrack = null;
                } else {
                    // Cancel stale prefetch and download normally
                    if (prefetchFuture != null) { prefetchFuture.cancel(true); prefetchFuture = null; }
                    downloaded = ZetPlayAudio.prefetch(track.query()).get();
                }

                // Kick off prefetch for next track while this one plays
		ZetPlayQueue.Track next = playQueue.snapshot().stream().findFirst().orElse(null);
		ZetPlayMod.LOGGER.info("[ZetPlay] Prefetch candidate: {}", next != null ? next.title() : "none");
                if (next != null) {
                    prefetchedTrack = next;
                    prefetchFuture = ZetPlayAudio.prefetch(next.query());
                }

                ZetPlayAudio.stream(downloaded, playQueue, pcm -> pcmQueue.offer(pcm));

            } catch (Exception e) {
                ZetPlayMod.LOGGER.error("[ZetPlay] Play error: {}", e.getMessage());
            }

            playQueue.setCurrent(null);
            if (playQueue.isStopRequested()) {
                if (prefetchFuture != null) { prefetchFuture.cancel(true); prefetchFuture = null; }
                playQueue.clear();
                playQueue.clearStop();
            }
            playQueue.clearSkip();
            playQueue.setPaused(false);
        }
    }, "zetplay-play");
    playThread.setDaemon(true);
    playThread.start();
}
    // ── Public API for ChatCommandListener ────────────────────────────────────

    /** Enqueue a new track. Returns position in queue (1 = now playing next). */
    public int enqueue(String query, String resolvedTitle) {
        playQueue.add(new ZetPlayQueue.Track(resolvedTitle, query));
        return playQueue.size();
    }

    /** Called by RadioStreamer to push PCM frames into the same audio channel. */
    public void sendFrame(short[] frame) {
        pcmQueue.offer(frame);
    }

}
