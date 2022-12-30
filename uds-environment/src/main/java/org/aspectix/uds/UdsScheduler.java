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

import org.aspectix.selfoptim.stepped.RequestType;
import org.aspectix.selfoptim.stepped.SteppedRunnable;
import org.aspectix.selfoptim.stepped.SteppendOnDemandLoadgenerator;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * UDS Scheduler object allows you to submit tasks that are deterministically
 * scheduled within a single scheduler.
 * 
 * The scheduler is in one of four modes: In 'normal' operation it accepts
 * submitted tasks and immediately schedules them in a deterministic way. These
 * tasks have to use UdsLocks to protect shared data. No synchronized methods or
 * blocks are allowed. No indeterministic functions must be called. In
 * 'suspending' and 'suspend' mode, the scheduler accepts further tasks, but
 * will not schedule them. In 'suspending' mode, the scheduler waits until all
 * tasks have left the scheduler, which changes the mode to 'suspended'. The
 * scheduler can immediately resume to 'normal' mode, which admits all waiting
 * tasks in the defined order. In mode 'shuttingDown' the scheduler also waits
 * until all tasks have left the system, and then shuts itself down, cleaning up
 * all related resources. Shutting down can be enforced. In this case, the
 * scheduler interrupts all threads and tries to get them out as quick as
 * possible.
 * 
 * The scheduling itself is solely done by UDS threads. The first thread who
 * wants to execute a critical operation will start the whole scheduling process
 * (after initialisation or on resume), but it will only execute scheduling code
 * until itself is able to make progress. Then only threads will execute on
 * behalf of scheduling that have to wait for their turn anyway or already have
 * terminated. Thus scheduling overhead does hardly count on the raw execution
 * time of a thread.
 * 
 * @author Gerhard Habiger
 * @author Franz J. Hauck
 */
public class UdsScheduler {
    /**
     * Ordered list of all threads to be scheduled by this UDS instance. Protected
     * by {@link UdsScheduler#schedulerLock}.
     */
    protected final List<UdsThread> allThreads;

    /**
     * Ordered list of stand-by threads waiting to be scheduled
     */
    protected List<UdsThread> standByThreads;

    /**
     * Primary threads in the current round
     */
    protected List<UdsThread> primaries;

    /**
     * Current round, incremented when a new round is started
     */
    protected int round;

    /**
     * The number of threads that have already been 'seen' by UDS
     */
    protected int highestThreadNo;

    /**
     * The next UDS thread ID
     */
    protected AtomicInteger threadId;

    /**
     * Indicator whether any progress was made in the current round
     */
    protected boolean progress;

    /**
     * Indicator for the scheduler's current state
     */
    protected UdsState state;

    /**
     * State change in progress
     */
    protected boolean stateChangeInProgress;

    /**
     * The total number of threads that have been added to UDS since it started
     * scheduling
     */
    protected long scheduledThreads;

    /**
     * The total number of threads that have been scheduled with UDS that have
     * terminated themselves (= completed)
     */
    protected long terminatedThreads;

    /**
     * Thread-local variable referring to current UDS thread
     */
    protected static final ThreadLocal<UdsThread> currentUdsThread = new ThreadLocal<>();

    /**
     * Current UDS configuration
     */
    protected UdsConfiguration currentConfig;

    /**
     * Current primary number
     */
    protected int primaryNumber = 1;

    /**
     * Current total order list
     */
    protected List<Integer> totalOrder;

    /**
     * Requested UDS configuration, effective for next round
     */
    protected UdsConfiguration requestedConfig = null;

    /**
     * Index within a round to acquire primaries
     */
    private int threadIndex;

    /**
     * Configuration lock
     */
    protected UdsLock configLock = new UdsLock(this);

    /**
     * Thread pool for request scheduling
     */
    protected final ExecutorService udsThreadPool = Executors.newCachedThreadPool();

    /**
     * Use ReentrantLock so we have access to its Condition Objects and can
     * await/signal threads efficiently.
     */
    protected final ReentrantLock schedulerLock = new ReentrantLock();

    /*
     * Global wait conditions for UDS Thread-local conditions are managed within
     * UDSThread
     */
    protected final Condition isFinishedCondition = schedulerLock.newCondition();
    protected final Condition threadExistsCondition = schedulerLock.newCondition();
    protected final Condition admissionEnabledCondition = schedulerLock.newCondition();
    protected final Condition shutdownCondition = schedulerLock.newCondition();

    // variables for stepped UDS for use with rl-uds-optimizer training
    protected final Callable<Integer> stepHandler;
    protected org.aspectix.selfoptim.stepped.SteppendOnDemandLoadgenerator steppedThreadGenerator;
    private ArrayList<UdsThread> previousRoundPrimaries;
    private long previousRoundTerminatedThreads;
    private int previousRoundStepsConsumed;
    private ArrayList<UdsThread> previousRoundAllThreads;
    private long previousRoundStartedNs = 0L;
    private long previousRoundDurationNs = 0L;
    private long previousRoundCPUTimeUsedNs = 0L;
    private int previousRoundSODLActiveClients = 0;
    private Deque<UDSRLObservedRound> previousRoundsUDSRLObservations = new ArrayDeque<>(3);
    private int roundsSinceLastConfigChange = 0;

    // Initialize an offset that can be used to impart some wallclock relation to System.nanoTime() calls in this JVM
    public static final long BENCHMARK_NANOTIME_OFFSET = (System.currentTimeMillis() * 1000000) - System.nanoTime();

    /**
     * Scheduler ID
     */
    protected String id;

    /**
     * Left over threads when going shutdown
     */
    private int leftOvers = 0;

    /**
     * Logging
     */
    protected static final Logger logger = Logger.getLogger(UdsScheduler.class.getName());

    /**
     * The UDS Scheduler object for handling a deterministic set of threads.
     * 
     * @param id Scheduler ID used for logging and debugging, defaults to '@'
     * @param uc UDS configuration object to start with
     */
    public UdsScheduler(String id, UdsConfiguration uc, Callable<Integer> stepHandler,
                        org.aspectix.selfoptim.stepped.SteppendOnDemandLoadgenerator steppedThreadGenerator) {
        schedulerLock.lock();
        this.id = (id == null ? "@" : id);
        state = UdsState.NORMAL;
        stateChangeInProgress = false;
        allThreads = new LinkedList<>();
        standByThreads = new LinkedList<>();
        primaries = new ArrayList<>(64);
        threadId = new AtomicInteger(0);
        totalOrder = new ArrayList<Integer>(64);
        round = 0;
        scheduledThreads = 0;
        terminatedThreads = 0;
        if (uc == null)
            uc = new UdsConfiguration(1, 1, 200);
        requestedConfig = uc;
        progress = true;
        this.stepHandler = stepHandler;
        this.steppedThreadGenerator = steppedThreadGenerator;
        this.previousRoundPrimaries = new ArrayList<>(8);
        startRound();
        schedulerLock.unlock();

        for(int i = 0; i < 3; i++) {
            this.previousRoundsUDSRLObservations.add(new UDSRLObservedRound(this.id, 0, new ArrayList<>(),
                    new ArrayList<>(), 0, 0, 1,
                    1,
                    this.currentConfig, 0));
        }

    }

    /**
     * The UDS Scheduler object for handling a deterministic set of threads.
     * 
     * @param id Scheduler ID used for logging and debugging, defaults to '@'
     */
    public UdsScheduler(String id) {
        this(id, null, new Callable<Integer>() {
            @Override
            public Integer call() throws Exception {
                // default to 1 primary if this constructor is called in a stepped environment (which shouldn't happen)
                return 1;
            }
        }, null);
    }

