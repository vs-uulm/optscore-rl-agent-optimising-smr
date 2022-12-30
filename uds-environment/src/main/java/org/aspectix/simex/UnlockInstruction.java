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

import org.aspectix.uds.UdsScheduler;

/**
 * Unlocking of one of the previos locks locked by a LockInstruction
 */
public class UnlockInstruction extends Instruction {
    /**
     * Lock instruction to unlock
     */
    int lockInstr;

    /**
     * Logger
     */
    static final Logger logger = Logger.getLogger(SimulatedExecution.class.getName());

    /**
     * Constructor
     * 
     * @param lockInstr zero is last lock, one the one before, etc.
     */    
    public UnlockInstruction(int lockInstr) {
        this.lockInstr = lockInstr;
    }

    /**
     * Execute unlock instruction
     * 
     * @param ex The execution environment
     */
    @Override
    public int execute(SimulatedExecution se, int pc) {
        ExecutionEnvironment env = se.getEnv();
        String prefix = UdsScheduler.getThreadID() + " SimEx.Unlocking ";
        
        if( lockInstr >= env.takenLocksCount ) {
            throw new IllegalArgumentException("Lock instruction out of range");
        }
        logger.finer( prefix + env.takenLocks.get(env.takenLocksCount-lockInstr-1));
        env.takenLocks.get(env.takenLocksCount-lockInstr-1).unlock();
        env.takenLocks.remove(env.takenLocksCount-lockInstr-1);
        env.takenLocksCount--;
        return pc+1;
    }

    /**
     * toString method
     */
    @Override
    public String toString() {
        return "Unlock(last"+(lockInstr>0 ? "-"+lockInstr : "")+")";
    }
}
