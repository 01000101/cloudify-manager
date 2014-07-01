#########
# Copyright (c) 2014 GigaSpaces Technologies Ltd. All rights reserved
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

from functools import wraps

from cloudify import utils
from cloudify.context import CloudifyContext

from windows_agent_installer.winrm_runner import WinRMRunner


__author__ = 'elip'


def init_worker_installer(func):

    @wraps(func)
    def wrapper(*args, **kwargs):
        ctx = utils.find_type_in_kwargs(CloudifyContext, kwargs.values() + list(args))
        if not ctx:
            raise RuntimeError('CloudifyContext not found in invocation args')
        if ctx.properties and 'cloudify_agent' in ctx.properties:
            cloudify_agent = ctx.properties['cloudify_agent']
        else:
            cloudify_agent = {}
        prepare_configuration(ctx, cloudify_agent)
        kwargs['cloudify_agent'] = cloudify_agent
        kwargs['runner'] = WinRMRunner(session_config=cloudify_agent, logger=ctx.logger)
        return func(*args, **kwargs)
    return wrapper


def prepare_configuration(ctx, cloudify_agent):

    # runtime info

    cloudify_agent['name'] = ctx.node_id
    cloudify_agent['host'] = get_machine_ip(ctx)

def get_machine_ip(ctx):
    if 'ip' in ctx.properties:
        return ctx.properties['ip']  # priority for statically specifying ip.
    if 'ip' in ctx.runtime_properties:
        return ctx.runtime_properties['ip']
    raise ValueError('ip property is not set for node: {0}. '
                     'This is mandatory for installing an agent remotely'
    .format(ctx.node_id))


