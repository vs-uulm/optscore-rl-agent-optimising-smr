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

import java.util.logging.Logger;

/**
 * Instruction object that simulates CPU load. It is recommended to not use this
 * instruction for longer time spans. Due to frequency scaling of modern
 * processors the execution time will vary according to currenct clock speed.
 * The self-calibration algorithm will produce shorter time periods as it takes
 * the highest available speed as the average one.
 * 
 * @author Franz J. Hauck
 * @author Gerhard Habiger
 */
public class BuggyCpuLoadInstruction extends Instruction {
    /**
     * Duration in nanoseconds that this instruction should keep CPU busy
     */
    long duration;

    /**
     * Logger
     */
    static final Logger logger = Logger.getLogger(SimulatedExecution.class.getName());

    /**
     * Constructor
     * 
     * @param duration duration in nanoseconds that this instruction should keep CPU
     *                 busy
     */
    public BuggyCpuLoadInstruction(long duration) {
        this.duration = duration;
    }

    /**
     * Execute function of the instruction
     */
    @Override
    public int execute(SimulatedExecution ex, int pc) {
        logger.finest(UdsScheduler.getThreadID() + " SimEx.BuggyCpuLoad: load for " + duration + "ns");
        
        long startTime = System.nanoTime();
        while(System.nanoTime() - startTime < duration) ;

        return pc + 1;
    }

    /**
     * toString method
     */
    @Override
    public String toString() {
        return "BuggyCpuLoad(" + duration + ")";
    }
}
