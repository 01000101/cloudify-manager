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
package org.cloudifysource.cosmo.agent.state;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.common.base.Preconditions;
import org.cloudifysource.cosmo.LifecycleStateMachine;
import org.cloudifysource.cosmo.TaskConsumerState;

import java.net.URI;
import java.util.List;

/**
 * State of the Agent TaskConsumer.
 * @author Itai Frenkel
 * @since 0.1
 */
public class AgentState extends TaskConsumerState {

    private static final String MACHINE_UNREACHABLE = "machine_unreachable";
    private static final String MACHINE_TERMINATED = "machine_terminated";
    private static final String MACHINE_REACHABLE = "machine_reachable";
    private static final String MACHINE_STARTED = "machine_started";

    public AgentState() {
        stateMachine = new LifecycleStateMachine(
                MACHINE_UNREACHABLE + "->" + MACHINE_TERMINATED + "<->" + MACHINE_STARTED + "->" +
                MACHINE_REACHABLE + "->" + MACHINE_TERMINATED);
        stateMachine.setFinalLifecycle(MACHINE_REACHABLE);
        stateMachine.setInitialLifecycle(MACHINE_TERMINATED);
    }

    private LifecycleStateMachine stateMachine;

    private String lifecycle;
    private String ipAddress;
    private List<URI> serviceInstanceIds;
    private int numberOfAgentStarts;
    private int numberOfMachineStarts;
    private long lastPingSourceTimestamp;

    /**
     * Use isLifecycle(x or y or z) instead.
     * This is to encourage using the pattern of positive lifecycle checks such as "isLifecycle(y)"
     * instead of negative lifecycle checks such as (!getLifecycle().equals(x))
     */
    @Deprecated
    public String getLifecycle() {
        return lifecycle;
    }

    /**
     * @return true if {@code #getLifecycle()} matches any of the specified options.
     */
    public boolean isLifecycle(String... expectedProgresses) {
        for (String expectedProgress : expectedProgresses) {
            if (lifecycle != null && lifecycle.equals(expectedProgress)) {
                return true;
            }
        }
        return false;
    }

    public void setLifecycle(String lifecycle) {
        this.lifecycle = lifecycle;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setServiceInstanceIds(List<URI> serviceInstanceIds) {
        this.serviceInstanceIds = serviceInstanceIds;
    }

    public List<URI> getServiceInstanceIds() {
        return serviceInstanceIds;
    }

    public int getNumberOfAgentStarts() {
        return numberOfAgentStarts;
    }

    public void setNumberOfAgentStarts(int numberOfAgentStarts) {
        this.numberOfAgentStarts = numberOfAgentStarts;
    }

    public int getNumberOfMachineStarts() {
        return numberOfMachineStarts;
    }

    public void setNumberOfMachineStarts(int numberOfMachineStarts) {
        this.numberOfMachineStarts = numberOfMachineStarts;
    }

    @JsonIgnore
    public void removeServiceInstanceId(final URI instanceId) {
        boolean removed = serviceInstanceIds.remove(instanceId);
        Preconditions.checkArgument(removed, "Cannot remove instance %s", instanceId);
    }

    public long getLastPingSourceTimestamp() {
        return lastPingSourceTimestamp;
    }

    public void setLastPingSourceTimestamp(long lastPingSourceTimestamp) {
        this.lastPingSourceTimestamp = lastPingSourceTimestamp;
    }

    @JsonIgnore
    public void incrementNumberOfMachineStarts() {
        numberOfMachineStarts++;
    }

    @JsonIgnore
    public void incrementNumberOfAgentStarts() {
        numberOfAgentStarts++;
    }

    @JsonIgnore
    public void resetNumberOfAgentStarts() {
        numberOfAgentStarts = 0;
    }

    /**
     * @param expectedLifecycle - the expected lifecycle
     * @return - the next lifecycle that should bring us one step closer to expectedLifecyle
     *           expectedLifecycle if the current lifecycle equals the expected lifecycle.
     */
    @JsonIgnore
    public String getNextAgentLifecycle(String expectedLifecycle) {
        return stateMachine.getNextInstanceLifecycle(lifecycle, expectedLifecycle);
    }

    @JsonIgnore
    public String getMachineTerminatedLifecycle() {
        return MACHINE_TERMINATED;
    }

    @JsonIgnore
    public String getMachineReachableLifecycle() {
        return MACHINE_REACHABLE;
    }

    @JsonIgnore
    public String getMachineStartedLifecycle() {
        return MACHINE_STARTED;
    }

    @JsonIgnore
    public boolean isMachineTerminatedLifecycle() {
        return  isLifecycle(getMachineTerminatedLifecycle());
    }

    @JsonIgnore
    public boolean isMachineReachableLifecycle() {
        return  isLifecycle(getMachineReachableLifecycle());
    }

    @JsonIgnore
    public String getMachineUnreachableLifecycle() {
        return MACHINE_UNREACHABLE;
    }
}
