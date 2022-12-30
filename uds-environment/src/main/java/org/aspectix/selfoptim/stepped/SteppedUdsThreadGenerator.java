package org.aspectix.selfoptim.stepped;

import org.aspectix.uds.*;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.stream.Collectors;

/**
 * Can generate new threads for UDS in a stepped environment, for training deep learning agents
 */
public class SteppedUdsThreadGenerator {
    private final List<UdsLock> udsLocks;
    private int maxThreads;

    private Queue<SteppedRunnable> nextThreads;

    private SteppedGeneratorLoad currentLoad;

    private int byTiIntervalMs = 100;
    private int currentThroughputPerS = 1000;

    public SteppedUdsThreadGenerator(int maxThreads, List<UdsLock> udsLocks, SteppedGeneratorLoad initialLoad) {
        this.maxThreads = maxThreads;
        this.nextThreads = new ArrayDeque<>(maxThreads);
        this.udsLocks = udsLocks;
        this.currentLoad = initialLoad;
        fillNextThreadList();
    }

    public SteppedRunnable getNextRunnable() {
        fillNextThreadList();
        return nextThreads.poll();
    }

    public double[] getNextThreadTypes() {
        return nextThreads.stream()
                .map(SteppedRunnable::getRequestType)
                .mapToDouble(RequestType::getRequestTypeCode)
                .toArray();
    }

    public void fillNextThreadList() {
        double rand;

        while(nextThreads.size() < maxThreads) {
            switch(currentLoad) {
                case NO_LOAD:
                    nextThreads.add(new ByTIRunnable());
                    break;
                case LOW_LOAD:
                    rand = Math.random();
                    if(rand < 0.2d) {
                        nextThreads.add(new LULURunnable(udsLocks.get(0)));
                    } else {
                        nextThreads.add(new ByTIRunnable());
                    }
                    break;
                case MEDIUM_LOAD:
                    rand = Math.random();
                    if(rand < 0.7d) {
                        nextThreads.add(new LULURunnable(udsLocks.get(0)));
                    } else {
                        nextThreads.add(new ByTIRunnable());
                    }
                    break;
                case HIGH_LOAD:
                    rand = Math.random();
                    if(rand < 0.95d) {
                        nextThreads.add(new LULURunnable(udsLocks.get(0)));
                    } else {
                        nextThreads.add(new ByTIRunnable());
                    }
                    break;
                default:
                    nextThreads.add(new ByTIRunnable());
            }
        }
        System.out.print("Filled nextThreads list: ");
        System.out.println(nextThreads.stream()
                .map(SteppedRunnable::getRequestType)
                .map(RequestType::getRequestTypeCode)
                .map(String::valueOf)
                .collect(Collectors.joining(",", "[", "]")));
    }

}
