package org.openspaces.servicegrid;

public class TaskProducerTask extends Task {

	private int maxNumberOfSteps = 1;

	public int getMaxNumberOfSteps() {
		return maxNumberOfSteps;
	}

	public void setMaxNumberOfSteps(
			int maxNumberOfSteps) {
		this.maxNumberOfSteps = maxNumberOfSteps;
	}

}
