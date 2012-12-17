package org.openspaces.servicegrid.model;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.beust.jcommander.internal.Sets;
import com.google.common.collect.Maps;

public class Task {

	private String type;
	private Map<String,Object> properties = Maps.newHashMap();
	private Set<String> tags = Sets.newHashSet();

	public String getType() {
		return type;
	}

	public void setType(String type) {
		this.type = type;	
	}

	public void setProperty(String key, Object value) {
		this.properties.put(key, value);
	}

	public <T> T getProperty(String key, Class<? extends T> clazz) {
		return (T) properties.get(key);
	}

	public void setTags(Set<String> tags) {
		this.tags = tags;
	}

	public Set<String> getTags() {
		return this.tags;
	}
}
