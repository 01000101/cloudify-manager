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
package org.cloudifysource.cosmo.tasks.producer;

import org.cloudifysource.cosmo.tasks.messages.TaskMessage;

/**
 * TODO: Write a short summary of this type's roles and responsibilities.
 *
 * @author Idan Moyal
 * @since 0.1
 */
public interface TaskProducerListener {

    void onTaskUpdateReceived(TaskMessage result);
    void onFailure(Throwable t);

}