    /**
     * The UDS Scheduler object for handling a deterministic set of threads. Name
     * defaults to '@'.
     */
    public UdsScheduler() {
        this(null);
    }

    /**
     * Adds a task (= Runnable) to the UDS scheduling queue. Threads are started by
     * this method, and then scheduled with UDS. This method must not be called
     * concurrently with itself or with other public methods of the scheduler.
     *
     * @param runDet Runnable for deterministic execution, e.g. fulfilling a client
     *               request
     */
    synchronized public void submitTask(Runnable runDet) {
        submitTask(runDet, null);
    }

    synchronized public void submitTask(Runnable runDet, Runnable runUndet) {
        submitTask(runDet, runUndet, org.aspectix.selfoptim.stepped.RequestType.UNDEFINED);
    }

    /**
     * Adds a task (= Runnable) to the UDS scheduling queue. Threads are started by
     * this method, and then scheduled with UDS. This method must not be called
     * concurrently with itself or with other public methods of the scheduler. There
     * are two runnables, the first runs determinitistically. After the first, a
     * second runnable is non-deterministically run outside of the scheduler, e.g.
     * for cleanup or response processing.
     *
     * @param runDet   Runnable for deterministic execution, e.g. fulfilling a
     *                 client request
     * @param runUndet Runnable for undeterministic execution after runDet has
     *                 finished, e.g. response processing
     */
    public void submitTask(Runnable runDet, Runnable runUndet, org.aspectix.selfoptim.stepped.RequestType type) {
        String prefix = getThreadID() + " submitTask: ";

        if (logger.isLoggable(Level.FINE)) {
            logger.finer(prefix);
        }

        // check for UDS thread
        if (currentUdsThread.get() != null) {
            throw new IllegalCallerException("UDS threads are not allowed to call submitTask");
        }

        // take schedulerLock to guard against another thread messing with the
        // thread queue while we add and start the new one
        schedulerLock.lock();
        try {
            // Check for shutdown
            if (state == UdsState.SHUTDOWN) {
                logger.info(prefix + "scheduler shut(ting) down, task rejected");
                throw new IllegalSchedulerStateException("Scheduler shutting down or already stopped");
            }

            if (allThreads.size() > currentConfig.maxThreads) {
                // too many threads in the system: implementing back-pressure to the outside
                // world by stopping submission
                if (logger.isLoggable(Level.FINEST)) {
                    logger.finest(prefix + "blocking due to too many threads");
                }
                while (true) {
                    try {
                        admissionEnabledCondition.await();
                        // success: we can leave
                        break;
                    } catch (InterruptedException e) {
                        if (logger.isLoggable(Level.FINEST)) {
                            logger.finest(prefix + "woken up on admissionEnabled");
                        }
                        if (state == UdsState.SHUTDOWN) {
                            if (logger.isLoggable(Level.INFO)) {
                                logger.info(prefix + "scheduler shutting down, task rejected");
                            }
                            throw new IllegalSchedulerStateException("Scheduler shutting down or already stopped");
                        }
                    }
                }
            }

            // Create UDS thread
            UdsThread thread = new UdsThread(runDet, runUndet);
            thread.requestType = type;
            String threadID = thread.getIdString();

            if (logger.isLoggable(Level.FINE)) {
                logger.finer(prefix + "thread created: " + threadID);
            }

            if (state == UdsState.NORMAL) {
                // first the thread is added to the thread list to preserve order
                if (logger.isLoggable(Level.FINEST)) {
                    logger.finest(prefix + "adding thread " + thread.getIdString() + " to UDS threads");
                }
                thread.start(StartMode.EXTERNAL);

                // signal scheduling thread waiting for threads in startRound().
                // Should be only one thread awaiting this condition, but call signalAll() just
                // to be safe.
                threadExistsCondition.signalAll();
            } else if (state == UdsState.SUSPENDED) {
                // put into standby list
                if (logger.isLoggable(Level.FINEST)) {
                    logger.finest(prefix + "adding thread " + threadID + " to stand-by threads");
                }
                standByThreads.add(thread);
            } else {
                // we should not be here
                assert (false);
            }
        } finally {
            schedulerLock.unlock();
        }
    }

    /**
     * Requests UDS scheduler to reconfigure itself to use the specified
     * configuration object.
     *
     * Can be called external from the scheduler. In this case it must not be called
     * concurrently with submitTask. A good idea is to have only a single thread who
     * is allowed to do both. Alternatively you can synchronise outside of UDS.
     *
     * Can be called by an UDS thread. Internally it is already made deterministic
     * by acquiring a lock so that there is no other synchronisation necessary.
     *
     * @param uc The new configuration object
     **/
    public void requestReconfiguration(UdsConfiguration uc) {
        String prefix = getThreadID() + " requestReconfiguration: ";
        UdsThread t = currentUdsThread.get();

        logger.finer(prefix);

        if (t == null) {
            // not an UDS thread
            submitTask(() -> requestReconfiguration(uc));
        } else if (t.getUdsScheduler() != this) {
            // wrong scheduler
            throw new IllegalCallerException("Thread belongs to different scheduler");
        } else {
            // let's reconfigure
            if (state == UdsState.SHUTDOWN || state == UdsState.FORCEDSHUTDOWN) {
                throw new IllegalSchedulerStateException("Scheduler shutting down or already stopped");
            }
            logger.fine(prefix + "new configuration (type: " + uc.getClass() + ", " + uc.primaryNumber + " " + "prims, "
                    + uc.steps + " steps per prim)");

            configLock.lock();
            schedulerLock.lock();
            try {
                if (currentConfig.deterministic != uc.deterministic)
                    throw new IllegalArgumentException("Can't switch determinism during run");
                requestedConfig = uc;
            } finally {
                schedulerLock.unlock();
                configLock.unlock();
            }
        }
    }

    /**
     * Creates explicit new UDS thread.
     * 
     * @param r The task the new thread has to execute
     */
    static public void createThread(Runnable r) {
        createThread(r, false, 0);
    }

    /**
     * Creates explicit new background thread. Only allowed to call from UDS
     * threads.
     * 
     * @param r The task the new thread has to execute
     */
    static public void createBackgroundThread(Runnable r) {
        createThread(r, true, 1);
    }

    /**
     * Creates explicit new thread with all parameters. Only allowed to call from
     * UDS threads.
     * 
     * @param r          The tasks the new thread has to execute
     * @param background True if task should be working in background
     * @param steps      Number of steps this thread should get in every round (only
     *                   considered if background thread)
     */
    static public void createThread(Runnable r, boolean background, int steps) {
        String prefix = getThreadID() + " createThread: ";
        UdsThread t = currentUdsThread.get();
        UdsScheduler us;

        logger.finer(prefix);

        if (t == null) {
            // not a UDS thread
            throw new IllegalCallerException("Non-UDS threads are not allowed to call createThread");
        } else {
            us = t.getUdsScheduler();
        }

        // create thread
        t = us.new UdsThread(r);
        t.background = background;
        t.backgroundSteps = steps;

        // add to thread list
        us.schedulerLock.lock();
        us.waitForTurn();
        t.start(background ? StartMode.BACKGROUND : StartMode.INTERNAL);
        us.schedulerLock.unlock();
    }

