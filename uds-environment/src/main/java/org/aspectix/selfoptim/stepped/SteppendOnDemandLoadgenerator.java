package org.aspectix.selfoptim.stepped;

import org.aspectix.uds.UdsLock;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class SteppendOnDemandLoadgenerator {

    private final List<UdsLock> udsLocks;

    private int maxNumOfClients; // how many clients are actively sending requests at max
    private int clientCapability; // theoretical req/s each client is capable of sending
    private int tickets; // current number of tickets, counting down before a ByTI is generated; calculated
    private boolean randomLoad;

    // values controlling how often randomLoad changes the number of active clients
    private final int minReqStableLoad = 80;
    private final int maxReqStableLoad = 200;
    private int reqSinceLastLoadChange = 0;

    // how often ByTI are (theoretically) occurring naturally, e.g. every 100ms
    private final int BYTI_PERIOD_MS = 100;

    private List<SODLClient> clients;

    /**
     * Logging
     */
    protected static final Logger logger = Logger.getLogger(SteppendOnDemandLoadgenerator.class.getName());

    /**
     *
     * @param udsLocks a List of UDSLocks which clients can take
     * @param maxNumOfClients how many clients at most are actively sending requests simultaneously
     * @param clientCapability theoretical req/s each client is capable of sending
     * @param randomLoad whether a random fluctuation of clients should be simulated (between 1 and maxNumOfClients)
     */
    public SteppendOnDemandLoadgenerator(List<UdsLock> udsLocks, int maxNumOfClients, int clientCapability,
                                         boolean randomLoad) {
        this.udsLocks = udsLocks;
        this.maxNumOfClients = maxNumOfClients;
        this.clientCapability = clientCapability;
        // calculate tickets by summing up raw clientCapabilities/s then dividing by ByTI period
        this.tickets = maxNumOfClients * clientCapability / (1000 / BYTI_PERIOD_MS);
        this.clients = new ArrayList<>(40);

        this.randomLoad = randomLoad;

        for(int i = 0; i < maxNumOfClients; i++) {
            this.clients.add(new SODLClient(clientCapability, i));
        }
        // initial random number of active clients
        changeActiveClients(getRandomNumber(1, clients.size() + 1));
    }

    private int getCurrentReqCapability() {
        return (int) this.clients.stream().collect(Collectors.summarizingInt(SODLClient::getRequestRate)).getSum();
    }

    public int getMaxNumOfClients() {
        return this.clients.size();
    }

    public int getTickets() {
        return tickets;
    }

    public void setTickets(int tickets) {
        this.tickets = tickets;
    }

    public void requestTerminated(int fromClientId) {
        try {
            this.clients.get(fromClientId).requestTerminated();
        } catch(IllegalStateException e) {
            e.printStackTrace();
        }
    }

    public SteppedRunnable getNextRequest() {
        if(randomLoad) {
            this.reqSinceLastLoadChange++;
            if(reqSinceLastLoadChange >= maxReqStableLoad) {
                // force change of num of active clients
                changeActiveClients(getRandomNumber(1, clients.size() + 1));
            } else if(reqSinceLastLoadChange > minReqStableLoad) {
                // maybe trigger change of num of active clients
                double rnd = Math.random();
                double diffMinMax = maxReqStableLoad - minReqStableLoad;
                double diffLastChangeMin = reqSinceLastLoadChange - minReqStableLoad;
                double probOfChange = diffLastChangeMin / diffMinMax;
                if(rnd < probOfChange) {
                    changeActiveClients(getRandomNumber(1, clients.size() + 1));
                }
            }
        }

        Optional<SODLClient> client =
                clients.stream().filter(SODLClient::isActive).filter(SODLClient::noRequestsOutstanding).findFirst();
        if(client.isPresent()) {
            return client.get().generateNextThread();
        } else {
            logger.finest("SODL returned new ByTI because not enough active clients available");
            return new ByTIRunnable();
        }
    }

    private void changeActiveClients(int newActiveClients) {
        clients.stream().forEach(SODLClient::disable);
        for(int i = 0; i < newActiveClients; i++) {
            clients.get(i).enable();
        }
        this.reqSinceLastLoadChange = 0;
        logger.finest("SODL changed active clients to " + newActiveClients);
    }

    public int getNumOfActiveClients() {
        return (int) clients.stream().filter(SODLClient::isActive).count();
    }

    private int getRandomNumber(int min, int max) {
        return (int) ((Math.random() * (max - min)) + min);
    }

    private class SODLClient {
        private int outstandingRequests;

        public int getRequestRate() {
            return requestRate;
        }

        private int requestRate; // the theoretical/simulated number of requests/s this client is sending
        private boolean active;
        private int clientId;

        SODLClient() {
            this(0, -1);
        }

        SODLClient(int requestRate, int clientId) {
            this.outstandingRequests = 0;
            this.clientId = clientId;
            this.requestRate = requestRate;
            this.active = true;
        }

        private SteppedRunnable generateNextThread() {
            logger.finest("SODL Tickets left: " + tickets);

            // if tickets are gone we generate a ByTI and reset the tickets
            if(tickets < 1) {
                logger.finer("ByTI generated due to ticket underflow");
                tickets = clients.size() * clientCapability / (1000 / BYTI_PERIOD_MS);
                logger.finest("New number of tickets: " + tickets);
                return new ByTIRunnable();
            }

            if(outstandingRequests > 1 || outstandingRequests < 0) {
                logger.finer("SODLClient #" + clientId + " can't generate new req. Returning ByTIRUnnable. " +
                        "Outstanding reqs: " + outstandingRequests);
                return new ByTIRunnable();
            }

            this.outstandingRequests++;
            tickets--;
            logger.finest("SODLClient #" + clientId + " generates new req. Outstanding reqs: " + outstandingRequests);
            return new LUCLURunnable(udsLocks.get(0), clientId, 250000);
        }

        private void requestTerminated() throws IllegalStateException {
            if(outstandingRequests > 0) {
                outstandingRequests--;
                logger.finest("SODLClient #" + clientId + " request terminated. Outstanding reqs: " + outstandingRequests);
            } else {
                throw new IllegalStateException("Request was terminated for SODLClient " + clientId + " even though no requests " +
                        "were outstanding for this client.");
            }
        }

        private boolean isActive() {
            return this.active;
        }

        private void setActive(boolean active) {
            this.active = active;
        }

        private void enable() {
            this.active = true;
        }

        private void disable() {
            this.active = false;
        }

        private boolean hasRequestsOutstanding() {
            return outstandingRequests > 0;
        }

        private boolean noRequestsOutstanding() {
            return outstandingRequests == 0;
        }
    }
}
