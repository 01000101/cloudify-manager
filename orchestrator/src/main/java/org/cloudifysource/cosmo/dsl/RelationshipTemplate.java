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

package org.cloudifysource.cosmo.dsl;

import com.beust.jcommander.internal.Lists;

import java.util.List;

/**
 * TODO: Write a short summary of this type's roles and responsibilities.
 *
 * @author Dan Kilman
 * @since 0.1
 */
public class RelationshipTemplate {

    private String type;
    private String target;
    private boolean lateBinding;
    private List<String> executionOrder = Lists.newArrayList();

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getTarget() {
        return target;
    }

    public void setTarget(String target) {
        this.target = target;
    }

    public boolean isLateBinding() {
        return lateBinding;
    }

    public void setLateBinding(boolean lateBinding) {
        this.lateBinding = lateBinding;
    }

    public List<String> getExecutionOrder() {
        return executionOrder;
    }

    public void setExecutionOrder(List<String> executionOrder) {
        this.executionOrder = executionOrder;
    }

}
