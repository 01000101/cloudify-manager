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
package org.cloudifysource.cosmo;

import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;
import org.cloudifysource.cosmo.mock.MockPlannerManagement;
import org.cloudifysource.cosmo.service.ServiceUtils;
import org.cloudifysource.cosmo.service.state.ServiceConfig;
import org.cloudifysource.cosmo.service.state.ServiceGridDeploymentPlan;
import org.cloudifysource.cosmo.service.state.ServiceGridDeploymentPlannerState;
import org.cloudifysource.cosmo.service.tasks.InstallServiceTask;
import org.cloudifysource.cosmo.service.tasks.ScaleServiceTask;
import org.cloudifysource.cosmo.service.tasks.UninstallServiceTask;
import org.cloudifysource.cosmo.state.StateReader;
import org.cloudifysource.cosmo.streams.StreamUtils;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.net.URI;

public class ServiceGridDeploymentPlannerTest extends AbstractServiceGridTest<MockPlannerManagement> {

    @Override
    protected MockPlannerManagement createMockManagement() {
        return new MockPlannerManagement();
    }

    /**
     * Tests deployment of 1 instance
     */
    @Test
    public void installSingleInstanceServiceTest() {
        Assert.assertTrue(Iterables.isEmpty(getAgentIds()));
        installService("tomcat", 1);
        execute();
        assertOneTomcatInstance();
        uninstallService("tomcat");
        execute();
        assertTomcatUninstalledGracefully();
    }

    /**
     * Tests deployment of 2 instances
     */
    @Test
    public void installMultipleInstanceServiceTest() {
        installService("tomcat", 2);
        execute();
        assertTwoTomcatInstances();
        uninstallService("tomcat");
        execute();
        assertTomcatUninstalledGracefully();
    }

    /**
     * Tests change in plan from 1 instance to 2 instances
     */
    @Test
    public void scaleOutServiceTest() {
        installService("tomcat", 1);
        execute();
        scaleService("tomcat",2);
        execute();
        assertTwoTomcatInstances();
        uninstallService("tomcat");
        execute();
        assertTomcatUninstalledGracefully();
    }

    /**
     * Tests change in plan from 1 instance to 2 instances
     */
    @Test
    public void scaleInServiceTest() {
        installService("tomcat", 2);
        execute();
        scaleService("tomcat",1);
        execute();
        assertTomcatScaledInFrom2To1();
        uninstallService("tomcat");
        execute();
        assertTomcatUninstalledGracefully();
    }

    /**
     * Tests management state recovery from crash
     */
    @Test
    public void managementRestartTest() {
        installService("tomcat", 1);
        execute();
        restartManagement();
        execute();
        assertOneTomcatInstance();
        uninstallService("tomcat");
        execute();
        assertTomcatUninstalledGracefully();
    }

    /**
     * Install two services, each with one instance
     */
    @Test
    public void installTwoSingleInstanceServicesTest(){
        installService("tomcat", 1);
        installService("cassandra", 1);
        execute();
        assertServiceInstalledWithOneInstance("tomcat", 0);
        assertServiceInstalledWithOneInstance("cassandra", 1);
        uninstallService("tomcat");
        uninstallService("cassandra");
        execute();
        assertTomcatUninstalledGracefully();
    }

    private void assertOneTomcatInstance() {
        assertSingleServiceInstance("tomcat");
    }

    private void assertTomcatScaledInFrom2To1() {
        assertServiceInstalledWithOneInstance("tomcat");
    }

    private void assertTomcatUninstalledGracefully() {
        final URI serviceId = getServiceId("tomcat");
        Assert.assertFalse(getDeploymentPlannerState().getDeploymentPlan().isServiceExists(serviceId));
    }

    private void assertServiceInstalledWithOneInstance(String serviceName) {
        assertServiceInstalledWithOneInstance(serviceName, 0);
    }

    private void assertSingleServiceInstance(String serviceName) {
        final int zeroAgentRestarts = 0;
        final int zeroMachineRestarts = 0;
        assertSingleServiceInstance(serviceName, zeroAgentRestarts,zeroMachineRestarts);
    }

