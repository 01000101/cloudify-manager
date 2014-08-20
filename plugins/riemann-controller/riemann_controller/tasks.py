#########
# Copyright (c) 2013 GigaSpaces Technologies Ltd. All rights reserved
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#       http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
#  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  * See the License for the specific language governing permissions and
#  * limitations under the License.


import os
from os import path
import time
import errno
import json

import requests
import pika

from cloudify import ctx
from cloudify.decorators import operation
from cloudify.exceptions import NonRecoverableError

from riemann_controller import config

RIEMANN_CONFIGS_DIR = 'RIEMANN_CONFIGS_DIR'


@operation
def create(policy_types=None,
           policy_triggers=None,
           groups=None,
           **_):
    policy_types = policy_types or {}
    groups = groups or {}

    if groups:
        policy_triggers = policy_triggers or {
            'execute_workflow': {
                'source': 'file://{}'.format(
                    path.join(path.dirname(__file__),
                              'resources',
                              'execute_workflow'))
            }
        }
        for group in groups.values():
            for policy in group['policies'].values():
                policy['triggers'] = {
                    'threshold_exceeded_workflow': {
                        'type': 'execute_workflow',
                        'parameters': {
                            'workflow': 'threshold_exceeded',
                            'workflow_parameters': {},
                            'socket_timeout': 1000,
                            'conn_timeout': 1000
                        }
                    }
                }
    else:
        policy_triggers = {}

    _process_sources(policy_triggers)
    _process_sources(policy_types)
    deployment_config_dir_path = _deployment_config_dir()
    if not os.path.isdir(deployment_config_dir_path):
        os.makedirs(deployment_config_dir_path)
        os.chmod(deployment_config_dir_path, 0777)
    with open(_deployment_config_template()) as f:
        deployment_config_template = f.read()
    with open(path.join(deployment_config_dir_path,
                        'deployment.config'), 'w') as f:
        f.write(config.create(ctx,
                              policy_types,
                              policy_triggers,
                              groups,
                              deployment_config_template))
    with open(os.path.join(_deployment_config_dir(), 'groups'), 'w') as f:
        f.write(json.dumps(groups))
    _publish_configuration_event('start', deployment_config_dir_path)
    _verify_core_up(deployment_config_dir_path,
                    timeout=ctx.bootstrap_context.policy_engine.start_timeout)


@operation
def delete(**_):
    deployment_config_dir_path = _deployment_config_dir()
    _publish_configuration_event('stop', deployment_config_dir_path)


def _deployment_config_dir():
    return os.path.join(os.environ[RIEMANN_CONFIGS_DIR],
                        ctx.deployment_id)


def _publish_configuration_event(state, deployment_config_dir_path):
    manager_queue = 'manager-riemann'
    connection = pika.BlockingConnection()
    try:
        channel = connection.channel()
        channel.queue_declare(
            queue=manager_queue,
            auto_delete=True,
            durable=False,
            exclusive=False)
        channel.basic_publish(
            exchange='',
            routing_key=manager_queue,
            body=json.dumps({
                'service': 'cloudify.configuration',
                'state': state,
                'config_path': deployment_config_dir_path,
                'deployment_id': ctx.deployment_id,
                'time': int(time.time())
            }))
    finally:
        connection.close()


def _deployment_config_template():
    return path.abspath(path.join(path.dirname(__file__),
                                  'resources',
                                  'deployment.config.template'))


def _verify_core_up(deployment_config_dir_path, timeout):
    if timeout is None:
        timeout = 30
    ok_path = path.join(deployment_config_dir_path, 'ok')
    end = time.time() + timeout
    while time.time() < end:
        try:
            # after the core is started this file is written as an indication
            with (open(ok_path)) as f:
                assert f.read().strip() == 'ok'
            return
        except IOError, e:
            if e.errno in [errno.ENOENT]:
                time.sleep(0.5)
            else:
                raise
    raise NonRecoverableError('Riemann core was has not started in {} seconds'
                              .format(timeout))


def _process_sources(sources):
    for source in sources.values():
        source['source'] = _process_source(source['source'])


def _process_source(source):
    split = source.split('://')
    schema = split[0]
    the_rest = ''.join(split[1:])
    if schema in ['http', 'https']:
        return requests.get(source).text
    elif schema == 'file' and the_rest:
        with open(the_rest) as f:
            return f.read()
    else:
        return ctx.get_resource(source)
