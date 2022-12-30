package org.aspectix.selfoptim.stepped;

import org.aspectix.uds.UdsLock;

public class LULURunnable implements SteppedRunnable {
    private final RequestType type = RequestType.LULU;

    private final org.aspectix.uds.UdsLock lock;

    private int fromClientId;

    public LULURunnable(org.aspectix.uds.UdsLock lock) {
        this(lock, -1);
    }

    public LULURunnable(UdsLock lock, int fromClientId) {
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
        this.lock.lock();
        this.lock.unlock();
    }
}
