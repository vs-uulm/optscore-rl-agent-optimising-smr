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
public class CpuLoadInstruction extends Instruction {
    /**
     * Calibrated duration of a standard loop execution
     */
    static long calibratedLoadDuration;

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
    public CpuLoadInstruction(long duration) {
        this.duration = duration;
    }

    /**
     * Calibrator
     */

    static {
        long e;
        long s = -1000 * 1000 * 1000;
        long min = 1000 * 1000 * 1000;

        for (int i = 1; i < 10000; i++) {
            e = System.nanoTime();
            someLoad();
            if ((e - s) < min)
                min = e - s;
            s = e;
            // this is just here to do the same computation as later in the real load
            if (calibratedLoadDuration * i > e)
                continue;

        }
        calibratedLoadDuration = min;

        System.out.println("CpuLoadInstruction: calibrated to " + calibratedLoadDuration + "ns");
    }

    /**
     * Some CPU load
     */
    static void someLoad() {
        for (int j = 0; j < 400; j++) {
            Math.atan(0.4);
        }
    }

    /**
     * Execute function of the instruction
     */
    @Override
    public int execute(SimulatedExecution ex, int pc) {
        logger.finest(org.aspectix.uds.UdsScheduler.getThreadID() + " SimEx.CpuLoad: load for " + duration + "ns");
        long e = 0;
        long s = -1000 * 1000 * 1000;
        long min = 1000 * 1000 * 1000;

        // simple self-calibrating CPU load: assumption is that Math.atan always needs
        // the same real-time span; for frequency scaling self adaptation is repeated
        // every 100th iteration
        for (int i = 1; true; i++) {
            e = System.nanoTime();
            // the load
            for (int j = 0; j < 200; j++) {
                Math.atan(0.4);
            }
            if ((e - s) < min)
                min = e - s;
            s = e;
            if (calibratedLoadDuration * i > duration)
                break;
        }

        // System.out.println("Real time " + (end - start)); // TODO: test timings

        return pc + 1;

    }

    /**
     * Calibrate load execution time
     */
    public static void calibrate(long length) {
        calibratedLoadDuration = length;
    }

    /**
     * toString method
     */
    @Override
    public String toString() {
        return "CpuLoad(" + duration + ")";
    }

    static public void main(String argv[]) {
        CpuLoadInstruction ci = new CpuLoadInstruction(250 * 1000);
        SimulatedExecution ex = new SimulatedExecution(new Instruction[0], new UdsScheduler());

        for (int i = 0; i < 100; i++)
            ci.execute(ex, 10);
    }
}
