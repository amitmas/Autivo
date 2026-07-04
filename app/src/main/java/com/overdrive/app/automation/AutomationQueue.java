package com.overdrive.app.automation;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;

public class AutomationQueue {
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
    private static Thread automationWorker = null;

    private AutomationQueue() {}

    /**
     * Check whether the worker should currently be running
     * If there are no running automations, then there is no need for a worker to be running
     */
    public static void checkWorkerState() {
        if (Automations.isDisabled()) {
            if (automationWorker != null) {
                automationWorker.interrupt();
                automationWorker = null;
                automationQueue.clear();
            }
        } else {
            if (automationWorker == null) {
                automationWorker = new Thread(() -> {
                    try {
                        while (true) {
                            DelayedAutomation item = automationQueue.take();
                            queueItems.remove(item.getId());
                            // Ensure the conditions are checked so if they are no longer valid, the automation won't run
                            Automations.triggerActions(item.getId(), true);
                        }
                    } catch (InterruptedException ignored) {
                    }
                });

                // Allow application to exit even if there are still events left in the queue
                automationWorker.setDaemon(true);
                automationWorker.start();
            }
        }
    }

    /**
     * Remove an item from the queue before it has been actioned
     * This allows items to be removed which no longer meet some conditions
     * The lookup is done from a set to improve performance when the queue becomes large
     *
     * @param id The id of the item to remove from the queue if it exists
     */
    public static void removeFromQueue(String id) {
        if (id == null || !queueItems.contains(id)) return;
        automationQueue.removeIf(delayedAutomation -> id.equals(delayedAutomation.getId()));
        queueItems.remove(id);
    }

    /**
     * Add an item to the queue to be actioned after the delay
     *
     * @param id    The id of the automation to add to the queue
     * @param delay The delay in seconds before the actions can run
     */
    public static void addToQueue(String id, int delay) {
        if (id == null || queueItems.contains(id)) return;
        automationQueue.add(new DelayedAutomation(id, delay));
        queueItems.add(id);
    }
}