    /**
     * Classical yield function to give back to the scheduler. In UDS this means
     * that a step is consumed and progress in rounds can be made.
     */
    static public void yield() {
        String prefix = getThreadID() + " yield: ";
        UdsThread t = currentUdsThread.get();

        if (logger.isLoggable(Level.FINE)) {
            logger.finer(prefix);
        }

        if (t != null) {
            // UDS thread
            t.yield();
        } else {
            // no UDS thread
            throw new IllegalCallerException("Non-UDS threads are not allowed to call yield");
        }
    }

    /**
     * Shutdown the scheduler with all its threads.
     * 
     * If threads cannot be stopped as they are blocked somewhere and you did not
     * ask for shutdown now, the scheduler object stays alive and you may submit
     * more requests. Can be called anytime and concurrently.
     *
     * @return Number of threads that cannot be stopped
     */
    public int shutdown() {
        String prefix = getThreadID() + " shutdown: ";

        try {
            schedulerLock.lock();
            // Start shutdown
            if (state != UdsState.SHUTDOWN) {
                state = UdsState.SHUTDOWN;
                stateChangeInProgress = true;
                if (logger.isLoggable(Level.FINER)) {
                    logger.finer(prefix + "switching to SHUTDOWN state, in progress");
                }
            } else {
                throw new IllegalSchedulerStateException("Scheduler already down or going down");
            }

            try {
                shutdownCondition.await();
            } catch (InterruptedException e) {
                ;
            }
            return leftOvers;
        } finally {
            schedulerLock.unlock();
        }
    }

    /**
     * Forced and immediate shutdown of the scheduler with all its threads. Can be
     * called anytime and concurrently.
     * 
     * @return Number of threads that cannot be stopped TODO: Really?
     */
    public int forcedShutdown() {
        String prefix = getThreadID() + " forcedShutdown: ";

        schedulerLock.lock();
        // Start shutdown
        if (state != UdsState.FORCEDSHUTDOWN) {
            state = UdsState.FORCEDSHUTDOWN;
            stateChangeInProgress = true;
            if (logger.isLoggable(Level.FINER)) {
                logger.finer(prefix + "switching to FORCEDSHUTDOWN state, in progress");
            }
            // TODO: More to come
        } else {
            throw new IllegalSchedulerStateException("Scheduler already down or going down");
        }
        schedulerLock.unlock();
        return 0;
    }

    /**
     * Suspend scheduler and all its threads. All threads are evicted, but scheduler
     * is not shut down. Must not called concurrently with submitTask calls. If
     * scheduler is already suspended or suspending, nothing will happen.
     */
    synchronized public void suspend() {
        String prefix = getThreadID() + " suspend: ";

        schedulerLock.lock();
        switch (state) {
            case NORMAL:
                // supend everything
                state = UdsState.SUSPENDED;
                stateChangeInProgress = true;
                logger.finer(prefix + "switching to SUSPENDED state, in progress");
                break;
            case SUSPENDED:
                // nothing to do
                break;
            default:
                throw new IllegalSchedulerStateException("Current state is " + state);
        }
        schedulerLock.unlock();
    }

    /**
     * Resume scheduler from suspend state. Can be called anytime and concurrently
     * with submitTask calls if scheduler is in suspend state. If scheduler is in
     * normal state, nothing will happen.
     */
    public void resume() {
        String prefix = UdsScheduler.getThreadID() + " resume: ";

        schedulerLock.lock();
        switch (state) {
            case SUSPENDED:
                // resume operation
                state = UdsState.NORMAL;
                boolean oldStateChangeInProgress = stateChangeInProgress;
                stateChangeInProgress = false;
                logger.finer(prefix + "switching to NORMAL state");

                // start new round after resume
                if (!oldStateChangeInProgress) {
                    startRound();
                }

                // process all stand-by threads
                for (UdsThread thread : standByThreads) {
                    // first the thread is added to the thread list to preserve order
                    logger.finest(prefix + "adding thread " + thread.idString + " to UDS threads");
                    thread.start(StartMode.EXTERNAL);
                }
                standByThreads.clear();
                break;
            case NORMAL:
                // nothing to do
                break;
            default:
                throw new IllegalSchedulerStateException("Current state is " + state);
        }
        schedulerLock.unlock();
    }

    /**
     * Get statistics data out of scheduler
     * 
     * @return UDS statistics object
     */
    public UdsStats getStats() {
        try {
            schedulerLock.lock();
            return new UdsStats(round, primaryNumber, primaries.size(), scheduledThreads, terminatedThreads, state,
                    stateChangeInProgress);
        } finally {
            schedulerLock.unlock();
        }
    }

    public UdsStats getStatsNonLocking() {
        return new UdsStats(round, primaryNumber, primaries.size(), scheduledThreads, terminatedThreads, state,
                stateChangeInProgress);
    }

    //
    // Scheduling Methods
    //

    /**
     * Get current UDS Thread
     * 
     * @return Current UdsThreaD
     */
    protected static UdsThread getCurrentUdsThread() {
        return currentUdsThread.get();
    }

    /**
     * Get current thread's ID
     */
    public static String getThreadID() {
        UdsThread t = currentUdsThread.get();
        if (t == null) {
            return "{" + Thread.currentThread().getName() + "}";
        } else {
            return t.getIdString();
        }
    }

    private int estimateNumOfActiveClients() {
        int clientEstimate = 0;
        for(clientEstimate = 0; clientEstimate < previousRoundAllThreads.size(); clientEstimate++) {
            if(previousRoundAllThreads.get(clientEstimate).requestType == RequestType.BYTI) {
                break;
            }
        }
        if(clientEstimate < 1) {
            clientEstimate = 1;
        }
        if(clientEstimate > 8) {
            clientEstimate = 8;
        }
        // return clientEstimate;
        // TEST return actual activeClientCount
        return this.previousRoundSODLActiveClients;
    }

    /**
     * Calculate the reward for UDS-RL after each round
     * @return
     */
    private double calcPrevRoundRLReward() {
        double reward = 0;

        // first calculate a penalty duration if there are more than 1 ByTI in a round
        int numOfByTIInRound = 0;
        for(UdsThread t : previousRoundPrimaries) {
            if(t.requestType == RequestType.BYTI) {
                numOfByTIInRound++;
            }
        }
        if(numOfByTIInRound > 1) {
            // add a duration of 100ms per ByTI that's extra (additional to the first)
            this.previousRoundDurationNs += (numOfByTIInRound - 1) * 100000000;
        }

        // how much the termination of a thread is valued in reward calculation
        // the higher this value, the more reward the system will get for termination of threads
        // good values between 0.5-9 (approx., depending on a lot of factors)
        double terminatedThreadValue = 0.8;

        // reward is positive if the round had a good ratio of CPUTimeUsed and actual roundLength in ns
        // or if the round terminated a lot of requests
        // reward is negative if there are more than 2 ByTI in a round (this indicates that we have too many
        // primaries for the current load, in the stepped environment)
        // or if the round was "badly scheduled", i.e., took long but no CPU work was done or threads were terminated

        // ratio of roundLengthNs to cpuTimeUsedNs is used to gauge effectiveness of last round's parallelization
        // (1 - this ratio) is negative for rounds which took longer than CPU time spent, and positive otherwise
        // if cpuTimeUsed is 0, we only had ByTIs or threads that terminated (without using up actual CPU time)
        // in that case, depending on how many ByTIs we had, leave current reward at 0 or set to a high negative value
        if(previousRoundCPUTimeUsedNs == 0) {
            reward -= 100 * (numOfByTIInRound > 1 ? numOfByTIInRound - 1 : 0);
        } else {
            reward = 1 - ((double) previousRoundDurationNs / previousRoundCPUTimeUsedNs);
        }

        // this values terminated threads to offset rounds which took longer but got threads out of the system
        reward += (previousRoundTerminatedThreads * terminatedThreadValue);

        // reward is clipped to [minReward,maxReward] to avoid explosion of gradients (squaring during RL training)
        int maxReward = 1;
        int minReward = -100;
        reward = (reward < minReward) ? minReward : (reward > maxReward) ? maxReward : reward;


//        // temp reward calc with squared distance to known best option, for testing agent learning
//        double sqDistReward =
//                0 - Math.pow(this.currentConfig.primaryNumber - previousRoundSODLActiveClients, 2);
//
//        // penalize configurations where primaries are higher than clientCount
//        if(this.currentConfig.primaryNumber > previousRoundSODLActiveClients)
//            sqDistReward *= 10;

        reward = reward - this.roundsSinceLastConfigChange;

        return reward;
    }

