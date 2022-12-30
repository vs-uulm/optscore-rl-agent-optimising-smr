package org.aspectix.selfoptim.stepped;


import org.aspectix.uds.UdsLock;

public class LURunnable implements SteppedRunnable {
    private final RequestType type = RequestType.LU;

    private final UdsLock lock;

    private int fromClientId;

    public LURunnable(UdsLock lock) {
        this(lock, -1);
    }

    public LURunnable(UdsLock lock, int fromClientId) {
        this.lock = lock;
        this.fromClientId = fromClientId;
    }

    @Override
    public RequestType getRequestType() {
        return type;
    }

    public int getFromClientId() {
        return fromClientId;
    }

    @Override
    public long getApproxCPUTimeSpentNs() {
        return 0;
    }

    @Override
    public void run() {
        this.lock.lock();
        this.lock.unlock();
    }
}
