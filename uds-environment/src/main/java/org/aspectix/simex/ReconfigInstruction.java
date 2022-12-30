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

import java.util.logging.Logger;

import org.aspectix.uds.UdsConfiguration;
import org.aspectix.uds.UdsScheduler;

/**
 * Instruction object that simulates CPU load.
 * 
 * @author Franz J. Hauck
 */
public class ReconfigInstruction extends Instruction {
    /**
     * Duration in nanoseconds that this instruction should keep CPU busy
     */
    private org.aspectix.uds.UdsConfiguration conf;

    /**
     * Logger
     */
    static final Logger logger = Logger.getLogger(SimulatedExecution.class.getName());

    /**
     * Constructor
     * 
     * @param conf UDS configuration object
     */
    public ReconfigInstruction(UdsConfiguration conf) {
        this.conf = conf;
    }

    /**
     * Execute function of the instruction
     */
    @Override
    public int execute(SimulatedExecution ex, int pc) {
        logger.finest(UdsScheduler.getThreadID() + " SimEx.Reconfig: to " + conf);
        ex.getEnv().scheduler.requestReconfiguration(conf);
        return pc + 1;
    }

    /**
     * toString method
     */
    @Override
    public String toString() {
        return "Reconfig(" + conf + ")";
    }
}
