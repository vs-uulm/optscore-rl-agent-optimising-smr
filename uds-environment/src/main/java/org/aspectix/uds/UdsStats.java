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

import java.util.logging.Logger;

/**
 * Statistics object of a UDS scheduler
 */
public class UdsStats {
    /**
     * current round
     */
    public int round;

    /**
     * configured primary number
     */
    public int configuredPrimaries;

    /*
     * actual primary number
     */
    public int actualPrimaries;

    /**
     * number of threads scheduled so far
     */
    public long scheduledThreads;

    /**
     * number of already terminated threads
     */
    public long terminatedThreads;

    /**
     * current scheduler state
     */
    public UdsState state;

    /**
     * state change in progress
     */
    public boolean stateChangeInProgress;

    /**
     * Logging
     */
    protected static final Logger logger = Logger.getLogger(UdsScheduler.class.getName());

    /**
     * constructor
     */
    public UdsStats( int r, int cp, int ap, long st, long tt, UdsState s, boolean pr) {
        round = r;
        configuredPrimaries = cp;
        actualPrimaries = ap;
        scheduledThreads = st;
        terminatedThreads = tt;
        state = s;
        stateChangeInProgress = pr; 
        
        logger.finer(UdsScheduler.getThreadID() + " " + this);
    }

    /**
     * standard toString
     */
    public String toString() {
        return "UDS Stats: rnd=" + round
                + " cprim=" + configuredPrimaries
                + " aprim=" + actualPrimaries
                + " shed=" + scheduledThreads
                + " term=" + terminatedThreads
                + " state=" + state
                + " progr=" + stateChangeInProgress;
    }
}
