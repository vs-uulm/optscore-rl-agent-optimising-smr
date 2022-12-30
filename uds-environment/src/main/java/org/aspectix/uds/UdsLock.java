/*
  LibUDS: Copyright 2020 Institute of Distributed Systems, Ulm University, Germany

  Licensed under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License.
  You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
*/
package org.aspectix.uds;

import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Represents a UDS-aware Lock. If a thread requests/takes this lock, a
 * UDS-imposed total order will be obeyed.
 */
public class UdsLock implements Lock {
    /**
     * Scheduler for this lock
     */
    private UdsScheduler scheduler;

    /**
     * Identifier of this Lock
     */
    private String lockId;

    /**
     * The thread currently holding this UDSLock, null if the lock is currently
     * free.
     */
    private UdsScheduler.UdsThread owner = null;

    /**
     * Number of locks acquired by the current owner thread
     */
    private int lockCount;

    /**
     * The per-mutex wait queue for threads which requested this Lock but have not
     * yet acquired it. May get cleared, e.g. at the end of a round.
     */
    private List<UdsScheduler.UdsThread> enqueuedThreads = new LinkedList<>();

    /**
     * Global ID counter for UDS locks
     */
    private static AtomicInteger nextId = new AtomicInteger(-2);

    /**
     * Logging
     */
    private static final Logger logger = Logger.getLogger(UdsLock.class.getName());

    /**
     * Creates a basic UdsLock using the scheduler of the creating UDS thread
     * 
     * @param id The ID of the new lock
     */
    public UdsLock() {
        this(UdsScheduler.getCurrentUdsThread() == null ? null : UdsScheduler.getCurrentUdsThread().getUdsScheduler());
    }

    /**
     * Creates a basic UdsLock with a given scheduler
     * 
     * @param id    The ID of the new lock
     * @param sched The UDS scheduler to use
     */
    public UdsLock(UdsScheduler sched) {
        scheduler = sched;
        lockId = "<" + (sched == null ? "unknown" : sched.id) + ":L" + nextId.incrementAndGet() + ">";

        if (logger.isLoggable(Level.FINER)) {
            logger.finer(UdsScheduler.getThreadID() + " created UDS lock " + lockId);
        }
    }

    /**
     * Obeys total order before granting a thread the lock
     */
    @Override
    public void lock() {
        UdsScheduler.UdsThread t = UdsScheduler.getCurrentUdsThread();
        String prefix = UdsScheduler.getThreadID() + " UdsLock.lock " + lockId + ": ";

        if (logger.isLoggable(Level.FINE)) {
            logger.finer(prefix);
        }

        if (scheduler == null) {
            if (t != null) {
                // finally get a scheduler: take the one of current thread
                scheduler = t.getUdsScheduler();
                lockId = lockId.replace("unknown", scheduler.id);
                prefix = prefix.replace("unknown", scheduler.id);
            } else {
                // no scheduler
                logger.severe(prefix + "not a UDS thread");
                throw new IllegalCallerException("Caller is not a UDS thread: " + lockId);
            }
        }
        if (t == null || t.getUdsScheduler() != scheduler) {
            // we have the wrong scheduler here
            logger.severe(prefix + "thread belongs to no or wrong scheduler");
            throw new IllegalCallerException("Thread belongs to different scheduler, locking: " + lockId);
        }

        scheduler.schedulerLock.lock();
        try {
            if (owner == t) {
                // we already have this lock
                lockCount++;
                if (logger.isLoggable(Level.FINER)) {
                    logger.finer(prefix + "took lock again, count=" + lockCount);
                }
                return;
            }

            // obey total order. Thread might get parked in UDScheduler conditions, but will
            // eventually continue here.
            scheduler.waitForTurn();

            for (int loop = 0; owner != t; loop++) {
                if (logger.isLoggable(Level.FINEST)) {
                    logger.finest(
                            prefix + "found lock with owner " + (owner != null ? owner.getIdString() : "'nobody'"));
                }
                if (owner != null) {
                    // if UDSLock is already taken, enqueue current thread and let it wait
                    if (logger.isLoggable(Level.FINE)) {
                        logger.finer(prefix + "enqueues itself at lock");
                    }
                    enqueuedThreads.add(t);
                    t.setEnqueued(this);

                    // check if round is over, else continue by waiting until thread is first in
                    // mutex wait queue
                    scheduler.checkForEndOfRound();
                    while (owner != t && t.getEnqueued() != null) {
                        if (logger.isLoggable(Level.FINER)) {
                            logger.finer(prefix + "goes to sleep, waiting for being dequeued");
                        }
                        t.awaitDequeueing();
                        if (logger.isLoggable(Level.FINER)) {
                            logger.finer(prefix + "was woken up, signalling that thread was dequeued");
                        }
                    }

                    if (logger.isLoggable(Level.FINER)) {
                        if (owner != t) {
                            // repeat (see UDS v1.2.1 spec line 28)
                            // unlock first, then
                            logger.finer(prefix + "tried locking, but wasn't right owner. Current owner is "
                                    + (owner == null ? "'nobody'" : owner.getIdString()) + ". Trying again...");
                        } else {
                            // got it
                            logger.finer(prefix + "took lock from previous owner");
                        }
                    }
                } else {
                    // take this UDSLock
                    if (logger.isLoggable(Level.FINER)) {
                        logger.finer(prefix + "took lock");
                    }
                    owner = t;
                    lockCount = 1;
                    t.myLocks.add(this);
                }
                scheduler.setProgress();

                if (loop > 0) {
                    logger.severe(prefix + "looping to get a lock: " + loop);
                }
            }
        } catch (Throwable e) {
            e.printStackTrace();
            logger.info(prefix + "has been interrupted while waiting on awaitDequeuing"); // TODO: interrupt handling
                                                                                          // must be fixed for
                                                                                          // suspend/shutdown
        } finally {
            scheduler.schedulerLock.unlock();
        }
    }

