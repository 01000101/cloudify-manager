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

package org.cloudifysource.cosmo.statecache;

import org.cloudifysource.cosmo.logging.Logger;
import org.cloudifysource.cosmo.logging.LoggerFactory;
import org.cloudifysource.cosmo.messaging.consumer.MessageConsumer;
import org.cloudifysource.cosmo.messaging.consumer.MessageConsumerListener;
import org.cloudifysource.cosmo.statecache.messages.StateChangedMessage;

import java.net.URI;
import java.util.Map;

/**
 * Holds a cache of the distributed system state. The state
 * is updated in real-time by listening to {@link org.cloudifysource.cosmo.statecache.messages.StateChangedMessage}s
 *
 * @author Eitan Yanovsky
 * @since 0.1
 */
public class RealTimeStateCache implements StateCacheReader {

    protected final Logger logger = LoggerFactory.getLogger(this.getClass());

    private final MessageConsumer consumer;
    private final URI messageTopic;
    private final StateCache stateCache;
    private final MessageConsumerListener messageConsumerListener;

    public RealTimeStateCache(URI messageTopic, MessageConsumer messageConsumer, StateCache stateCache) {
        this.consumer = messageConsumer;
        this.messageTopic = messageTopic;
        this.stateCache = stateCache;

        this.messageConsumerListener = new MessageConsumerListener() {
            @Override
            public void onMessage(URI uri, Object message) {
                if (message instanceof StateChangedMessage) {
                    final StateChangedMessage update = (StateChangedMessage) message;
                    final String resourceId = update.getResourceId();
                    final Map<String, Object> state = update.getState();
                    for (Map.Entry<String, Object> entry : state.entrySet()) {
                        RealTimeStateCache.this.stateCache.put(resourceId, entry.getKey(),
                                entry.getValue().toString());
                    }
                } else {
                    throw new IllegalArgumentException("Cannot handle message " + message);
                }
            }

            @Override
            public void onFailure(Throwable t) {
                RealTimeStateCache.this.messageConsumerFailure(t);
            }
        };
        this.consumer.addListener(messageTopic, messageConsumerListener);
    }

    @Override
    public void subscribe(String resourceId, StateCacheListener listener) {
        this.stateCache.subscribe(resourceId, listener);
    }

    @Override
    public void removeSubscription(String resourceId, String listenerId) {
        this.stateCache.removeSubscription(resourceId, listenerId);
    }

    public void close() {
        this.consumer.removeListener(messageConsumerListener);
    }

    private void messageConsumerFailure(Throwable t) {
        logger.warn(StateCacheLogDescription.MESSAGE_CONSUMER_ERROR, t);
    }

}
