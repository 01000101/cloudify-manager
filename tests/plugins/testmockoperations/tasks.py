########
# Copyright (c) 2013 GigaSpaces Technologies Ltd. All rights reserved
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

import time
from cloudify.decorators import operation
from cloudify.exceptions import NonRecoverableError
import tempfile
import os
import shutil
from cloudify.manager import get_rest_client

state = []
touched_time = None
unreachable_call_order = []
mock_operation_invocation = []
node_states = []
get_resource_operation_invocation = []
monitoring_operations_invocation = []
failure_invocation = []
host_get_state_invocation = []


@operation
def make_reachable(ctx, **kwargs):
    global state
    state_info = {
        'id': ctx.node_id,
        'time': time.time(),
        'capabilities': ctx.capabilities.get_all()
    }
    ctx.logger.info('Appending to state [node_id={0}, state={1}]'
                    .format(ctx.node_id, state_info))
    state.append(state_info)


@operation
def make_unreachable(ctx, **kwargs):
    global unreachable_call_order
    unreachable_call_order.append({
        'id': ctx.node_id,
        'time': time.time()
    })


@operation
def set_property(ctx, **kwargs):
    property_name = ctx.properties['property_name']
    value = ctx.properties['value']
    ctx.logger.info('Setting property [{0}={1}] for node: {2}'
                    .format(property_name, value, ctx.node_id))
    ctx.runtime_properties[property_name] = value


@operation
def touch(**kwargs):
    global touched_time
    touched_time = time.time()


@operation
def get_state(**kwargs):
    return state


@operation
def get_touched_time(**kwargs):
    return touched_time


@operation
def is_unreachable_called(node_id, **kwargs):
    return next((x for x in
                 unreachable_call_order if x['id'] == node_id), None)


@operation
def get_unreachable_call_order(**kwargs):
    return unreachable_call_order


@operation
def start_monitor(ctx, **kwargs):
    global monitoring_operations_invocation
    monitoring_operations_invocation.append({
        'id': ctx.node_id,
        'operation': 'start_monitor'
    })


@operation
def stop_monitor(ctx, **kwargs):
    global monitoring_operations_invocation
    monitoring_operations_invocation.append({
        'id': ctx.node_id,
        'operation': 'stop_monitor'
    })


@operation
def mock_operation(ctx, **kwargs):
    mockprop = ctx.properties['mockprop']
    global mock_operation_invocation
    mock_operation_invocation.append({
        'id': ctx.node_id,
        'mockprop': mockprop,
        'properties': {key: value for (key, value) in ctx.properties.items()}
    })


@operation
def mock_operation_from_custom_workflow(key, value, **_):
    mock_operation_invocation.append({
        key: value
    })


@operation
def get_resource_operation(ctx, **kwargs):
    resource_path = ctx.properties['resource_path']
    # trying to retrieve a resource
    res1 = ctx.download_resource(resource_path)
    if not res1:
        raise RuntimeError('Failed to get resource {0}'.format(resource_path))
    with open(res1, 'r') as f:
        res1_data = f.read()
    os.remove(res1)

    # trying to retrieve a resource to a specific location
    tempdir = tempfile.mkdtemp()
    try:
        filepath = os.path.join(tempdir, 'temp-resource-file')
        res2 = ctx.download_resource(resource_path, filepath)
        if not res2:
            raise RuntimeError('Failed to get resource {0} into {1}'.format(
                resource_path, filepath))
        with open(res2, 'r') as f:
            res2_data = f.read()
    finally:
        shutil.rmtree(tempdir)

    global get_resource_operation_invocation
    get_resource_operation_invocation.append({
        'res1_data': res1_data,
        'res2_data': res2_data,
        'custom_filepath': filepath,
        'res2_path': res2
    })


@operation
def get_resource_operation_invocations(**kwargs):
    return get_resource_operation_invocation


@operation
def get_mock_operation_invocations(**kwargs):
    return mock_operation_invocation


@operation
def get_monitoring_operations_invocation(**kwargs):
    return monitoring_operations_invocation


@operation
def append_node_state(ctx, **kwargs):
    client = get_rest_client()
    instance = client.node_instances.get(ctx.node_id)
    global node_states
    node_states.append(instance.state)


@operation
def get_node_states(**kwargs):
    global node_states
    return node_states


@operation
def sleep(ctx, **kwargs):
    sleep_time = ctx.properties['sleep'] if 'sleep' in ctx.properties \
        else kwargs['sleep']
    time.sleep(int(sleep_time))


@operation
def fail(ctx, **_):
    global failure_invocation
    failure_invocation.append(time.time())
    if len(failure_invocation) > ctx.properties.get('fail_count', 100000):
        return

    if ctx.properties.get('non_recoverable'):
        exc_type = NonRecoverableError
    else:
        exc_type = RuntimeError

    raise exc_type('TEST_EXPECTED_FAIL')


@operation
def get_fail_invocations(**_):
    global failure_invocation
    return failure_invocation


@operation
def host_get_state(ctx, **_):
    global host_get_state_invocation
    host_get_state_invocation.append(time.time())
    if len(host_get_state_invocation) <= ctx.properties['false_count']:
        return False
    return True


@operation
def get_host_get_state_invocations(**_):
    global host_get_state_invocation
    return host_get_state_invocation
