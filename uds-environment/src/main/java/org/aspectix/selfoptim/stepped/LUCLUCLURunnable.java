package org.aspectix.selfoptim.stepped;

import org.aspectix.uds.UdsLock;

public class LUCLUCLURunnable implements SteppedRunnable {
    private final RequestType type = RequestType.LUCLUCLU;

    private final UdsLock lock;
    private final long calcTimeNs;

    private long cpuTimeSpentNs;

    // Calibration for simulateCPULoadNanos; takes ~5000ns on a lab machine
    private static final long calibratedLoadDuration = 5000;

    private int fromClientId;

    public LUCLUCLURunnable(UdsLock lock) {
        this(lock, -1, 0);
    }

    public LUCLUCLURunnable(UdsLock lock, int fromClientId, long calcTimeNs) {
        this.lock = lock;
        this.fromClientId = fromClientId;
        this.calcTimeNs = calcTimeNs;
        this.cpuTimeSpentNs = 0;
    }

    @Override
    public RequestType getRequestType() {
        return this.type;
    }

    @Override
    public int getFromClientId() {
        return this.fromClientId;
    }

    /**
     * When retrieving this statistic, it is nulled (=0) so multiple requests to the same request don't skew results
     * (CPU time is used once, then the runnable can proceed doing other stuff, but the used time shouldn't be
     * logged/evaluated twice). So only call this when you actually log/use the time.
     * @return The approx. CPU time this runnable used up inside simulateLoad-calls since the last call to this method
     */
    @Override
    public long getApproxCPUTimeSpentNs() {
        long tmp = this.cpuTimeSpentNs;
        this.cpuTimeSpentNs = 0L;
        return tmp;
    }

    /**
     * Occupies the thread by spinning for a given number of nanoseconds (slightly
     * variable depending on the current systems JVM's accuracy).
     * Has to be recalibrated once for the specific hardware it's running on.
     *
     * @param durationInNanoseconds How long the thread should spin for, in ns
     */
    private void simulateCPULoadNanos(long durationInNanoseconds) {
        long start = System.nanoTime();

        long e = 0;
        long s = -1000 * 1000 * 1000;
        long min = 1000 * 1000 * 1000;

        // simple self-calibrating CPU load: assumption is that Math.atan always needs
        // the same real-time span;
        // 150 iterations have been tested and need approx. 5000ns on the lab machines
        for (int i = 1; true; i++) {
            e = System.nanoTime();
            // the load
            for (int j = 0; j < 150; j++) {
                Math.atan((j % 100f) / 100f);
            }
            if ((e - s) < min)
                min = e - s;
            s = e;
            if (calibratedLoadDuration * i > durationInNanoseconds)
                break;
        }
        this.cpuTimeSpentNs += System.nanoTime() - start;
    }

    @Override
    public void run() {
        this.lock.lock();
        this.lock.unlock();
        simulateCPULoadNanos(calcTimeNs);
        this.lock.lock();
        this.lock.unlock();
        simulateCPULoadNanos(calcTimeNs);
        this.lock.lock();
        this.lock.unlock();
    }
}
