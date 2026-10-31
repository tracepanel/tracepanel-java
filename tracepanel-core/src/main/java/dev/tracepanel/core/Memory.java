package dev.tracepanel.core;

import com.sun.management.GarbageCollectionNotificationInfo;
import com.sun.management.GcInfo;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import javax.management.Notification;
import javax.management.NotificationEmitter;
import javax.management.openmbean.CompositeData;

/**
 * What a profiled trace's memory and the garbage collector did while it ran: the profile's `memory` and `gc`.
 *
 * <p>Its memory only when it asks for it ("memory" in X-Tracepanel-Profile or the panel's cookie, {@code
 * Tracepanel.profile(true)}, or TRACEPANEL_PROFILE_MEMORY=1 for every profiled trace):
 *
 * <ul>
 *   <li>allocated: the bytes the threads that ran the trace allocated while they ran it (the JVM's per-thread count,
 *       com.sun.management.ThreadMXBean), garbage or not: the trace's own, exactly, on every thread Tracepanel
 *       follows it to (an async request's dispatches, a coroutine's resumes). Work it hands to a pool Tracepanel
 *       doesn't follow isn't in it. Left out where the JVM can't count a thread (a virtual thread on Java 21).
 *   <li>peak: the heap's highest level while the trace ran, above its level at the start: read every 10 ms by the
 *       profiler's thread, before each collection (the collector's notification says), and at the end. Garbage counts
 *       until it is collected.
 *   <li>net: the heap's level at the end less at its start, garbage counted until collected: negative where a
 *       collection freed more meanwhile.
 *   <li>shared: another trace ran in the process meanwhile. The heap is the process's, so peak and net hold what it
 *       allocated too; allocated stays the trace's own.
 * </ul>
 *
 * <p>No allocation sites. The JVM's only cheap source, JFR's ObjectAllocationSample, takes about 150 samples a
 * second across the process (a request of 20 ms gets a handful), tells where memory was allocated rather than where
 * it was held at the peak or the end (what the profile's sites say), and streams its events about a second late, so
 * every memory-profiled trace would wait for it.
 *
 * <p>The collector's runs (`gc`) are in every profile: how many ran while the trace did and how long they stood the
 * program still (the collectors' counts and times), and each pause as the collector's notification tells it, [from
 * the trace's start, how long, 0 for a young collection and 2 for one of the whole heap]. A pause stops every thread,
 * so it is the trace's whichever ran it. The JVM tells them to the millisecond. Concurrent collectors (ZGC,
 * Shenandoah) count their cycles as collections and only their pauses as pause time.
 */
final class Memory {
    /** How often the heap's level is read while a trace's memory is measured, for its peak. */
    static final long HEAP_EVERY_MS = 10;
    /** The pauses a trace keeps: a long one under a busy collector has thousands. */
    static final int MAX_PAUSES = 1000;

    private static final MemoryMXBean HEAP = ManagementFactory.getMemoryMXBean();
    private static final RuntimeMXBean RUNTIME = ManagementFactory.getRuntimeMXBean();
    private static final com.sun.management.ThreadMXBean THREADS = threads();
    // Traces running in the process and begun so far, for `shared`: another ran while one did. A test's own trace
    // isn't counted: it waits for the requests it makes.
    private static final AtomicLong RUNNING = new AtomicLong();
    private static final AtomicLong BEGUN = new AtomicLong();
    private static final Set<Gc> WATCHED = ConcurrentHashMap.newKeySet();
    private static volatile boolean listening;

    private Memory() {}

    private static com.sun.management.ThreadMXBean threads() {
        try {
            ThreadMXBean threads = ManagementFactory.getThreadMXBean();
            if (threads instanceof com.sun.management.ThreadMXBean counting
                    && counting.isThreadAllocatedMemorySupported()
                    && counting.isThreadAllocatedMemoryEnabled()) {
                return counting;
            }
        } catch (RuntimeException | LinkageError e) {
            // A JVM without it: no `allocated`.
        }
        return null;
    }

    static void begun(Trace trace) {
        if ("test".equals(trace.kind())) return;
        RUNNING.incrementAndGet();
        BEGUN.incrementAndGet();
    }

