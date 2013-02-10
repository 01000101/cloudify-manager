package org.openspaces.servicegrid.state;

import javax.ws.rs.core.EntityTag;

import com.google.common.base.Preconditions;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;
import com.sun.jersey.api.client.ClientResponse;

public class Etag {

	public static Etag EMPTY = create("EMPTY");

	private final EntityTag entityTag;
	
	/**
	 * For mocking
	 */
	private Etag(HashCode hash) {
		this.entityTag = new EntityTag(hash.toString());
	}

	/**
	 * For responses
	 */
	private Etag(EntityTag entityTag) {
		Preconditions.checkNotNull(entityTag);
		this.entityTag = entityTag;
	}

	/**
	 * For mocking
	 */
	public static Etag create(String input) {
		return new Etag(Hashing.md5().hashString(input));
	}
	
	@Override
	public int hashCode() {
		return entityTag.hashCode();
	}

	@Override
	public boolean equals(Object obj) {
		if (obj instanceof Etag) {
			Etag etag = (Etag) obj;
			return entityTag.equals(etag.entityTag);	
		}
		return false;
	}

	@Override
	public String toString() {
		return entityTag.toString();
	}

	public static Etag create(ClientResponse response) {
		EntityTag responseEtag = response.getEntityTag();
		if (responseEtag == null) {
			return Etag.EMPTY;
		}
		return new Etag(responseEtag);
	}
	
	public static Etag empty() {
		return Etag.EMPTY;
	}
}
