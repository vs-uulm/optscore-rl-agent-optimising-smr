package org.aspectix.selfoptim.stepped;

public interface SteppedRunnable extends Runnable {
    public RequestType getRequestType();

    public int getFromClientId();

    public long getApproxCPUTimeSpentNs();
}