    static void ended(Trace trace) {
        if (!"test".equals(trace.kind())) RUNNING.decrementAndGet();
    }

    /**
     * When the JVM started, in milliseconds since the epoch: a collection's start is told from it. The JVM's uptime
     * runs ahead of that clock by its startup; the wall clock agrees with it to a millisecond or two.
     */
    private static final long JVM_START_MS = jvmStart();

    /** The slack a collection's start, told to the millisecond, may say it began before a trace that ran it. */
    private static final long SLACK_MS = 2;

    private static long jvmStart() {
        try {
            return RUNTIME.getStartTime();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** The heap's level now: what its objects take, garbage included until collected. */
    static long heapLevel() {
        try {
            return HEAP.getHeapMemoryUsage().getUsed();
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /** What `thread` has allocated in all, or -1 where the JVM can't tell. */
    private static long allocatedBy(Thread thread) {
        if (THREADS == null) return -1;
        try {
            return thread == Thread.currentThread()
                    ? THREADS.getCurrentThreadAllocatedBytes()
                    : THREADS.getThreadAllocatedBytes(thread.getId());
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /** A trace's memory while it runs: where the heap stood at its start, its peak since, its threads' allocations. */
    static final class Window {
        private final long startMs = System.currentTimeMillis();
        private final long level = heapLevel();
        private final AtomicLong peak = new AtomicLong(level);
        private final boolean alone = RUNNING.get() <= 1;
        private final long begun = BEGUN.get();
        // Each thread running the trace now, with what it had allocated when it began to.
        private final Map<Thread, Long> on = new ConcurrentHashMap<>();
        private final AtomicLong allocated = new AtomicLong();
        private volatile boolean uncounted = THREADS == null;

        Window() {
            if (level >= 0) watch(this);
        }

        /** The trace runs on `thread` from now on. */
        void enter(Thread thread) {
            long now = allocatedBy(thread);
            if (now < 0) uncounted = true;
            else on.putIfAbsent(thread, now);
        }

        /** The trace left `thread`: what it allocated meanwhile is the trace's. */
        void leave(Thread thread) {
            Long from = on.remove(thread);
            if (from == null) return;
            long now = allocatedBy(thread);
            if (now < 0) uncounted = true;
            else allocated.addAndGet(Math.max(now - from, 0));
        }

        void raise(long level) {
            peak.accumulateAndGet(level, Math::max);
        }

        /** The heap's level before a collection that began at `atMs`: the trace's when it began after it. */
        void raiseBefore(long level, long atMs) {
            if (atMs + SLACK_MS >= startMs) raise(level);
        }

        /** The trace ended: its `memory`, or null when the heap can't be read. */
        Map<String, Object> end() {
            for (Thread thread : List.copyOf(on.keySet())) leave(thread);
            WINDOWS.remove(this);
            long now = heapLevel();
            if (level < 0 || now < 0) return null;
            raise(now);
            Map<String, Object> memory = new LinkedHashMap<>();
            memory.put("peak", Math.max(peak.get() - level, 0));
            memory.put("net", now - level);
            if (!uncounted) memory.put("allocated", allocated.get());
            memory.put("shared", !alone || BEGUN.get() != begun || RUNNING.get() > 1);
            return memory;
        }
    }

    // The windows open now, raised by every heap read and every collection's level before it.
    private static final Set<Window> WINDOWS = ConcurrentHashMap.newKeySet();

    private static void watch(Window window) {
        WINDOWS.add(window);
        listen();
    }

    /** Whether a trace's memory is measured now: the profiler's thread reads the heap for it. */
    static boolean measuring() {
        return !WINDOWS.isEmpty();
    }

    /** The heap's level read now, for the peaks of the traces measured. */
    static void sampleHeap() {
        if (WINDOWS.isEmpty()) return;
        long level = heapLevel();
        if (level < 0) return;
        for (Window window : WINDOWS) window.raise(level);
    }

    /** One collector's counts as a trace began: how many it had run and their time. */
    private record Collector(GarbageCollectorMXBean bean, long count, long timeMs) {}

    /** The collectors while a trace runs: their counts at its start, and the pauses told meanwhile. */
    static final class Gc {
        // Watched from now (µs since the epoch): a trace the app's code profiles halfway has the collections since.
        private final long startMicros;
        private final long sinceMicros = Trace.nowMicros();
        private final List<Collector> collectors = new ArrayList<>();
        private final List<long[]> pauses = new ArrayList<>();

        Gc(long startMicros) {
            this.startMicros = startMicros;
            for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
                collectors.add(new Collector(bean, bean.getCollectionCount(), bean.getCollectionTime()));
            }
            WATCHED.add(this);
            listen();
        }

        /** A pause that began at `atMs` and lasted `micros`: the trace's when it began since it was watched. */
        private synchronized void paused(long atMs, long micros, int generation) {
            if ((atMs + SLACK_MS) * 1000 < sinceMicros || pauses.size() >= MAX_PAUSES) return;
            pauses.add(new long[] {Math.max(atMs * 1000 - startMicros, 0), micros, generation});
        }

        /** The trace ended: its `gc`. */
        Map<String, Object> end() {
            WATCHED.remove(this);
            long collections = 0;
            long paused = 0;
            long pauseMs = 0;
            for (Collector collector : collectors) {
                String name = collector.bean().getName();
                long count = Math.max(collector.bean().getCollectionCount() - collector.count(), 0);
                long time = Math.max(collector.bean().getCollectionTime() - collector.timeMs(), 0);
                // A concurrent collector's cycles are its collections, its pauses the time it stood the program still.
                if (!name.endsWith("Pauses")) collections += count;
                if (!name.endsWith("Cycles")) {
                    paused += count;
                    pauseMs += time;
                }
            }
            List<List<Long>> told = new ArrayList<>();
            long toldMicros = 0;
            synchronized (this) {
                for (long[] pause : pauses) {
                    told.add(List.of(pause[0], pause[1], pause[2]));
                    toldMicros += pause[1];
                }
            }
            Map<String, Object> gc = new LinkedHashMap<>();
            gc.put("collections", collections);
            // Each pause told: their time is the pauses' the console draws (the counts round each to its own ms).
            gc.put("pause_us", told.size() == paused ? toldMicros : pauseMs * 1000);
            gc.put("pauses", told);
            return gc;
        }
    }

    /** Listens to the collectors' notifications once: each collection's pause and the heap's level before it. */
    private static void listen() {
        if (listening) return;
        synchronized (Memory.class) {
            if (listening) return;
            listening = true;
            try {
                Set<String> heapPools = new HashSet<>();
                for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
                    if (pool.getType() == MemoryType.HEAP) heapPools.add(pool.getName());
                }
                for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
                    if (bean instanceof NotificationEmitter emitter) {
                        emitter.addNotificationListener(
                                (notification, handback) -> collected(notification, heapPools), null, null);
                    }
                }
            } catch (RuntimeException | LinkageError e) {
                // A JVM that doesn't tell its collections: the counts still do.
            }
        }
    }

    private static void collected(Notification notification, Set<String> heapPools) {
        if (!GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION.equals(notification.getType())) return;
        if (WATCHED.isEmpty() && WINDOWS.isEmpty()) return;
        try {
            GarbageCollectionNotificationInfo info =
                    GarbageCollectionNotificationInfo.from((CompositeData) notification.getUserData());
            GcInfo gc = info.getGcInfo();
            long before = 0;
            for (Map.Entry<String, MemoryUsage> pool : gc.getMemoryUsageBeforeGc().entrySet()) {
                if (heapPools.contains(pool.getKey())) before += pool.getValue().getUsed();
            }
            long atMs = JVM_START_MS + gc.getStartTime();
            for (Window window : WINDOWS) window.raiseBefore(before, atMs);
            // A concurrent collector's cycle isn't a pause: its own pauses are told apart.
            if (info.getGcName().endsWith("Cycles")) return;
            int generation = info.getGcAction().contains("minor") ? 0 : 2;
            for (Gc watched : WATCHED) watched.paused(atMs, gc.getDuration() * 1000, generation);
        } catch (RuntimeException e) {
            // Not a collection this JVM tells as HotSpot does.
        }
    }
}
