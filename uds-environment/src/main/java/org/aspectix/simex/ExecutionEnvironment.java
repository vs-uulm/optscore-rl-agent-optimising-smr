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
package org.aspectix.simex;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.aspectix.uds.UdsLock;
import org.aspectix.uds.UdsScheduler;

/**
 * Class containing the execution environment for a simulated execution
 */
public class ExecutionEnvironment {
    /**
     * Our locks
     */
    final org.aspectix.uds.UdsLock[] locks;
     
    /**
     * List of locks taken
     */
    List<org.aspectix.uds.UdsLock> takenLocks = new ArrayList<org.aspectix.uds.UdsLock>(10);

    /**
     * Number of locks taken
     */
    int takenLocksCount = 0;

    /**
     * Contexts
     */
    Map<String, TraceContext> context = new HashMap<>();
    
    /**
     * The scheduler used in this environment
     */
    org.aspectix.uds.UdsScheduler scheduler;

    /**
     * Starting time in ns
     */
    long startTime;

    /**
     * Completion time in ns
     */
    long endTime;

    /**
     * Constructor
     * 
     * @param lockCount number of locks in the environment
     */
    public ExecutionEnvironment(org.aspectix.uds.UdsScheduler us, int lockCount) {
        scheduler = us;
        locks = new org.aspectix.uds.UdsLock[lockCount];
        synchronized (locks) {
            for (int i = 0; i < lockCount; i++) {
                locks[i] = new UdsLock();
            }
        }
    }

    /**
     * Constructor
     */
    public ExecutionEnvironment(UdsScheduler us) {
        this(us, 64);
    }

    /**
     * Get context trace
     */
    public TraceContext getTrace(String contextId) {
        return context.get(contextId);
    }

    /**
     * Get execution time
     * 
     * @return execution time in ns
     */
    public long getExecutionTime() {
        return endTime - startTime;
    }
}
