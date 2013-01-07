package org.openspaces.servicegrid.model.tasks;

import java.net.URL;

public class Task {

	private URL target;
	
	private URL impersonatedTarget;

	private URL source;

	private long sourceTimestamp;

	public URL getSource() {
		return source;
	}

	public URL getImpersonatedTarget() {
		return impersonatedTarget;
	}

	public void setTarget(URL target) {
		this.target = target;
	}
	
	public URL getTarget() {
		return target;
	}

	public void setImpersonatedTarget(URL impersonatedTarget) {
		this.impersonatedTarget = impersonatedTarget;
	}
	
	public void setSource(URL source) {
		this.source = source;
	}

	public long getSourceTimestamp() {
		return sourceTimestamp;
	}

	public void setSourceTimestamp(long sourceTimestamp) {
		this.sourceTimestamp = sourceTimestamp;
	}
}
