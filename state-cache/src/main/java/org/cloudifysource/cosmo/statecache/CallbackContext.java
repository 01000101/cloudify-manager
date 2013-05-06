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

/**
 * TODO javadoc.
 *
 * @since 0.1
 * @author Dan Kilman
 */
class CallbackContext {

    private final Object receiver;
    private final Object context;
    private final StateChangeCallback callback;
    private final Condition condition;

    public CallbackContext(Object receiver, Object context, StateChangeCallback callback, Condition condition) {
        this.receiver = receiver;
        this.context = context;
        this.callback = callback;
        this.condition = condition;
    }

    public StateChangeCallback getCallback() {
        return callback;
    }

    public Object getContext() {
        return context;
    }

    public Object getReceiver() {
        return receiver;
    }

    public Condition getCondition() {
        return condition;
    }
}