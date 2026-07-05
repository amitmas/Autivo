package com.overdrive.app.automation;

import com.overdrive.app.logging.DaemonLogger;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;

public class AutomationQueue {
    private static final DaemonLogger logger = DaemonLogger.getInstance("Automations");

    private static class DelayedAutomation implements Delayed {
        private final String id;
        private final long startTime;

        /**
         * A queue with a delay for items
         * The delay is stored using System.nanoTime to avoid issues with timezones
         *
         * @param id    The id of an automation which will be run
         * @param delay The time in seconds to delay that actions of the automation
         */
        public DelayedAutomation(String id, int delay) {
            this.id = id;
            this.startTime = System.nanoTime() + TimeUnit.SECONDS.toNanos(delay);
        }

        /**
         * The stored id for an automation
         *
         * @return The stored id for an automation
         */
        public String getId() {
            return id;
        }

        /**
         * Override the delay method to check the time left until this item can be actioned
         *
         * @param unit the time unit
         * @return The time left for this item
         */
        @Override
        public long getDelay(TimeUnit unit) {
            return unit.convert(startTime - System.nanoTime(), TimeUnit.NANOSECONDS);
        }

        /**
         * Override the compareTo method so this queue can be sorted
         *
         * @param o the object to be compared.
         * @return An integer representing whether this item should be before or after the other item
         */
        @Override
        public int compareTo(Delayed o) {
            return Long.compare(getDelay(TimeUnit.NANOSECONDS), o.getDelay(TimeUnit.NANOSECONDS));
        }
    }

    // A DelayQueue which is thread safe and will return items after a delay
    private static final DelayQueue<DelayedAutomation> automationQueue = new DelayQueue<>();
    // A set to store the currently queued items for an O(1) lookup to see if an item already exists
    private static final Set<String> queueItems = ConcurrentHashMap.newKeySet();
    // Locking discipline: every mutation of the (worker, queue, queueItems) triad is performed while
    // holding this single monitor so that the DelayQueue and its queueItems shadow-set never diverge
    // and at most one worker thread can ever exist. The ONLY thing that must NOT run under the lock is
    // the worker's blocking automationQueue.take() — holding a lock across an unbounded blocking call
    // would deadlock every HTTP thread that needs to add/remove/toggle. The worker therefore takes()
    // outside the lock and re-acquires the lock only for the brief queueItems bookkeeping right after.
    private static final Object lock = new Object();
    // volatile so the un-synchronized reads inside the worker loop (and any future readers) observe
    // the latest reference; all writes still happen under `lock`.
    private static volatile Thread automationWorker = null;

    private AutomationQueue() {}

    /**
     * Check whether the worker should currently be running
     * If there are no running automations, then there is no need for a worker to be running
     * Synchronized on {@link #lock} so two concurrent HTTP threads cannot both observe a null worker
     * and start two threads (which would double-fire actions), and so a disable-all cannot orphan a
     * live worker while another thread is mid-start.
     */
    public static void checkWorkerState() {
        synchronized (lock) {
            if (Automations.isDisabled()) {
                if (automationWorker != null) {
                    automationWorker.interrupt();
                    automationWorker = null;
                    automationQueue.clear();
                    queueItems.clear();
                }
            } else {
                ensureWorker();
            }
        }
    }

    /**
     * Start the single drainer thread if one is not already running.
     * <p>
     * MUST be called while holding {@link #lock}. Extracted so both the enable path
     * ({@link #checkWorkerState()}) and every enqueue ({@link #addToQueue}) can guarantee a drainer
     * exists. Guaranteeing it from {@code addToQueue} is what makes the queue self-healing across a
     * daemon restart: on restart {@code Automations} reloads persisted automations from disk but no
     * API mutation runs, so {@code checkWorkerState()} is never called — yet the first triggered
     * automation still enqueues, and that enqueue now spins up the worker to drain it. Kept lazy (no
     * worker until something is actually queued) so an idle-but-enabled feature costs only a parked
     * thread, and a feature with zero automations costs nothing.
     */
    private static void ensureWorker() {
        if (automationWorker != null) return;
        // Never spin up a drainer for a disabled/empty feature. Closes the race where a disable-all tears
        // the worker down while an addToQueue is blocked on the lock, then the add re-spawns an orphan
        // worker that outlives the disable. (A queued item under isDisabled would be a no-op anyway: the
        // triggerActions isDisabled guard drops it.)
        if (Automations.isDisabled()) return;
        automationWorker = new Thread(() -> {
            try {
                while (true) {
                    // take() blocks until an item is due; it MUST stay outside the lock.
                    DelayedAutomation item = automationQueue.take();
                    // Re-acquire the lock only for the brief set bookkeeping so the
                    // take()->remove window is closed against a concurrent addToQueue
                    // (which skips ids already in queueItems). Release before triggering.
                    synchronized (lock) {
                        queueItems.remove(item.getId());
                    }
                    // Ensure the conditions are checked so if they are no longer valid, the automation won't run.
                    // Wrap in catch(Throwable) so a single misbehaving action (RuntimeException/Error from a HAL
                    // call, or a hand-edited persisted config that slipped past API validation) can never kill the
                    // singleton drainer — otherwise every future automation would silently stop until daemon restart.
                    try {
                        Automations.triggerActions(item.getId(), true);
                    } catch (Throwable t) {
                        logger.error("Automation action threw, continuing drainer: " + item.getId());
                    }
                }
            } catch (InterruptedException ignored) {
            }
        });

        // Allow application to exit even if there are still events left in the queue
        automationWorker.setDaemon(true);
        automationWorker.start();
    }

    /**
     * Remove an item from the queue before it has been actioned
     * This allows items to be removed which no longer meet some conditions
     * The lookup is done from a set to improve performance when the queue becomes large
     * Synchronized on {@link #lock} so the DelayQueue and its queueItems shadow-set stay consistent.
     *
     * @param id The id of the item to remove from the queue if it exists
     */
    public static void removeFromQueue(String id) {
        if (id == null) return;
        synchronized (lock) {
            if (!queueItems.contains(id)) return;
            automationQueue.removeIf(delayedAutomation -> id.equals(delayedAutomation.getId()));
            queueItems.remove(id);
        }
    }

    /**
     * Add an item to the queue to be actioned after the delay
     * Synchronized on {@link #lock} so the DelayQueue and its queueItems shadow-set stay consistent
     * and a state change that arrives during the worker's take()->remove window is not silently lost.
     *
     * @param id    The id of the automation to add to the queue
     * @param delay The delay in seconds before the actions can run
     */
    public static void addToQueue(String id, int delay) {
        if (id == null) return;
        synchronized (lock) {
            if (queueItems.contains(id)) return;
            automationQueue.add(new DelayedAutomation(id, delay));
            queueItems.add(id);
            // Guarantee a drainer for what we just enqueued. Covers the post-restart path where no
            // API mutation (and thus no checkWorkerState()) has run since the automations reloaded.
            ensureWorker();
        }
    }
}