    /**
     * Start a new scheduling round. Can only be called by scheduler methods. If a
     * reconfiguration was requested the new configuration is loaded first.
     */
    protected void startRound() {
        String prefix = getThreadID() + " startRound(" + round + "): ";
        UdsThread udsThread = currentUdsThread.get();

        schedulerLock.lock();
        try {
            logger.finest(prefix + " took schedulerLock in startRound()");

            logger.finer("Previous round duration (ns): " + this.previousRoundDurationNs);
            logger.finer("Sum of processing power used by prims in last round (ns): " + this.previousRoundCPUTimeUsedNs);
            logger.finer("Steps consumed last round: " + previousRoundStepsConsumed);

            if(udsThread != null && round > 1 && currentConfig != null && currentConfig.zSocket != null) {
                if(logger.isLoggable(Level.FINEST)) {
                    logger.finest(previousRoundPrimaries.stream().map(UdsThread::getRequestType)
                            .map(org.aspectix.selfoptim.stepped.RequestType::getRequestTypeCode)
                            .map(String::valueOf)
                            .collect(Collectors.joining(",", "Requesttype of prev. Primaries: [","]")));
                }

                // save data of previous round so it can be re-used for upcoming observations
                UDSRLObservedRound prevRoundObs = new UDSRLObservedRound(this.id, this.round,
                        (List<UdsThread>) this.previousRoundPrimaries.clone(),
                        (List<UdsThread>) this.previousRoundAllThreads.clone(),
                        this.previousRoundDurationNs, this.previousRoundCPUTimeUsedNs,
                        this.previousRoundSODLActiveClients, this.estimateNumOfActiveClients(), this.currentConfig,
                        this.roundsSinceLastConfigChange);

                // remove oldest observation from buffer, push in newest one (FIFO)
                previousRoundsUDSRLObservations.pollLast();
                previousRoundsUDSRLObservations.addFirst(prevRoundObs);

                double reward = calcPrevRoundRLReward();
                StringBuilder answer = new StringBuilder("{\"reward\":");
                answer.append(String.valueOf(reward));
                answer.append(", \"observation\":");
                answer.append(previousRoundsUDSRLObservations.stream().map(UDSRLObservedRound::getObservationAsJson)
                        .collect(Collectors.joining(",", "[", "]")));
                answer.append("}");
                logger.warning("Sending answer: " + answer.toString());
                currentConfig.zSocket.send((answer.toString()).getBytes(StandardCharsets.UTF_8), 0);
            }

            // reset round stats stuff used for UDS-RL
            previousRoundStepsConsumed = 0;

            // call the stepHandler (which might send the current thread to sleep)
            if(udsThread != null && udsThread.getUdsScheduler() == this) {
                try {
                    logger.finest(prefix + " about to call stepHandler in startRound");
                    int action = this.stepHandler.call();
                    logger.finest(prefix + " returned from stepHandler, with action " + action);

                    if(action == -1) {
                        // -1 signals shutdown of scheduler in stepped environment
                        logger.info(prefix + " shutting down current scheduler (-1 received as action)");
                        this.forcedShutdown();
                        for(UdsThread t : allThreads) {
                            t.setTerminated();
                        }
                        this.udsThreadPool.shutdownNow();
                        return;
                    }

                    // if action > 8 we want to also configure steps
                    int newPrimCount = -1;
                    int newStepCount = -1;
                    if(action > 8) {
                        newPrimCount = action - 8;
                        newStepCount = 2;
                    } else {
                        newPrimCount = action;
                        newStepCount = 1;
                    }
                    if(action != this.currentConfig.primaryNumber) {
                        this.roundsSinceLastConfigChange = 0;
                    } else {
                        this.roundsSinceLastConfigChange++;
                    }
                    // reconfigure UDS using the newly received values from the agent
                    this.requestedConfig = new UdsConfiguration(newPrimCount, newStepCount, currentConfig.maxThreads
                            , currentConfig.zSocket);
                } catch(Exception e) {
                    e.printStackTrace();
                }
            }

            // reconfigure UDS
            if (requestedConfig != null) {
                currentConfig = requestedConfig;
                requestedConfig = null;
            }

            // Fill threadlist in UDS-RL stepped environment.
            // Before filling up, we need to check how many threads are currently terminated in the threadList,
            // because if we don't, all threads that have terminated but haven't yet been pruned, will be counted now
            // towards the limit but be immediately pruned in fillPrimaries(). This would mean we could potentially have
            // too few threads in the list to continue. I.e., the system stalls. This was a great Heisenbug.
            // (since fillPrimaries() is called often and the threadList is pruned during a round, this case in
            // reality only applies to one thread being terminated: The one that checked for end of round, i.e. the
            // one currently running this code, starting this round).
            int numberOfTerminatedThreadsInList =
                    allThreads.stream().filter(UdsThread::isTerminated).collect(Collectors.toCollection(ArrayList::new)).size();
            int numberOfThreadsToFill = currentConfig.getPrimaryNumber() - allThreads.size() + numberOfTerminatedThreadsInList;
            while(this.steppedThreadGenerator != null && numberOfThreadsToFill > 0) {
                UdsThread thread;
                SteppedRunnable nextRunnable = this.steppedThreadGenerator.getNextRequest();
                thread = new UdsThread(nextRunnable);
                thread.fromClientId = nextRunnable.getFromClientId();
                thread.requestType = nextRunnable.getRequestType();
                thread.steppedRunnable = nextRunnable;
                String threadID = thread.getIdString();
                thread.start(StartMode.EXTERNAL);
                numberOfThreadsToFill--;
            }

            // reset various variables for next round's calculations
            this.previousRoundCPUTimeUsedNs = 0;
            this.previousRoundTerminatedThreads = 0;

            // snapshot of allThreads and other variables for steppedEnvironment evaluations at end of upcoming round
            this.previousRoundAllThreads = new ArrayList(allThreads);
            this.previousRoundSODLActiveClients = steppedThreadGenerator.getNumOfActiveClients();

            // considering threads that start with a critical operation, this is where a round really starts
            // measure when the round started for UDS-RL reward calculation
            this.previousRoundStartedNs = System.nanoTime() + BENCHMARK_NANOTIME_OFFSET;

            if (currentConfig.deterministic) {
                // increment round counter
                this.round++;

                // empty the set of primaries
                primaries.clear();

                primaryNumber = currentConfig.getPrimaryNumber(); // Load primary number
                currentConfig.definePrimaryOrder(totalOrder); // Define total-order list
                if (logger.isLoggable(Level.FINER)) {
                    logger.finer(prefix + totalOrder.stream().map(Object::toString).collect(
                            Collectors.joining(", ", "new total order [", "] and " + primaryNumber + " primaries")));
                }

                // TODO: if no progress was made, increase number of primaries for next round

                // load with primaries
                threadIndex = 0;
            }

            // fill with primaries and clean up old threads
            fillPrimaries();

            // special state?
            if ((state == UdsState.SUSPENDED || state == UdsState.SHUTDOWN)
                    && (!currentConfig.deterministic || primaries.size() == 0)) {
                // we are suspending or shutting down and need to check whether we succeeded
                // already
                if (allThreads.size() == 0) {
                    // no more threads
                    logger.fine(prefix + "UDS threads are all evicted");

                    if (state == UdsState.SHUTDOWN) {
                        // shutdown thread pool
                        udsThreadPool.shutdown();
                        logger.fine(prefix + "scheduler shut down");
                    }
                    stateChangeInProgress = false;
                    if (state == UdsState.SHUTDOWN) {
                        shutdownCondition.signal();
                    }
                } else if (currentConfig.deterministic) {
                    // threads left in deterministic mode
                    logger.severe(prefix + "there are threads left 🙄");
                    logger.severe(allThreads.stream().map(UdsThread::getIdString)
                            .collect(Collectors.joining(", ", prefix + "current threadList: [", "]")));
                    for (UdsThread t : allThreads) {
                        logger.severe(prefix + t.toString());
                    }
                    logger.severe(primaries.stream().map(UdsThread::getIdString)
                            .collect(Collectors.joining(", ", prefix + "current primaries: [", "]")));
                    for (UdsThread t : primaries) {
                        logger.severe(prefix + t.toString());
                    }
                    System.exit(-1); // TODO: something more useful here
                }
            }
        } catch(Exception e) {
            e.printStackTrace();
        } finally {
            schedulerLock.unlock();
            logger.finest(prefix + " unlocked schedulerLock in startRound");
        }
    }

