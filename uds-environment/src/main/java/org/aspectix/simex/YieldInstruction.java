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
 * Yield instruction to consume a UDS critical operation (step)
 */
public class YieldInstruction extends Instruction {
    /**
     * Create YieldInstruction
     */
    public YieldInstruction() {
    }

    /**
     * Lock a lock
     */
    @Override
    public int execute(SimulatedExecution se, int pc) {
        UdsScheduler.yield();
        return pc + 1;
    }

    /**
     * toString method
     */
    @Override
    public String toString() {
        return "Yield";
    }
}
