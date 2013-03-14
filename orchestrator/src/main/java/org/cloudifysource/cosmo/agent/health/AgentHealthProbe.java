/*******************************************************************************
 * Copyright (c) 2013 GigaSpaces Technologies Ltd. All rights reserved
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/

package org.cloudifysource.cosmo.agent.health;

import java.net.URI;
import java.util.Map;

/**
 * Acts as a health probe for agents.
 *
 * @author Eitan Yanovsky
 * @since 0.1
 */
public interface AgentHealthProbe {

    /**
     * returns the monitored agents health status, this method should not block on IO and return immediately,
     * usually with a cached result which is refreshed frequently. It is up to the implementation to decide when this
     * result is updated.
     * @return monitored agents health status, true means the agent is considered unreachable
     */
    Map<URI, Boolean> getAgentsHealthStatus();

    /**
     * Specify which agents needs to be monitored for health status
     * monitored.
     * @param agentsIds the list of agents ids to monitor
     */
    void monitorAgents(Iterable<URI> agentsIds);
}