    /**
     * Fills primaries if some are available. Can only be called by scheduler
     * methods holding the scheduler lock.
     */
    void fillPrimaries() {
        int n; // iterator over primary number
        String prefix = getThreadID() + " fillPrimaries: ";

        logger.finer(prefix);

        if (currentConfig.deterministic) {
            // before loop: threadIndex points to next thread to consider
            for (n = primaries.size(); n < primaryNumber; threadIndex++) {
                if (threadIndex >= allThreads.size()) {
                    // we have less threads than we expect primaries in this round
                    if (state == UdsState.SUSPENDED || state == UdsState.SHUTDOWN) {
                        // threads should phase out: do not wait for additional primaries
                        logger.finer(prefix + "suspend or shutdown state detected");

                        // remove all missing threads from total order
                        for (int j = n; j < primaryNumber; j++) {
                            removeFromOrder(j);
                            logger.finer(prefix + "removed primary #" + j + " from total order");
                        }
                        // correct primary number
                        primaryNumber = n;
                    }
                    // end composition of primaries
                    break;
                }

                // now we have another primary
                UdsThread t = allThreads.get(threadIndex);

                // if a thread is not already terminated or primary and the
                // selectPrimary()-predicate allows it, add thread to primaries
                if (!t.isTerminated() && !t.isPrimary() && currentConfig.selectPrimary(t, state)) {
                    logger.finer(prefix + "adding thread " + t.getIdString() + " to primaries");
                    t.setPrimary(true);
                    primaries.add(t);

                    if (logger.isLoggable(Level.FINER)) {
                        logger.finer(primaries.stream().map(UdsThread::getIdString)
                                .collect(Collectors.joining(", ", prefix + "new primaries [", "]")));
                        logger.finer(prefix + "total order now " + totalOrderToString() + ", with " + primaryNumber
                                + " primaries");
                    }
                    n++;
                }
            }
            // after loop: threadIndex points to next thread to consider

            // update the number of seen threads
            if (highestThreadNo < threadIndex - 1) {
                logger.finest(prefix + "increased highestThreadNo to " + (threadIndex - 1));
                highestThreadNo = threadIndex - 1;
            }
        }

        // prune thread list by removing all terminated threads, but only if all
        // primaries are there otherwise it might spoil threadIndex
        int removedThreads = allThreads.size();
        if ((!currentConfig.deterministic || threadIndex >= primaryNumber)
                && allThreads.removeIf(UdsThread::isTerminated)) {
            // some space for new threads

            // compute removed threads and correct number of seen threads
            removedThreads -= allThreads.size();
            highestThreadNo -= removedThreads;
            logger.finest(
                    prefix + "corrected highestThreadNo to " + highestThreadNo + " after thread garbage collection");

            // allow submitTask to continue as there could be more threads to be admitted
            admissionEnabledCondition.signalAll();
        }

        logger.fine(allThreads.stream().map(t -> {
                    return t.getIdString() + "|" + t.getSteppedRunnable().getRequestType().getRequestTypeCode();
                })
                .collect(Collectors.joining(", ", prefix + "ThreadList after pruning [", "]")));
    }

    /**
     * Checks whether the current scheduling round meets all conditions to end.
     */
    protected void checkForEndOfRound() {
        String prefix = getThreadID() + " checkForEndOfRound: ";

        logger.finer(prefix + "round " + round);

        // check all primaries whether they are finished/terminated/waiting
        try {
            schedulerLock.lock();

            if (currentConfig.deterministic) {
                // if there are currently less primaries than there should be (as per the
                // udsConfiguration for the current round), then the round is not yet over
                // and we have to wait for more primaries
                if (primaries.size() < primaryNumber) {
                    logger.finer(prefix + "did not yet see all primaries");
                    return;
                }

                for (UdsThread t : primaries) {
                    // if any primary is still running/has steps/is waiting for its turn, round is
                    // not over
                    logger.finest(prefix + t.toString());
                    if (!t.isTerminated() && t.getEnqueued() == null && !t.isFinished() && !t.isWaitingForTurn()) {
                        logger.finer(prefix + "round " + round + " not yet over");
                        return;
                    }
                }

                // end of round detected
                logger.fine(prefix + "end of round " + round + " detected. " + primaries.stream().map(UdsThread::getIdString)
                        .collect(Collectors.joining(", ", prefix + "current primaries: [", "]")));

                // measure the time this round took to complete for UDS-RL reward calculation
                this.previousRoundDurationNs = BENCHMARK_NANOTIME_OFFSET + System.nanoTime() - this.previousRoundStartedNs;

                for(UdsThread t : primaries) {
                    SteppedRunnable r = t.getSteppedRunnable();
                    this.previousRoundCPUTimeUsedNs += r.getApproxCPUTimeSpentNs();
                }

                for (UdsThread t : primaries) {
                    t.setPrimary(false);
                    t.setFinished(false);
                    t.setWaitingForTurn(false);
                    t.dequeueThread();
                    // increment roundsSeen per primary thread
                    t.roundsSeen++;
                }

                // remember this round's primary list for stepped UDS environment (RL learning reward calculation)
                this.previousRoundPrimaries = new ArrayList<>(primaries);

                // reset primaries
                primaries.clear();

                // signal waiting threads to re-check their changed conditions
                // don't signal primaries, since we cleared primaries and no thread could
                // possibly be primary
                logger.finest(prefix + "signaling threads waiting on isFinished to start new round");
                isFinishedCondition.signalAll();
            }
            // ... and now start a new round
            startRound();
        } finally {
            schedulerLock.unlock();
        }
    }

