package org.openspaces.servicegrid.streams;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonGenerationException;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategy;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.guava.GuavaModule;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;

public class StreamUtils {

	public static <T> T cloneElement(ObjectMapper mapper, T state) {
	
		@SuppressWarnings("unchecked")
		Class<? extends T> clazz = (Class<? extends T>) state.getClass();
		return (T) fromJson(mapper,toJson(mapper, state), clazz);
	}
	
	public static String toJson(ObjectMapper mapper, Object state) {
		try {
			return mapper.writeValueAsString(state);
		} catch (JsonProcessingException e) {
			throw Throwables.propagate(e);
		}
	}
	

	public static <T> T fromJson(ObjectMapper mapper, String json, Class<? extends T> clazz) {
		try {
			return (T) mapper.readValue(json, clazz);
		} catch (JsonParseException e) {
			throw Throwables.propagate(e);
		} catch (JsonMappingException e) {
			throw Throwables.propagate(e);
		} catch (JsonGenerationException e) {
			throw Throwables.propagate(e);
		} catch (IOException e) {
			throw Throwables.propagate(e);
		}
	}
	
	public static <T> boolean elementEquals(ObjectMapper mapper, T state1, T state2) {
		try {
			final String state1String = mapper.writeValueAsString(state1);
			final String state2String = mapper.writeValueAsString(state2);
			return state1String.equals(state2String);
		} catch (JsonGenerationException e) {
			throw Throwables.propagate(e);
		} catch (JsonMappingException e) {
			throw Throwables.propagate(e);
		} catch (IOException e) {
			throw Throwables.propagate(e);
		}
	}
	
	/**
	 * @return joined list of ids maintaining order, removing duplicates
	 */
	public static Iterable<URI> concat(final Iterable<URI> ids1, final Iterable<URI> ids2) {
		return ImmutableSet.copyOf(Iterables.concat(ids1, ids2));
	}
	
	/**
	 * @return old ids that are not in the newIds, maintaining order, removing duplicates. 
	 */
	public static Iterable<URI> diff(final Iterable<URI> oldIds, final Iterable<URI> newIds) {
		final Set<URI> idsToFilter = Sets.newHashSet(newIds);
		final Iterable<URI> diffWithDuplicates =
			Iterables.filter(oldIds, new Predicate<URI>(){
	
				@Override
				public boolean apply(URI id) {
					return !idsToFilter.contains(id);
				}
			});
		return ImmutableSet.copyOf(diffWithDuplicates);
	}
	
	public static URI newURI(String URI) {
		try {
			return new URI(URI);
		} catch (final URISyntaxException e) {
			throw Throwables.propagate(e);
		}
	}
	
	public static URI fixSlash(URI id) {
		Preconditions.checkNotNull(id);
		String externalForm = id.toString();
		if (!externalForm.endsWith("/")) {
			externalForm += "/";
		}
		return StreamUtils.newURI(externalForm);
	}

	public static ObjectMapper newObjectMapper() {
		ObjectMapper mapper = new ObjectMapper();
		mapper.registerModule(new GuavaModule());
		mapper.setPropertyNamingStrategy(PropertyNamingStrategy.CAMEL_CASE_TO_LOWER_CASE_WITH_UNDERSCORES);
		mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
		mapper.configure(SerializationFeature.INDENT_OUTPUT, true);
		
		//getDeserializationConfig().with(DeserializationFeature.)
		return mapper;
	}
}
