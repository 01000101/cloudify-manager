package org.openspaces.servicegrid.service.tasks;

import java.net.URI;

import org.openspaces.servicegrid.Task;

public class ScaleOutServiceTask extends Task {

	private int plannedNumberOfInstances;
	private URI serviceId;

	public void setPlannedNumberOfInstances(int plannedNumberOfInstances) {
		this.plannedNumberOfInstances = plannedNumberOfInstances;
	}
	
	public int getPlannedNumberOfInstances() {
		return plannedNumberOfInstances;
	}

	public URI getServiceId() {
		return serviceId;
	}
	
	public void setServiceId(URI serviceId) {
		this.serviceId = serviceId;
	}
}
