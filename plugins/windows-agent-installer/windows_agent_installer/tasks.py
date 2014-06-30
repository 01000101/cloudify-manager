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
import time

from cloudify.decorators import operation
from cloudify import utils
from cloudify.exceptions import TimeoutException

from windows_agent_installer import init_worker_installer


# This is the folder under which the agent is extracted to inside the current directory.
# It is set in the packaging process so it must be hardcoded here.

AGENT_FOLDER_NAME = 'CloudifyAgent'

# This is where we download the agent to.
AGENT_EXEC_FILE_NAME = 'CloudifyAgent.exe'

# nssm will install celery and use this name to identify the service
AGENT_SERVICE_NAME = 'CloudifyAgent'

# location of the agent package on the management machine, relative to the file server root.
AGENT_PACKAGE_PATH = '/packages/agents/CloudifyWindowsAgent.exe'

# Path to the agent. We are using global (not user based) paths because of virtualenv relocation issues on windows.
RUNTIME_AGENT_PATH = 'C:\CloudifyAgent'

# Agent includes list, Mandatory
AGENT_INCLUDES = 'plugin_installer.tasks'

# Defaults timeouts.
DEFAULT_SERVICE_START_TIMEOUT = 10
DEFAULT_SERVICE_STOP_TIMEOUT = 10


def get_agent_package_url():
    return '{0}{1}'.format(utils.get_manager_file_server_url(),
                           AGENT_PACKAGE_PATH)

def get_manager_ip():
    return utils.get_manager_ip()

@operation
@init_worker_installer
def install(ctx, runner=None, cloudify_agent=None, **kwargs):

    '''

    Installs the cloudify agent service on the machine.
    The agent installation consists of the following:

        1. Download and extract necessary files.
        2. Configure the agent service to auto start on vm launch.
        3. Configure the agent service to restart on failure.


    :param ctx: Invocation context - injected by the @operation
    :param runner: Injected by the @init_worker_installer
    :param cloudify_agent: Injected by the @init_worker_installer
    :return:
    '''

    ctx.logger.info('Installing agent {0}'.format(cloudify_agent['name']))

    agent_exec_path = 'C:\{0}'.format(AGENT_EXEC_FILE_NAME)

    runner.download(get_agent_package_url(), agent_exec_path)
    ctx.logger.debug('Extracting agent to C:\\ ...')

    runner.run('{0} -o{1} -y'.format(agent_exec_path, 'C:\\'))

    params = ('--broker=amqp://guest:guest@{0}:5672// '
              '--include=plugin_installer.tasks '
              '--events '
              '--app=cloudify '
              '-Q {1} '
              '-n {1} '
              '--logfile={2}\celery.log '
              '--pidfile={2}\celery.pid '
              .format(get_manager_ip(), cloudify_agent['name'], RUNTIME_AGENT_PATH))
    runner.run('{0}\\nssm\\nssm.exe install {1} {0}\Scripts\celeryd.exe {2}'
               .format(RUNTIME_AGENT_PATH, AGENT_SERVICE_NAME, params))
    runner.run('sc config {0} start= auto'.format(AGENT_SERVICE_NAME))
    runner.run('sc failure {0} reset= 60 actions= restart/5000'.format(AGENT_SERVICE_NAME))

    return True


@operation
@init_worker_installer
def start(ctx, runner=None, cloudify_agent=None, **kwargs):

    '''

    Starts the cloudify agent service on the machine.

    :param ctx: Invocation context - injected by the @operation
    :param runner: Injected by the @init_worker_installer
    :param cloudify_agent: Injected by the @init_worker_installer
    :return:
    '''

    ctx.logger.info('Starting agent {0}'.format(cloudify_agent['name']))

    runner.run('sc start {}'.format(AGENT_SERVICE_NAME))

    ctx.logger.info('Waiting for {0} to start...'.format(AGENT_SERVICE_NAME))
    _wait_for_service_status(runner, AGENT_SERVICE_NAME, 'RUNNING',
        cloudify_agent['service_start_timeout'] if 'service_start_timeout' in cloudify_agent
        else DEFAULT_SERVICE_START_TIMEOUT)



@operation
@init_worker_installer
def stop(ctx, runner=None, cloudify_agent=None, **kwargs):

    '''

    Stops the cloudify agent service on the machine.

    :param ctx: Invocation context - injected by the @operation
    :param runner: Injected by the @init_worker_installer
    :param cloudify_agent: Injected by the @init_worker_installer
    :return:
    '''


    ctx.logger.info('Stopping agent {0}'.format(cloudify_agent['name']))

    runner.run('sc stop {}'.format(AGENT_SERVICE_NAME))

    ctx.logger.info('Waiting for {0} to stop...'.format(AGENT_SERVICE_NAME))
    _wait_for_service_status(runner, AGENT_SERVICE_NAME, 'STOPPED',
        cloudify_agent['service_stop_timeout'] if 'service_stop_timeout' in cloudify_agent
        else DEFAULT_SERVICE_STOP_TIMEOUT)

@operation
@init_worker_installer
def restart(ctx, runner=None, cloudify_agent=None, **kwargs):

    '''

    Restarts the cloudify agent service on the machine.

        1. Stop the service.
        2. Start the service.

    :param ctx: Invocation context - injected by the @operation
    :param runner: Injected by the @init_worker_installer
    :param cloudify_agent: Injected by the @init_worker_installer
    :return:
    '''

    ctx.logger.info('Restarting agent {0}'.format(cloudify_agent['name']))

    runner.run('sc stop {}'.format(AGENT_SERVICE_NAME))
    runner.run('sc start {}'.format(AGENT_SERVICE_NAME))


@operation
@init_worker_installer
def uninstall(ctx, runner=None, cloudify_agent=None, **kwargs):

    '''

    Uninstalls the cloudify agent service from the machine.

        1. Remove the service from the registry.
        2. Delete related files..


    :param ctx: Invocation context - injected by the @operation
    :param runner: Injected by the @init_worker_installer
    :param cloudify_agent: Injected by the @init_worker_installer
    :return:
    '''


    ctx.logger.info('Uninstalling agent {0}'.format(cloudify_agent['name']))

    runner.run('{0} remove {1} confirm'.format('{0}\\nssm\\nssm.exe'.format(RUNTIME_AGENT_PATH),
                                               AGENT_SERVICE_NAME))

    runner.delete(path=RUNTIME_AGENT_PATH)
    runner.delete(path='C:\\{0}'.format(AGENT_EXEC_FILE_NAME))


def _wait_for_service_status(runner, service_name, desired_status, timeout_in_seconds):

    end_time = time.time() + timeout_in_seconds

    while end_time > time.time():

        response = runner.run('sc query {0}'.format(service_name))
        if desired_status in response.std_out:
            return
        time.sleep(1)
    raise TimeoutException("Service {0} did not reach state {1} in {2} seconds"
                           .format(service_name, desired_status, timeout_in_seconds))
