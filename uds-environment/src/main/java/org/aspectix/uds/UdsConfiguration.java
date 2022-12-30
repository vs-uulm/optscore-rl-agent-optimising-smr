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

import org.zeromq.ZMQ;

import java.util.List;

/**
 * Container for a UDS round configuration The configuration is valid for the
 * next round. If there is no other configuration set within a round, the
 * current configuration will also be valid for the next round. Subclass this
 * class to get a more sophisticated configuration.
 */
public class UdsConfiguration {
    /**
     * Number of primaries in this configuration
     */
    protected int primaryNumber;

    /**
     * Number of steps for each thread in this configuration If set to non-zero all
     * threads will get the same number in round-robin fasion.
     */
    protected int steps;

    /**
     * Maximum number of threads to admit to the scheduler. Further submissions are
     * blocked.
     */
    public int maxThreads;

    /**
     * Deterministic execution. Defaults to true. Do only use in with false if you
     * know what you are doing.
     */
    public boolean deterministic = true;

    public ZMQ.Socket zSocket;

    /**
     * Constructor for UDS configuration
     */
    public UdsConfiguration(int primaries, int steps, int maxThreads) {
        this.primaryNumber = primaries;
        this.steps = steps;
        this.maxThreads = maxThreads;
        this.zSocket = null;
    }

    /**
     * Constructor for UDS conf. when used with step()/rl-optimization, so that threads can answer with current stats
     */
    public UdsConfiguration(int primaries, int steps, int maxThreads, ZMQ.Socket zSocket) {
        this(primaries, steps, maxThreads);
        this.zSocket = zSocket;
    }

    /**
     * Get number of primaries
     * 
     * @return Number of primaries
     */
    public int getPrimaryNumber() {
        return primaryNumber;
    }

    /**
     * Function to pre-select a primary. If scheduler is in state shutdown or
     * suspended, this method has to consider all threads to get rid of them.
     */
    public boolean selectPrimary(UdsScheduler.UdsThread t, UdsState state) {
        return true; // Select all
    }

    /**
     * Define total order This function reuses an existing List of Integer objects
     */
    public void definePrimaryOrder(List<Integer> threadOrder) {
        int s = steps > 0 ? steps : 1;
        int p = primaryNumber > 0 ? primaryNumber : 1;
        int x = 0;

        for (int i = 0; i < s; i++) {
            for (int j = 0; j < p; j++) {
                try {
                    threadOrder.set(x++, j);
                } catch (IndexOutOfBoundsException e) {
                    threadOrder.add(j);
                }
            }
        }
        while (threadOrder.size() > x) {
            threadOrder.remove(x);
        }
    }

    @Override
    public String toString() {
        return "{ \"primaryNumber\": " + primaryNumber +
                ", \"steps\":" + steps +
                ", \"maxThreads\": " + maxThreads +
                ", \"deterministic\":" + deterministic +
                "}";
    }
}
