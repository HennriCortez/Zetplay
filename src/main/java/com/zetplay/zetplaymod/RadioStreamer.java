package com.zetplay.zetplaymod;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public class RadioStreamer implements Runnable {

    public static int getSampleRate() { return ZetPlayConfig.get().sampleRate; }
    public static int getFrameSamples() { return ZetPlayConfig.get().frameSamples; }
    public static int getFrameBytes() { return getFrameSamples() * 2; }
    private static final long FRAME_MS = 20L;

    private static final short[] EOF_SENTINEL = new short[0];

    private final String url;
    private final Consumer<short[]> frameConsumer;

    private final AtomicBoolean stopFlag = new AtomicBoolean(false);
    private volatile Process ffmpeg = null;

    public volatile boolean errorOccurred = false;
    public volatile String errorMessage = "";

    public RadioStreamer(String url, Consumer<short[]> frameConsumer) {
        this.url = url;
        this.frameConsumer = frameConsumer;
    }

    public void stop() {
        stopFlag.set(true);
        Process p = ffmpeg;
        if (p != null) p.destroyForcibly();
    }

    public boolean isStopped() {
        return stopFlag.get();
    }

    @Override
    public void run() {
        BlockingQueue<short[]> frameBuffer = new ArrayBlockingQueue<>(300);

        try {
            ffmpeg = new ProcessBuilder(
                "ffmpeg",
                "-hide_banner", "-loglevel", "error",
                "-i", url,
                "-vn",
                "-af", "pan=mono|c0=0.5*c0+0.5*c1",
                "-f", "s16le",
                "-ar", String.valueOf(getSampleRate()),
                "pipe:1"
            ).redirectError(ProcessBuilder.Redirect.DISCARD).start();

        } catch (IOException e) {
            errorOccurred = true;
            errorMessage = e.getMessage();
            ZetPlayMod.LOGGER.error("[ZetPlay/Radio] Failed to start ffmpeg: {}", e.getMessage());
            return;
        }

        final InputStream pcm = ffmpeg.getInputStream();
        Thread reader = new Thread(() -> {
            byte[] buf = new byte[getFrameBytes()];
            try {
                while (!stopFlag.get()) {
                    int n = readFully(pcm, buf);
                    if (n <= 0) break;
                    if (n < getFrameBytes())
                        for (int i = n; i < getFrameBytes(); i++) buf[i] = 0;
                    short[] frame = new short[getFrameSamples()];
                    bytesToShorts(buf.clone(), frame);
                    frameBuffer.put(frame);
                }
            } catch (IOException | InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                try { frameBuffer.put(EOF_SENTINEL); } catch (InterruptedException ignored) {}
            }
        }, "zetplay-radio-reader");
        reader.setDaemon(true);
        reader.start();

        long nextDeadline = System.nanoTime();
        try {
            while (true) {
                if (stopFlag.get()) {
                    reader.interrupt();
                    break;
                }

                short[] frame = frameBuffer.poll(500, TimeUnit.MILLISECONDS);
                if (frame == null) continue;
                if (frame == EOF_SENTINEL) break;

                frameConsumer.accept(frame);

                nextDeadline += FRAME_MS * 1_000_000L;
                long sleepNs = nextDeadline - System.nanoTime();
                if (sleepNs > 0) TimeUnit.NANOSECONDS.sleep(sleepNs);
                else nextDeadline = System.nanoTime();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            stopFlag.set(true);
            Process p = ffmpeg;
            if (p != null) p.destroyForcibly();
            try { reader.join(1000); } catch (InterruptedException ignored) {}
        }
    }

    private static int readFully(InputStream in, byte[] buf) throws IOException {
        int total = 0;
        while (total < buf.length) {
            int n = in.read(buf, total, buf.length - total);
            if (n < 0) break;
            total += n;
        }
        return total;
    }

    private static void bytesToShorts(byte[] bytes, short[] out) {
        for (int i = 0; i < out.length; i++)
            out[i] = (short) ((bytes[i * 2] & 0xFF) | (bytes[i * 2 + 1] << 8));
    }
}