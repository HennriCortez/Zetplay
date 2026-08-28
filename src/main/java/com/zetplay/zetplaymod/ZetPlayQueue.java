package com.zetplay.zetplaymod;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Thread-safe queue and playback state for ZetPlay.
 */
public class ZetPlayQueue {

    public record Track(String title, String query) {}

    private final Deque<Track> queue = new ArrayDeque<>();
    private volatile Track current = null;
    private volatile boolean paused = false;
    private volatile boolean skipRequested = false;
    private volatile boolean stopRequested = false;

    // ── Queue ops ─────────────────────────────────────────────────────────────

    public synchronized void add(Track track) {
        queue.addLast(track);
    }

    public synchronized Track poll() {
        return queue.pollFirst();
    }

    public synchronized List<Track> snapshot() {
        return new ArrayList<>(queue);
    }

    public synchronized int size() {
        return queue.size();
    }

    public synchronized void clear() {
        queue.clear();
    }

    // ── Current track ─────────────────────────────────────────────────────────

    public Track getCurrent() { return current; }
    public void setCurrent(Track t) { current = t; }

    // ── Playback flags ────────────────────────────────────────────────────────

    public boolean isPaused() { return paused; }
    public void setPaused(boolean p) { paused = p; }

    public boolean isSkipRequested() { return skipRequested; }
    public void requestSkip() { skipRequested = true; paused = false; }
    public void clearSkip() { skipRequested = false; }

    public boolean isStopRequested() { return stopRequested; }
    public void requestStop() { stopRequested = true; skipRequested = true; paused = false; }
    public void clearStop() { stopRequested = false; }
}