    /**
     * Called whenever total order needs to be obeyed by a UDSThread. Must only be
     * called by scheduler methods.
     */
    protected void waitForTurn() {
        UdsThread t = getCurrentUdsThread();
        String prefix = t.getIdString() + " waitForTurn: ";

        if (!currentConfig.deterministic)
            return;

        schedulerLock.lock();
        try {
            while (true) {
                // Wait until primary
                t.waitForPrimary();

                logger.finest(prefix + "checking whether there are steps left in total order " + totalOrderToString());
                if (!totalOrder.contains(primaries.indexOf(t))) {
                    // we are done in this round and have to wait for next round
                    t.setFinished(true);
                    checkForEndOfRound();
                    // wait for the signal isFinished = false, which means the next round has
                    // started and we can try again
                    while (t.isFinished()) {
                        logger.finer(prefix + "before awaiting isFinished");
                        isFinishedCondition.await();
                        logger.finer(prefix + "after woken up by signal to isFinished");
                    }
                    continue;
                } else if (totalOrder.size() > 0 && primaries.size() > totalOrder.get(0)
                        && primaries.get(totalOrder.get(0)).equals(t)) {
                    // it is our turn and we remove our step from total order
                    logger.finer(prefix + "is removing its step from the tip of the total order");
                    totalOrder.remove(0);
                    t.stepsConsumed++;
                    previousRoundStepsConsumed++;
                    logger.finer(prefix + "total order left is " + totalOrderToString());

                    // wake up next
                    if (totalOrder.size() > 0) {
                        // steps left in total order
                        if (primaries.size() > totalOrder.get(0)) {
                            // next primary is already in primary list
                            primaries.get(totalOrder.get(0)).setWaitingForTurn(false);
                        }
                        // else {
                        // we need to get more primaries
                        // fillPrimariesTo(totalOrder.get(0));
                        // }
                    }
                    break;
                } else {
                    // not our turn
                    // wait for step / being first in total order
                    t.setWaitingForTurn(true);
                    checkForEndOfRound();
                    while (t.isWaitingForTurn()) {
                        logger.finer(prefix + "going to sleep awaiting turn");
                        t.awaitTurn();
                        logger.finer(prefix + "woken up by signal to isWaitingForTurn");
                    }
                    continue;
                }
            }
        } catch (InterruptedException e) {
            logger.info(prefix + "has been interrupted while waiting. Re-waiting ...");// TODO: we actually do not 😢
        } finally {
            schedulerLock.unlock();
        }
    }

    /**
     * Removes a UDS primary thread from the current total order. Must only be
     * called by scheduler methods holding the scheduler lock.
     * 
     * @param t UDS primary thread to leave total order
     */
    protected void removeFromOrder(UdsThread t) {
        // remove all steps of t in total order
        if (logger.isLoggable(Level.FINER)) {
            logger.finer(UdsScheduler.getThreadID() + " removeFromOrder: removes all steps from " + t.getIdString());
        }
        removeFromOrder(primaries.indexOf(t));
        if (logger.isLoggable(Level.FINEST)) {
            logger.finest(UdsScheduler.getThreadID() + " removeFromOrder: new total order " + totalOrderToString());
        }
    }

    /**
     * Remove a UDS thread referred to by its primary number from the current total
     * order. Must only be called by scheduler methods holding the scheduler lock.
     * 
     * @param pn primary number of thread to be removed
     */
    protected void removeFromOrder(Integer pn) {
        while (totalOrder.remove(pn))
            ;
    }

    /**
     * Called by UDSThreads when they've finished their Runnable task and want to
     * terminate. Can only be called by the terminating thread itself.
     */
    protected void terminateThread() {
        String prefix = getThreadID() + " terminateThread: ";

        UdsThread t = currentUdsThread.get();
        if (t == null) {
            throw new IllegalCallerException("Only UDS thread can call terminateThread");
        }

        logger.finer(prefix);

        try {
            // release all locks
            logger.finer(prefix + "releasing locks");
            while (t.myLocks.size() > 0) {
                t.myLocks.get(0).unlock();
            }

            schedulerLock.lock();
            setProgress(); // TODO: progress management still flawed

            if (currentConfig.deterministic) {
                // become a primary
                logger.finest(prefix + "before waitForPrimary");
                t.waitForPrimary();

                removeFromOrder(t);

                // signal next thread in total order if there are steps left in this round
                if (totalOrder.size() > 0 && primaries.size() > totalOrder.get(0)) {
                    primaries.get(totalOrder.get(0)).setWaitingForTurn(false);
                }
            }
            // thread can be marked as terminated and retire itself
            t.setTerminated();

            // stepped UDS environment
            if(t.requestType != RequestType.BYTI) {
                this.steppedThreadGenerator.requestTerminated(t.fromClientId);
                this.previousRoundTerminatedThreads++;
                terminatedThreads++;
            }

            checkForEndOfRound();

            // nothing left to do, thread should stop itself after returning
        } finally {
            schedulerLock.unlock();
        }
    }

    public ReentrantLock getSchedulerLock() {
        return schedulerLock;
    }

    public void setSteppedThreadGenerator(SteppendOnDemandLoadgenerator steppedThreadGenerator) {
        this.steppedThreadGenerator = steppedThreadGenerator;
    }

    /**
     * Notify scheduler that there was progress
     */
    protected void setProgress() {
        this.progress = true;
    }

    /**
     * Create log string with total order
     * 
     * @return Log string containing the current total order
     */
    protected String totalOrderToString() {
        return totalOrder.stream().map(Object::toString).collect(Collectors.joining(",", "[", "]="))
                + totalOrder.stream().map((Object o) -> {
                    try {
                        return primaries.get((Integer) o).getIdString();
                    } catch (IndexOutOfBoundsException e) {
                        return "{not yet known}";
                    }
                }).collect(Collectors.joining(",", "[", "]"));
    }

    /**
     * Std toString method
     */
    public String toString() {
        return "UdsScheduler:" + id;
    }

    /**
     * UDS's own thread class to manage UDS threads A special inner class
     * representing of Thread which obeys UDS specifics. For example, it executes
     * terminate() before stopping, which waits until the Thread is in the set of
     * primaries before returning.
     */
    protected class UdsThread {
        /**
         * ID string for debugging and logging purposes
         */
        private final String idString;

        /**
         * The complete task of this thread
         */
        private Runnable task;

        /**
         * True if thread was already started by UDS
         */
        private boolean started;

        /**
         * The lock the thread is currently enqueued for waiting for lock acquisition
         */
        private UdsLock enqueued;

        /**
         * Condition for being enqueued at a lock
         */
        private final Condition enqueuedCondition;

        /**
         * List of held locks
         */
        final List<UdsLock> myLocks = new LinkedList<>();

        /**
         * True if thread is primary
         */
        private boolean primary;

        /**
         * Condition for scheduler's lock to wait until thread becomes primary
         */
        private Condition isPrimaryCondition;

        /**
         * True if thread has terminated its usual processing, but is not yet removed by
         * UDS
         */
        private boolean terminated;

        /**
         * True if thread has finished its round
         */
        private boolean finished;

        /**
         * True if thread waits for its turn in the total UDS order
         */
        private boolean waitingForTurn;

        /**
         * Condition for scheduler's lock to wait until it's the thread's turn
         */
        private Condition waitingForTurnCondition;

        /**
         * True if background thread
         */
        public boolean background;

        /**
         * If background thread, the number of threads per round
         */
        public int backgroundSteps;