    /**
     * Unlocks this UdsLock and wakes up enqueued threads if applicable, so they can
     * try to acquire the lock while obeying the total order.
     */
    @Override
    public void unlock() {
        UdsScheduler.UdsThread t = UdsScheduler.getCurrentUdsThread();
        String prefix = UdsScheduler.getThreadID() + " UdsLock.unlock " + lockId + ": ";

        logger.finer(prefix);

        if (t == null || t.getUdsScheduler() != scheduler) {
            // We have the wrong scheduler here
            logger.severe(prefix + "thread belongs to no or wrong scheduler");
            throw new IllegalMonitorStateException(
                    prefix + "tried to release " + lockId + " even though it belongs to different scheduler");
        }

        scheduler.schedulerLock.lock();
        try {
            if (t != owner) {
                // a thread not currently owning the mutex tried to unlock it. Return and ignore
                // the request.
                throw new IllegalMonitorStateException(
                        "Thread tried to release even though it wasn't the owner of lock " + lockId);
            }

            if (lockCount > 1) {
                // we locked multiple times
                lockCount--;
                if (logger.isLoggable(Level.FINER)) {
                    logger.finer(prefix + "released lock, count=" + lockCount);
                }
                return;
            }
            // total release
            owner = null;
            t.myLocks.remove(this);

            // see if other threads are waiting for this UDSLock and grant it to the first
            // thread in queue
            if (enqueuedThreads.size() > 0) {
                if (logger.isLoggable(Level.FINEST)) {
                    logger.finest(prefix + "checks enqueuedThreads and waking up first");
                }
                UdsScheduler.UdsThread next = enqueuedThreads.get(0);
                owner = next;
                lockCount = 1;
                next.myLocks.add(this);

                // signal the one thread that next will run due to changed condition
                if (logger.isLoggable(Level.FINE)) {
                    logger.finer(prefix + "signaling dequeued thread " + owner.getIdString());
                }
                next.dequeueThread();
            }

            logger.finest(prefix + "released lock; new owner is "
                        + (owner != null ? owner.getIdString() : "'nobody'"));
        } finally {
            scheduler.schedulerLock.unlock();
        }
    }

    /**
     * Not implemented for UDSLock. Do not use. Use lock() instead.
     * 
     * @throws InterruptedException The known exception
     */
    @Override
    public void lockInterruptibly() throws InterruptedException {
        throw new NoSuchMethodError();
    }

    /**
     * Not implemented for UdsLock. Do not use. Use lock() instead.
     * 
     * @throws NoSuchMethodError The known exception
     */
    @Override
    public boolean tryLock() {
        throw new NoSuchMethodError();
    }

    /**
     * Not implemented for UdsLock. Do not use. Use lock() instead.
     * 
     * @param time Waiting time
     * @param unit Waiting time unit
     * @return Is the lock taken?
     * @throws NoSuchMethodError The known exception
     */
    @Override
    public boolean tryLock(long time, TimeUnit unit) throws InterruptedException {
        throw new NoSuchMethodError();
    }

    /**
     * Not implemented for UdsLock. Do not use.
     */
    @Override
    public Condition newCondition() {
        throw new NoSuchMethodError();
    }

    /**
     * Remove a thread from the queue of this lock
     * 
     * @param t The thread to be removed
     */
    void removeFromQueue(UdsScheduler.UdsThread t) {
        if (logger.isLoggable(Level.FINE)) {
            logger.finer(UdsScheduler.getThreadID() + " removeFromQueue: is removing " + t.getIdString()
                    + " from queue of lock " + lockId);
        }
        while (enqueuedThreads.remove(t)) {
        }
    }

    /**
     * ToString
     */
    @Override
    public String toString() {
        return "UdsLock:" + lockId;
    }
}
