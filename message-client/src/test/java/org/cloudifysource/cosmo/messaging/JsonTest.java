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
 *******************************************************************************/

package org.cloudifysource.cosmo.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.cloudifysource.cosmo.messaging.messages.MockDynamicMessage;
import org.cloudifysource.cosmo.messaging.messages.MockMessage;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.HashMap;

import static org.fest.assertions.api.Assertions.assertThat;

/**
 * Tests JSON serialization/deserialization of messages.
 * @see MessageMixin
 *
 * @author itaif
 * @since 0.1
 */
public class JsonTest {

    ObjectMapper mapper;
    @BeforeMethod
    public void initMapper() {
        mapper = ObjectMapperFactory.newObjectMapper();
    }

    @Test
    public void testSerialization() throws IOException {

        final MockMessage message = new MockMessage();
        message.setValue(1);
        final String json = mapper.writerWithType(message.getClass()).writeValueAsString(message);
        final MockMessage message2 = (MockMessage) mapper.readValue(json, Object.class);
        assertThat(message2).isEqualTo(message);
    }

    @Test
    public void testDynamicSerialization() throws IOException {

        final MockDynamicMessage message = new MockDynamicMessage();
        final HashMap<String, Object> state = new HashMap<String, Object>();
        state.put("reachable", true);
        message.setState(state);
        final String json = mapper.writerWithType(message.getClass()).writeValueAsString(message);
        final MockDynamicMessage message2 = (MockDynamicMessage) mapper.readValue(json, Object.class);
        assertThat(message2).isEqualTo(message);
    }
}