        /**
         * The number of steps this UDSThread has consumed so far. Updated in realtime, whenever a step is taken from
         * the total order
         */
        protected int stepsConsumed;

        /**
         * The number of rounds this thread has seen. Updated whenever the thread becomes primary in a new round
         */
        protected int roundsSeen;

        /**
         * For stepped UDS and learning/selfoptimization: The type of request this thread is working on in its Runnable
         */
        protected org.aspectix.selfoptim.stepped.RequestType requestType;

        // for steppedEnvironment (UDS RL optimization); which virtual client in SODL this request runnable came from
        private int fromClientId;

        private SteppedRunnable steppedRunnable;

        /**
         * Constructor of UDS threads
         */
        UdsThread() {
            this.idString = "{" + UdsScheduler.this.id + ":T" + threadId.getAndIncrement() + "}";
            this.started = false;
            this.enqueued = null;
            this.terminated = false;
            this.finished = false;
            this.waitingForTurn = false;
            this.stepsConsumed = 0;
            this.roundsSeen = 0;
            this.requestType = org.aspectix.selfoptim.stepped.RequestType.UNDEFINED;
            this.steppedRunnable = null;

            // Create and intialise condition objects
            isPrimaryCondition = schedulerLock.newCondition();
            waitingForTurnCondition = schedulerLock.newCondition();
            enqueuedCondition = schedulerLock.newCondition();

            logger.finest(getThreadID() + " UDSThread(): created " + idString);
        }

        /**
         * Constructor of UDS threads including runnables
         *
         * @param runDet   The deterministic runnable that runs within the scheduler
         * @param runUndet The undeterministic runnable that runs without determinism
         *                 after the deterministic runnable has completed
         */
        UdsThread(Runnable runDet, Runnable runUndet) {
            this();
            this.task = () -> {
                try {
                    currentUdsThread.set(this);
                    if (runDet != null)
                        runDet.run();
                    if (runUndet != null)
                        udsThreadPool.submit(runUndet);
                } finally {
                    terminateThread();
                    currentUdsThread.set(null);
                }
            };
        }

        /**
         * Constructor of UDS threads including runnables
         *
         * @param runDet The deterministic runnable that runs within the scheduler
         */
        UdsThread(Runnable runDet) {
            this(runDet, null);
        }

        /**
         * Returns ID string of UdsThread
         *
         * @return Thread's ID string
         */
        public String getIdString() {
            return idString;
        }

        /**
         * Classical yield function, consuming a UDS step
         */
        public void yield() {
            waitForTurn();
        }

        /**
         * Returns start status of UdsThread
         *
         * @return True if started
         */
        boolean isStarted() {
            return started;
        }

        /**
         * Returns lock at which thread is enqueued (waiting)
         *
         * @return UdsLock thread is waiting for
         */
        protected UdsLock getEnqueued() {
            return this.enqueued;
        }

        /**
         * Registers lock at which UdsThread is enqueued (waiting for)
         *
         * @param lock The lock which it waits for
         */
        protected void setEnqueued(UdsLock lock) {
            this.enqueued = lock;
        }

        /**
         * Returns primary status of UdsThread
         *
         * @return True if primary
         */
        boolean isPrimary() {
            return this.primary;
        }

        protected void setSteppedRunnable(SteppedRunnable steppedRunnable) {
            this.steppedRunnable = steppedRunnable;
        }

        /**
         * If this UDSThread has a SteppedRunnable (UDS-RL) set explicitly,
         * return this runnable (e.g. for RL reward calculation stuff).
         * @return SteppedRunnable if in a stepped environment and set, null otherwise
         */
        protected SteppedRunnable getSteppedRunnable() {
            return this.steppedRunnable;
        }

        /**
         * Sets primary status
         *
         * @param primary New primary status
         */
        void setPrimary(boolean primary) {
            boolean oldPrimary = this.primary;
            this.primary = primary;
            logger.finest(getThreadID() + " setPrimary: " + this.getIdString() + " from " + oldPrimary + " to "
                    + this.primary);

            if (!oldPrimary && primary) {
                isPrimaryCondition.signal();
            }
        }

        /**
         * Wait to become a primary thread. This method can only be called when holding
         * the scheduler lock. It further has to be called on the currently running
         * UdsThread
         */
        void waitForPrimary() {
            logger.finer(getIdString() + " waitForPrimary");
            fillPrimaries();
            if (!currentConfig.deterministic)
                return;

            while (!primary) {
                try {
                    isPrimaryCondition.await();
                } catch (InterruptedException e) {
                    if(state == UdsState.FORCEDSHUTDOWN) {
                        logger.info(getIdString() + " should terminate in waitForPrimary because UDS is " +
                                "FORCEDSHUTDOWN. Returning after terminate=true and dequeueing.");
                        setTerminated();
                        dequeueThread();
                        return;
                    }
                    logger.info(getIdString()
                            + " waitForPrimary: has been interrupted while waiting to become primary. Re-waiting...");
                }
            }
            logger.finer(getIdString() + " waitForPrimary: became primary");
        }

        /**
         * Wait for being dequeued at a lock. Can only be called when holding the
         * scheduler lock.
         */
        void awaitDequeueing() {
            logger.finer(getIdString() + " awaitDequeueing: ");
            while (true) {
                try {
                    enqueuedCondition.await();
                    break;
                } catch (InterruptedException e) {
                    logger.finest(getIdString() + "awaitDequeueing: was interrupted, re-waiting for dequeueing");
                }
            }
        }

        /**
         * Dequeue this thread from its lock. Can only be called when holding the
         * scheduler lock.
         */
        void dequeueThread() {
            if (enqueued != null) {
                logger.finer(getIdString() + " dequeueThread: is dequeueing itself");
                enqueued.removeFromQueue(this);
                enqueued = null;
                enqueuedCondition.signal();
            }
        }

        public int getFromClientId() {
            return fromClientId;
        }

        /**
         * Returns status of this UdsThread's termination
         * 
         * @return True if UdsThread is terminated
         */
        boolean isTerminated() {
            return this.terminated;
        }

        /**
         * Sets this UdsThread's termination status to true
         */
        void setTerminated() {
            this.terminated = true;
        }

        /**
         * Returns this UdsThread's finished status
         * 
         * @return True if round is finished for this UdsThread
         */
        boolean isFinished() {
            return this.finished;
        }

        /**
         * Sets this UdsThreads's finished status
         * 
         * @param finished New finished status
         */
        void setFinished(boolean finished) {
            // logger.info( getThreadID() + "finished");
            this.finished = finished;
        }

        /**
         * Returns true if UdsThread waits for turn
         * 
         * @return True if waiting for turn
         */
        boolean isWaitingForTurn() {
            return this.waitingForTurn;
        }

        /**
         * Sets wait-for-turn status of this UdsThread This method can only be called
         * when holding the scheduler lock
         * 
         * @param waitingForTurn Is thread waiting for turn
         */
        void setWaitingForTurn(boolean waitingForTurn) {
            logger.finest(getThreadID() + " setWaitingForTurn: " + this.getIdString() + " to " + waitingForTurn);
            if (this.waitingForTurn && !waitingForTurn)
                waitingForTurnCondition.signal();
            this.waitingForTurn = waitingForTurn;
        }

        /**
         * This method sets a condition to wait for turn
         * 
         * @param con The condition to wait for
         */
        void setWaitingForTurnCondition(Condition con) {
            this.waitingForTurnCondition = con;
        }

