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

import java.util.Random;
import java.util.logging.Logger;

public class LockInstruction extends Instruction {

    /**
     * Lower ID of lock range
     */
    private int lowLockId;

    /**
     * Higher ID of lock range
     */
    private int highLockId;

    /**
     * Create LockInstruction for a single lock
     * 
     * @param lockId the lock to be locked
     */
    public LockInstruction(int lockId) {
        this(lockId, lockId);
    }

    /**
     * Random generator
     */
    protected Random rnd = new Random();

    /**
     * Logger
     */
    static final Logger logger = Logger.getLogger(SimulatedExecution.class.getName());

    /**
     * Create LockInstruction for a range of locks that are randomly locked
     * 
     * LockIds are usable in the range of 0 to 63
     * 
     * @param lowLockId  lower ID of the lock range
     * @param highLockId higher ID of the lock range
     */
    public LockInstruction(int lowLockId, int highLockId) {
        this.lowLockId = lowLockId;
        this.highLockId = highLockId;
    }

    /**
     * Lock a lock
     */
    @Override
    public int execute(SimulatedExecution se, int pc) {
        ExecutionEnvironment env = se.getEnv();
        String prefix = UdsScheduler.getThreadID() + " SimEx.Locking ";

        if (lowLockId > highLockId || highLockId >= env.locks.length) {
            throw new IllegalArgumentException("Lock numbers out of range");
        }
        if (lowLockId != highLockId) {
            // take random lock in [lowLock, highLock)
            lowLockId = rnd.nextInt(highLockId - lowLockId) + lowLockId;
        }
        logger.finer( prefix + env.locks[lowLockId]);
        env.locks[lowLockId].lock();
        env.takenLocks.add(env.locks[lowLockId]);
        env.takenLocksCount++;
        return pc + 1;
    }

    /**
     * toString method
     */
    @Override
    public String toString() {
        return "Lock(" + lowLockId + "," + highLockId + ")";
    }
}