    private void assertSingleServiceInstance(String serviceName, int numberOfAgentRestarts, int numberOfMachineRestarts) {
        Assert.assertNotNull(getDeploymentPlannerState());
        Assert.assertEquals(getDeploymentPlannerState().getDeploymentPlan().getServices().size(), 1);
    }

    private void assertServiceInstalledWithOneInstance(
            String serviceName, int agentIndex) {
        final URI serviceId = getServiceId(serviceName);
        final URI instanceId = ServiceUtils.newInstanceId(serviceId, 0);
        final URI agentId = ServiceUtils.newAgentId(getManagement().getAgentsId(), agentIndex);
        final ServiceGridDeploymentPlannerState plannerState = getDeploymentPlannerState();
        final ServiceGridDeploymentPlan deploymentPlan = plannerState.getDeploymentPlan();
        Assert.assertEquals(Iterables.getOnlyElement(deploymentPlan.getInstanceIdsByAgentId(agentId)), instanceId);
        Assert.assertEquals(Iterables.getOnlyElement(deploymentPlan.getInstanceIdsByServiceId(serviceId)), instanceId);
        final ServiceConfig serviceConfig = deploymentPlan.getServiceById(serviceId).getServiceConfig();
        Assert.assertEquals(serviceConfig.getServiceId(), serviceId);
    }

    private ServiceGridDeploymentPlannerState getDeploymentPlannerState() {
        return getStateReader().get(getManagement().getDeploymentPlannerId(), ServiceGridDeploymentPlannerState.class).getState();
    }

    private void assertTwoTomcatInstances() {
        final URI serviceId = getServiceId("tomcat");

        final ServiceGridDeploymentPlannerState plannerState = getDeploymentPlannerState();
        final ServiceGridDeploymentPlan deploymentPlan = plannerState.getDeploymentPlan();
        Assert.assertEquals(Iterables.getOnlyElement(deploymentPlan.getServices()).getServiceConfig().getServiceId(), serviceId);
        Assert.assertEquals(Iterables.size(deploymentPlan.getInstanceIdsByServiceId(serviceId)), 2);
    }

    private void installService(String name, int numberOfInstances) {
        final int minNumberOfInstances = 1;
        final int maxNumberOfInstances = 2;
        ServiceConfig serviceConfig = new ServiceConfig();
        serviceConfig.setDisplayName(name);
        serviceConfig.setPlannedNumberOfInstances(numberOfInstances);
        serviceConfig.setMaxNumberOfInstances(maxNumberOfInstances);
        serviceConfig.setMinNumberOfInstances(minNumberOfInstances);
        serviceConfig.setServiceId(getServiceId(name));
        final InstallServiceTask installServiceTask = new InstallServiceTask();
        installServiceTask.setServiceConfig(serviceConfig);
        submitTask(getManagement().getDeploymentPlannerId(), installServiceTask);
    }

    private void uninstallService(String name) {
        URI serviceId = getServiceId(name);
        final UninstallServiceTask uninstallServiceTask = new UninstallServiceTask();
        uninstallServiceTask.setServiceId(serviceId);
        submitTask(getManagement().getDeploymentPlannerId(), uninstallServiceTask);
    }

    private void scaleService(String serviceName, int plannedNumberOfInstances) {
        final ScaleServiceTask scaleServiceTask = new ScaleServiceTask();
        URI serviceId = getServiceId(serviceName);
        scaleServiceTask.setServiceId(serviceId);
        scaleServiceTask.setPlannedNumberOfInstances(plannedNumberOfInstances);
        scaleServiceTask.setProducerTimestamp(currentTimeMillis());
        submitTask(getManagement().getDeploymentPlannerId(), scaleServiceTask);
    }

    private void execute() {
        super.execute(getManagement().getDeploymentPlannerId());
    }

    private Iterable<URI> getStateIdsStartingWith(final URI uri) {
        return Iterables.filter(
                getStateReader().getElementIdsStartingWith(uri),
                new Predicate<URI>(){

                    @Override
                    public boolean apply(URI stateId) {
                        return stateId.toString().endsWith("/");
                    }});
    }


    private Iterable<URI> getAgentIds() {
        return getStateIdsStartingWith(StreamUtils.newURI(getManagement().getStateServerUri() + "agents/"));
    }

    public StateReader getStateReader() {
        return getManagement().getStateReader();
    }
}