        /**
         * This method can only be called when holding the scheduler lock
         */
        void awaitTurn() {
            while (waitingForTurn) {
                try {
                    waitingForTurnCondition.await();
                } catch (InterruptedException e) {
                    logger.info(getThreadID() + " awaitTurn: interrupted while waiting for turn. Re-waiting...");
                }
            }
        }

        /**
         * Overriden toString function for debug output concerning this UdsThread
         */
        @Override
        public String toString() {
            return getIdString() + " status: " + (isPrimary() ? "primary" : "not primary")
                    + (isTerminated() ? ", terminated"
                            : ((isFinished() ? ", finished round"
                                    : ((enqueued != null ? ", is enqueued"
                                            : ((isWaitingForTurn() ? ", waits for turn" : ", running")))))));
        }

        /**
         * Start this UdsThread.
         */
        void start(StartMode mode) {
            String prefix = getThreadID() + " UdsThread.start: ";

            logger.finer(prefix);

            schedulerLock.lock();
            try {
                switch(mode) {
                    case INTERNAL:
                        // add thread at position of current thread
                        // this is the only one we can actually assume; all threads before may be gone;
                        // all threads thereafter are perhaps unknown
                        // only alternative would be directly after current thread (indexOf+1)
                        int index = allThreads.indexOf(getCurrentUdsThread());
                        allThreads.add(index, this);
                        if(threadIndex > index) {
                            threadIndex++;
                        }
                        break;
                    case EXTERNAL:
                        // add thread at end of thread list
                        allThreads.add(this);
                        break;
                    case BACKGROUND:
                        assert (false); // TODO: we have to implement this with an extra list
                        break;
                }

                scheduledThreads++;
                if(task != null)
                    udsThreadPool.submit(task);
                started = true;

                if(logger.isLoggable(Level.FINER)) {
                    String threadListStatus = allThreads.stream().map(UdsThread::getIdString)
                            .collect(Collectors.joining(", ", prefix + "current threadList: [", "]"));
                    logger.finer(threadListStatus);
                }
            } catch(RejectedExecutionException e) {
                System.err.println("Couldn't submit task to udsThreadPool (already shut down)");
            } finally {
                schedulerLock.unlock();
            }
        }

        /**
         * Returns scheduler object for this UdsThread
         * 
         * @return Scheduler object of this UdsThread
         */
        UdsScheduler getUdsScheduler() {
            return UdsScheduler.this;
        }

        public int getStepsConsumed() {
            return stepsConsumed;
        }

        public int getRoundsSeen() {
            return roundsSeen;
        }

        public RequestType getRequestType() {
            return requestType;
        }
    }

    /**
     * For storing information about an observed round in stepped environment
     */
    private class UDSRLObservedRound {
        /*
        { \"scheduler\": ");
            answer.append("\"" + this.id + "\"");
            answer.append(", \"previousRoundPrimaries\": ");
            answer.append(previousRoundPrimaries.stream()
                    .map(t -> Float.toString(t.getRequestType().getRequestTypeCode()))
                    .collect(Collectors.joining(",", "[","]")));
            answer.append(", \"previousRoundStepsTaken\":");
            answer.append(previousRoundPrimaries.stream()
                    .map(t -> Integer.toString(t.stepsConsumed))
                    .collect(Collectors.joining(",", "[", "]")));
            answer.append(", \"previousRoundRoundsSeen\":");
            answer.append(previousRoundPrimaries.stream()
                    .map(t -> Integer.toString(t.roundsSeen))
                    .collect(Collectors.joining(",", "[", "]")));
            answer.append(", \"threadsInSystem\": ");
            answer.append(previousRoundAllThreads.stream()
                    .map(t -> String.valueOf(t.getRequestType().getRequestTypeCode()))
                    .collect(Collectors.joining(",", "[","]")));
            answer.append(", \"round\": " + this.round);
            answer.append(", \"previousRoundDurationNs\": " + previousRoundDurationNs);
            answer.append(", \"previousRoundCPUTimeUsedNs\": " + previousRoundCPUTimeUsedNs);
            answer.append(", \"previousRoundActiveSODLClients\": " + previousRoundSODLActiveClients);
            answer.append(", \"estimatedClients\": " + estimatedClients);
            answer.append(", \"currentConfig\": ");
            answer.append(currentConfig.toString());
            answer.append("}}");
            */
        private String schedulerId;
        private int round;
        private List<UdsThread> previousRoundPrimaries;
        private List<UdsThread> threadsInSystem;
        private long previousRoundDurationNs;
        private long previousRoundCPUTimeUsedNs;
        private int previousRoundActiveSODLClients;
        private int estimatedClients;
        private UdsConfiguration currentConfig;
        private int roundsSinceLastConfigChange;

        private UDSRLObservedRound(String schedulerId, int round,
                                   List<UdsThread> previousRoundPrimaries, List<UdsThread> threadsInSystem,
                                   long previousRoundDurationNs, long previousRoundCPUTimeUsedNs,
                                   int previousRoundActiveSODLClients, int estimatedClients,
                                   UdsConfiguration currentConfig, int roundsSinceLastConfigChange) {
            this.schedulerId = schedulerId;
            this.round = round;
            this.previousRoundPrimaries = previousRoundPrimaries;
            this.threadsInSystem = threadsInSystem;
            this.previousRoundDurationNs = previousRoundDurationNs;
            this.previousRoundCPUTimeUsedNs = previousRoundCPUTimeUsedNs;
            this.previousRoundActiveSODLClients = previousRoundSODLActiveClients;
            this.estimatedClients = estimatedClients;
            this.currentConfig = currentConfig;
            this.roundsSinceLastConfigChange = roundsSinceLastConfigChange;
        }

        private String getObservationAsJson() {
            StringBuilder obs = new StringBuilder("{");

            obs.append("\"scheduler\": \"" + this.schedulerId + "\"");
            obs.append(", \"previousRoundPrimaries\":");
            obs.append(this.previousRoundPrimaries.stream()
                    .map(t -> Float.toString(t.getRequestType().getRequestTypeCode()))
                    .collect(Collectors.joining(",", "[","]")));
            obs.append(", \"previousRoundStepsTaken\":");
            obs.append(this.previousRoundPrimaries.stream()
                    .map(t -> Integer.toString(t.stepsConsumed))
                    .collect(Collectors.joining(",", "[", "]")));
            obs.append(", \"previousRoundRoundsSeen\":");
            obs.append(this.previousRoundPrimaries.stream()
                    .map(t -> Integer.toString(t.roundsSeen))
                    .collect(Collectors.joining(",", "[", "]")));
            obs.append(", \"threadsInSystem\":");
            obs.append(this.threadsInSystem.stream()
                    .map(t -> String.valueOf(t.getRequestType().getRequestTypeCode()))
                    .collect(Collectors.joining(",", "[","]")));
            obs.append(", \"round\": " + this.round);
            obs.append(", \"previousRoundDurationNs\": " + this.previousRoundDurationNs);
            obs.append(", \"previousRoundCPUTimeUsedNs\": " + this.previousRoundCPUTimeUsedNs);
            obs.append(", \"previousRoundActiveSODLClients\":" + this.previousRoundActiveSODLClients);
            obs.append(", \"estimatedClients\":" + this.estimatedClients);
            obs.append(", \"roundsSinceLastConfigChange\":" + this.roundsSinceLastConfigChange);
            obs.append(", \"currentConfig\":");
            obs.append(this.currentConfig.toString());
            obs.append("}");

            return obs.toString();
        }
    }
}
