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

import org.aspectix.uds.UdsScheduler;

/**
 * Instruction to test whether there is a pure sequential execution
 */
public class TraceInstruction extends CpuLoadInstruction {
    /**
     * A string designating a context. All occurences of the same context are shared
     * and treated alike.
     */
    private String contextId;

    /**
     * Constructor
     * 
     * @param duration duration in nanoseconds that this instruction should keep CPU
     *                 busy
     */
    public TraceInstruction(String contextId, long duration) {
        super(duration);
        if (contextId == null || contextId == "") {
            throw new IllegalArgumentException("Context needs to be a non empty string");
        }
        this.contextId = contextId;
    }

    /**
     * Execute function of the instruction
     */
    @Override
    public int execute(SimulatedExecution ex, int pc) {
        ExecutionEnvironment env = ex.getEnv();
        TraceContext ourContext = env.context.get(contextId);
        if (ourContext == null) {
            ourContext = new TraceContext(contextId);
            env.context.put(contextId, ourContext);
        }
        logger.finest(org.aspectix.uds.UdsScheduler.getThreadID() + " Trace: context " + ourContext.contextId + ", load for " + duration
                + "ns");

        // prelude
        synchronized (ourContext) {
            ourContext.trace.add(org.aspectix.uds.UdsScheduler.getThreadID() + " entering");
        }

        // do something
        pc = super.execute(ex, pc);

        // postlude
        synchronized (ourContext) {
            ourContext.trace.add(org.aspectix.uds.UdsScheduler.getThreadID() + " leaving");
        }

        return pc;
    }

    /**
     * toString method
     */
    @Override
    public String toString() {
        return "Trace(" + contextId + ", " + duration + ")";
    }

    /**
     * Check for sequential execution
     * 
     * @return true if sequential execution was found
     */
    public static boolean checkSequentialExecution(TraceContext traceCtx) {
        int threadsInContext = 0;

        for (int i = 0; i < traceCtx.trace.size(); i++) {
            if (traceCtx.trace.get(i).matches(" entering")) {
                threadsInContext++;
            } else if (traceCtx.trace.get(i).matches(" leaving")) {
                threadsInContext--;
            }
            if (threadsInContext > 1) {
                logger.severe(UdsScheduler.getThreadID() + " more than one thread in context at position " + i);
                return false;
            }
        }
        return true;
    }

    /**
     * Check for concurrent execution
     * 
     * @return number of concurrent threads found in trace
     */
    public static int checkConcurrentExecution(TraceContext traceCtx) {
        int threadsInContext = 0;
        int maxThreads = 0;

        for (int i = 0; i < traceCtx.trace.size(); i++) {
            if (traceCtx.trace.get(i).matches(".*entering")) {
                threadsInContext++;
            } else if (traceCtx.trace.get(i).matches(".*leaving")) {
                threadsInContext--;
            }
            if (maxThreads < threadsInContext) {
                maxThreads = threadsInContext;
            }
        }
        return maxThreads;
    }
}