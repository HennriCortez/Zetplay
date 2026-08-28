package com.zetplay.zetplaymod;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class ZetPlayAudio {

    public static int getSampleRate() { return ZetPlayConfig.get().sampleRate; }
    public static int getFrameSamples() { return ZetPlayConfig.get().frameSamples; }
    public static int getFrameBytes() { return getFrameSamples() * 2; }
    private static final long FRAME_DURATION_MS = 20;

    public static String resolveTitle(String query) {
        try {
            Process p = new ProcessBuilder(
                "yt-dlp",
                "--no-playlist",
                "--get-title",
                "ytsearch1:" + query
            ).redirectErrorStream(false).start();

            try (var br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()))) {
                String line = br.readLine();
                p.waitFor();
                return (line != null && !line.isBlank()) ? line.trim() : query;
            }
        } catch (Exception e) {
            ZetPlayMod.LOGGER.warn("[ZetPlay] resolveTitle failed: {}", e.getMessage());
            return query;
        }
    }

    private static Path downloadToTemp(String query) throws IOException, InterruptedException {
        Path tmpDir = Paths.get(System.getProperty("java.io.tmpdir"), "zetplay");
        Files.createDirectories(tmpDir);

        String baseName = "zetplay-" + UUID.randomUUID();
        Path outputTemplate = tmpDir.resolve(baseName + ".%(ext)s");

        Process ytdlp = new ProcessBuilder(
            "yt-dlp",
            "--no-playlist",
            "-x",
            "--audio-format", "wav",
            "-o", outputTemplate.toString(),
            "ytsearch1:" + query
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD)
         .redirectError(ProcessBuilder.Redirect.DISCARD)
         .start();

        boolean finished = ytdlp.waitFor(ZetPlayConfig.get().downloadTimeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            ytdlp.destroyForcibly();
            throw new IOException("yt-dlp timed out downloading: " + query);
        }
        if (ytdlp.exitValue() != 0) {
            throw new IOException("yt-dlp exited " + ytdlp.exitValue() + " for: " + query);
        }

        Path result = tmpDir.resolve(baseName + ".wav");
        if (!Files.isRegularFile(result)) {
            throw new IOException("Downloaded file missing after yt-dlp: " + result);
        }
        return result;
    }

    public static java.util.concurrent.Future<Path> prefetch(String query) {
        return java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "zetplay-prefetch");
            t.setDaemon(true);
            return t;
        }).submit(() -> downloadToTemp(query));
    }

    public static boolean stream(
            Path downloaded,
            ZetPlayQueue queue,
            Consumer<short[]> frameConsumer
    ) {
        final short[] EOF_SENTINEL = new short[0];
        final BlockingQueue<short[]> frameBuffer = new ArrayBlockingQueue<>(300);
        Process ffmpeg = null;

        try {
            if (queue.isSkipRequested() || queue.isStopRequested()) return false;

            ffmpeg = new ProcessBuilder(
                "ffmpeg", "-hide_banner", "-loglevel", "error",
                "-i", downloaded.toString(),
                "-vn",
                "-af", "pan=mono|c0=0.5*c0+0.5*c1",
                "-f", "s16le",
                "-ar", String.valueOf(getSampleRate()),
                "pipe:1"
            ).redirectError(ProcessBuilder.Redirect.DISCARD).start();

            final InputStream pcmStream = ffmpeg.getInputStream();
            final Thread readerThread = new Thread(() -> {
                byte[] buf = new byte[getFrameBytes()];
                try {
                    while (true) {
                        int read = readFully(pcmStream, buf);
                        if (read <= 0) break;
                        if (read < getFrameBytes())
                            for (int i = read; i < getFrameBytes(); i++) buf[i] = 0;
                        short[] frame = new short[getFrameSamples()];
                        bytesToShorts(buf.clone(), frame);
                        frameBuffer.put(frame);
                        if (queue.isSkipRequested()) break;
                    }
                } catch (IOException | InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    try { frameBuffer.put(EOF_SENTINEL); } catch (InterruptedException ignored) {}
                }
            }, "zetplay-reader");
            readerThread.setDaemon(true);
            readerThread.start();

            long nextDeadline = System.nanoTime();
            while (true) {
                if (queue.isSkipRequested()) { readerThread.interrupt(); return false; }
                if (queue.isPaused()) {
                    while (queue.isPaused()) {
                        frameConsumer.accept(new short[getFrameSamples()]);
                        try { Thread.sleep(FRAME_DURATION_MS); }
                        catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
                        if (queue.isSkipRequested()) { readerThread.interrupt(); return false; }
                    }
                    nextDeadline = System.nanoTime();
                }
                short[] frame = frameBuffer.poll(500, TimeUnit.MILLISECONDS);
                if (frame == null) continue;
                if (frame == EOF_SENTINEL) break;
                frameConsumer.accept(frame);
                nextDeadline += FRAME_DURATION_MS * 1_000_000L;
                long sleepNanos = nextDeadline - System.nanoTime();
                if (sleepNanos > 0) TimeUnit.NANOSECONDS.sleep(sleepNanos);
                else nextDeadline = System.nanoTime();
            }

            readerThread.join(1000);
            return !queue.isSkipRequested();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IOException e) {
            ZetPlayMod.LOGGER.error("[ZetPlay] Stream error: {}", e.getMessage());
            return false;
        } finally {
            if (ffmpeg != null) ffmpeg.destroyForcibly();
            try { Files.deleteIfExists(downloaded); }
            catch (IOException e) {
                ZetPlayMod.LOGGER.warn("[ZetPlay] Failed to delete temp file {}: {}", downloaded, e.getMessage());
            }
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

    private static void bytesToShorts(byte[] bytes, short[] output) {
        for (int i = 0; i < output.length; i++) {
            output[i] = (short) ((bytes[i * 2] & 0xFF) | (bytes[i * 2 + 1] << 8));
        }
    }
}