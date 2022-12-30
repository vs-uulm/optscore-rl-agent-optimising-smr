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
import java.util.List;

/**
 * A structure defining the context of probe instructions
 */
public class TraceContext {
    /**
     * An ID distinguishing different context
     */
    public String contextId;

    /**
     * A trace describing context behaviour
     */
    public List<String> trace = new ArrayList<>();

    /**
     * Constructor
     * 
     * @param contextId the context name or ID
     */
    public TraceContext(String contextId) {
        if (contextId == null || contextId == "") {
            throw new IllegalArgumentException("Context needs to be a non empty string");
        }
        this.contextId = contextId;
    }
}
