package org.aspectix.selfoptim.stepped;

import org.aspectix.uds.*;
import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

public class UdsEnvironment {
    private UdsScheduler scheduler;
    private List<UdsLock> udsLocks;

    private static final int udsMaxThreads = 16;

    private final ZContext zContext;
    private final ZMQ.Socket zSocket;
    private final int port;

    private ServerSocket serverSocket;
    private Socket client;

    private PrintWriter clientWriter;
    private BufferedReader clientReader;

    private int nextAction = 0;
    private boolean nextStep = false;

    private ReentrantLock stepLock;
    private Condition stepCondition;
    private Callable<Integer> stepCallable;

    private SteppendOnDemandLoadgenerator steppedUdsThreadGenerator;

    private int udsInstanceCounter;

    private int numClients;

    private Callable<SteppedRunnable> nextThreadCallable;

    /**
     * Logging
     */
    protected static final Logger logger = Logger.getLogger(UdsEnvironment.class.getName());

    public UdsEnvironment(int numClients, int port) {
        this.zContext = new ZContext();
        this.zSocket = zContext.createSocket(SocketType.REP);
        this.port = port;

        this.udsInstanceCounter = 0;

        this.numClients = numClients;

        Runtime.getRuntime().addShutdownHook(new Thread(this.zSocket::close));
    }

    public void socketLoop() {
        //  Socket to talk to clients
        zSocket.bind("tcp://localhost:" + this.port);
        logger.info("Socket connected (" + this.port + ")");

        while (!Thread.currentThread().isInterrupted()) {
            byte[] recv = zSocket.recv(0);
            String msg = new String(recv, ZMQ.CHARSET);
            if(msg.equals("exit")) {
                zSocket.close();
                zContext.destroy();
                break;
            } else {
                handleInput(msg);
            }
        }

        logger.info("== Received exit command. Exiting org.aspectix.stepped.UdsEnvironment server...");
        // System.exit(0);  // don't exit JVM when running in thread via TestcaseCoordinator
    }

    private void handleInput(String input) {
        try {
            int action = Integer.parseInt(input);
            logger.finer("Received action: " + action);

            action(action);

        } catch(NumberFormatException e) {
            logger.fine("Received text: " + input);
            if(input.equals("reset")) {
                reset();
            }
        }
    }

    private void action(int action) {
        stepLock.lock();
        try {
            // set next action
            this.nextAction = action;
            // set condition for UDS thread waiting in startRound to wake up
            this.nextStep = true;
            logger.finest(UdsScheduler.getThreadID() + " signalling threads waiting on stepCondition; " +
                    "next received action: " + action);
            this.stepCondition.signalAll();
        } finally {
            stepLock.unlock();
        }
    }

    private void reset() {
        // create new stepCondition and stepCallable
        this.stepLock = new ReentrantLock();
        this.stepCondition = this.stepLock.newCondition();
        this.stepCallable = new Callable<Integer>() {
            @Override
            public Integer call() throws Exception {
                stepLock.lock();
                try {
                    while(!nextStep) {
                        logger.finer(UdsScheduler.getThreadID() + " waiting for next step in stepCallable ...");
                        stepCondition.await();
                        // leave loop and abort waiting even if next step hasn't been set if we receive -1 (signal for reset)
                        if(nextAction == -1) {
                            break;
                        }
                    }
                    nextStep = false;
                    logger.finer(UdsScheduler.getThreadID() + " in stepCallable about to return with nextAction: " + nextAction);
                } catch(InterruptedException e) {
                    logger.warning(UdsScheduler.getThreadID() + " was interrupted while waiting on stepCondition");
                    return 0;
                } finally {
                    stepLock.unlock();
                }

                return nextAction;
            }
        };


        // create new scheduling environment with UDSLocks and Scheduler
        this.udsLocks = new ArrayList<>();
        udsLocks.add(new UdsLock());

        this.steppedUdsThreadGenerator = new SteppendOnDemandLoadgenerator(udsLocks, numClients, 500, true);

        if(this.scheduler != null) {
            logger.info("Last UDScheduler stats: " + scheduler.getStatsNonLocking().toString());
        }

        // instantiate new scheduler and new UDS locks
        logger.info(UdsScheduler.getThreadID() + " spawning new UDScheduler with ID " + udsInstanceCounter);
        this.scheduler = new UdsScheduler("UDScheduler #" + udsInstanceCounter,
                new UdsConfiguration(1, 1, udsMaxThreads, zSocket),
                stepCallable, this.steppedUdsThreadGenerator);

        // increment naming of UDScheduler, so we don't get confused when debugging/logging with multiple UDS instances
        this.udsInstanceCounter++;

        // send an answer to the reset-request
        logger.fine("Sending reset-answer...");
        this.zSocket.send("Reset successful".getBytes(StandardCharsets.UTF_8), 0);
    }

    public static void main(String[] args) {
        UdsEnvironment env = new UdsEnvironment(8, 1337);
    }
}
