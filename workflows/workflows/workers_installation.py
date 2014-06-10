########
# Copyright (c) 2014 GigaSpaces Technologies Ltd. All rights reserved
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#        http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
#    * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#    * See the License for the specific language governing permissions and
#    * limitations under the License.

__author__ = 'ran'


from cloudify.decorators import workflow
from cloudify.workflows.tasks_graph import TaskDependencyGraph, forkjoin


WORKER_PAYLOAD = {
    'properties': {
        'worker_config': {
            'workflows_worker': True
        }
    }
}


@workflow
def install(ctx, **kwargs):

    graph = TaskDependencyGraph(ctx)

    sequence = graph.sequence()

    plugins = kwargs['management_plugins_to_install']

    sequence.add(
        ctx.send_event('Installing deployment operations worker'),
        ctx.execute_task(
            task_queue='cloudify.management',
            task_name='worker_installer.tasks.install'),
        ctx.send_event('Starting deployment operations worker'),
        ctx.execute_task(
            task_queue='cloudify.management',
            task_name='worker_installer.tasks.start'),
        ctx.send_event('Installing deployment operations plugins'),
        ctx.execute_task(
            task_queue=ctx.deployment_id,
            task_name='plugin_installer.tasks.install',
            kwargs={'properties': plugins}),
        ctx.execute_task(
            task_queue='cloudify.management',
            task_name='worker_installer.tasks.restart'))

    sequence.add(
        ctx.send_event('Installing deployment workflows worker'),
        ctx.execute_task(
            task_queue='cloudify.management',
            task_name='worker_installer.tasks.install',
            kwargs=WORKER_PAYLOAD),
        ctx.send_event('Starting deployment workflows worker'),
        ctx.execute_task(
            task_queue='cloudify.management',
            task_name='worker_installer.tasks.start',
            kwargs=WORKER_PAYLOAD),
        ctx.send_event('Installing deployment workflows plugins'),
        ctx.execute_task(
            task_queue='{0}_workflows'.format(ctx.deployment_id),
            task_name='plugin_installer.tasks.install',
            kwargs={'properties': plugins}),
        ctx.execute_task(
            task_queue='cloudify.management',
            task_name='worker_installer.tasks.restart',
            kwargs=WORKER_PAYLOAD))

    graph.execute()


@workflow
def uninstall(ctx, **kwargs):

    graph = TaskDependencyGraph(ctx)

    sequence = graph.sequence()

    sequence.add(
        ctx.send_event('Stopping deployment operations worker'),
        ctx.execute_task(
            task_queue='cloudify.management',
            task_name='worker_installer.tasks.stop'),
        ctx.send_event('Uninstalling deployment operations worker'),
        ctx.execute_task(
            task_queue='cloudify.management',
            task_name='worker_installer.tasks.uninstall'))

    sequence.add(
        ctx.send_event('Stopping deployment workflows worker'),
        ctx.execute_task(
            task_queue='cloudify.management',
            task_name='worker_installer.tasks.stop',
            kwargs=WORKER_PAYLOAD),
        ctx.send_event('Uninstall deployment workflows worker'),
        ctx.execute_task(
            task_queue='cloudify.management',
            task_name='worker_installer.tasks.uninstall',
            kwargs=WORKER_PAYLOAD))

    graph.execute()
