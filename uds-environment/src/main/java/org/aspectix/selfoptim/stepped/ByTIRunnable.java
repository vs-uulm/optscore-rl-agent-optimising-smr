package org.aspectix.selfoptim.stepped;

public class ByTIRunnable implements SteppedRunnable {
    private final RequestType type = RequestType.BYTI;

    @Override
    public RequestType getRequestType() {
        return type;
    }

    @Override
    public int getFromClientId() {
        return -1;
    }

    @Override
    public long getApproxCPUTimeSpentNs() {
        return 0;
    }


    @Override
    public void run() {

    }
}
