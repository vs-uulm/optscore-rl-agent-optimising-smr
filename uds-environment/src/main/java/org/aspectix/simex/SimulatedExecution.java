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

import java.util.Arrays;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Object simulates the execution of some deterministic application
 */
public class SimulatedExecution implements Runnable {
    /**
     * The list of instructions to executed and simulate
     */
    private Instruction[] instr;

    /**
     * Some execution types
     */
    public static final Instruction[] L1U = new Instruction[] { new LockInstruction(1), new UnlockInstruction(0), };
    public static final Instruction[] L1C250U = new Instruction[] { new LockInstruction(1),
            new CpuLoadInstruction(250 * 1000), new UnlockInstruction(0), };
    public static final Instruction[] C250L1C250U = new Instruction[] { new CpuLoadInstruction(250 * 1000),
            new LockInstruction(1), new CpuLoadInstruction(250 * 1000), new UnlockInstruction(0), };
    public static final Instruction[] B250L1B250U = new Instruction[] { new BuggyCpuLoadInstruction(250 * 1000),
            new LockInstruction(1), new BuggyCpuLoadInstruction(250 * 1000), new UnlockInstruction(0), };
    public static final Instruction[] S250L1S250U = new Instruction[] { new SleepInstruction(250 * 1000),
            new LockInstruction(1), new SleepInstruction(250 * 1000), new UnlockInstruction(0), };

    private ExecutionEnvironment env;

    /**
     * Logger
     */
    static final Logger logger = Logger.getLogger(SimulatedExecution.class.getName());

    /**
     * Constructor
     * 
     * @param instr array of instructions
     * @param env   execution environment used for execution of instructions
     */
    public SimulatedExecution(Instruction[] instr, ExecutionEnvironment env) {
        String prefix = org.aspectix.uds.UdsScheduler.getThreadID() + " SimulatedExecution: ";
        this.instr = instr;
        this.env = env;

        logger.finer(Arrays.asList(instr).stream().map(Object::toString)
                .collect(Collectors.joining(", ", prefix + "[", "]")));
    }

    /**
     * Constructor
     * 
     * @param instr array of instructions
     * @param us    UDS scheduler object
     */
    public SimulatedExecution(Instruction[] instr, UdsScheduler us) {
        this(instr, new ExecutionEnvironment(us));
    }

    /**
     * The run method executed the simulated execution
     */
    @Override
    public void run() {
        env.startTime = System.nanoTime();
        for (int pc = 0; pc < instr.length;) {
            pc = instr[pc].execute(this, pc);
        }
        env.endTime = System.nanoTime();
    }

    /**
     * Getter for environment
     */
    public ExecutionEnvironment getEnv() {
        return env;
    }
}
