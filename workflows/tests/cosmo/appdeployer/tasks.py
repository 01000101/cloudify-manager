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

__author__ = 'idanmo'

from cosmo.celery import celery
import os
from celery.utils.log import get_task_logger
import Queue
from os import path
import tarfile
import requests
import json

TEMP_DIR = os.environ.get('TEMP_DIR')


logger = get_task_logger(__name__)
return_value = Queue.Queue()
thread_obj = None


class ManagerRestClientTestClient(object):

    def __init__(self, port=8100):
        self.port = port
        self.base_manager_rest_uri = 'http://localhost:{0}'.format(port)

    def submit_blueprint(self, blueprint_path):
        tar_path = self._tar_blueprint(blueprint_path)
        application_file = path.basename(blueprint_path)
        with open(tar_path) as f:
            response = requests.post('{0}/blueprints'.format(self.base_manager_rest_uri),
                                     params={
                                         'application_file_name': application_file
                                     },
                                     data=f.read(),
                                     headers={'Content-Type': 'application/octet-stream'})
            status_code = response.status_code
            if status_code != 201:
                raise RuntimeError('Blueprint {0} submission failed'.format(blueprint_path))
        return response.json()

    validate = submit_blueprint

    def create_deployment(self, blueprint_response):
        response = requests.post('{0}/deployments'.format(
                                 self.base_manager_rest_uri),
                                 headers={'Content-Type': 'application/json'},
                                 data=json.dumps({'blueprintId': blueprint_response['id']}))
        if response.status_code != 201:
            raise RuntimeError('Create deployment failed for blueprint: {0}'.format(
                blueprint_response['id']))
        return response.json()

    def validate_blueprint(self, blueprint_response):
        response = requests.get('{0}/blueprints/{1}/validate'.format(
            self.base_manager_rest_uri, blueprint_response['id']))
        if response.status_code != 200:
            raise RuntimeError('Validation failed for blueprint: {0}'.format(blueprint_response['id']))
        return response.json()

    def execute_install_workflow(self, deployment_response):
        response = requests.post('{0}/deployments/{1}/executions'.format(
                                 self.base_manager_rest_uri, deployment_response['id']),
                                 headers={'Content-Type': 'application/json'},
                                 data=json.dumps({'workflowId': 'install'}))
        if response.status_code != 201:
            raise RuntimeError('Install workflow execution failed for deployment: {0}'.
                               format(deployment_response['id']))
        return response.json()

    def get_execution_status(self, execution_id):
        response = requests.get('{0}/executions/{1}'.format(
            self.base_manager_rest_uri, execution_id),
            headers={'Content-Type': 'application/json'})
        if response.status_code != 200:
            raise RuntimeError('Failed getting workflow execution status for workflow id {0}'.format(
                execution_id))
        return response.json()

    def get_deployment_events(self, deployment_id, first_event=0, events_count=500):
        response = requests.get('{0}/deployments/{1}/events?from={2}&count={3}'.format(
            self.base_manager_rest_uri, deployment_id, first_event, events_count))
        if response.status_code != 200:
            raise RuntimeError('Failed getting deployment events for deployment id {0}'.format(deployment_id))
        return response.json()

    def get_deployment_nodes(self, deployment_id=None):
        if deployment_id is not None:
            raise RuntimeError('not implemented')
        response = requests.get('{0}/nodes'.format(self.base_manager_rest_uri))
        if response.status_code != 200:
            raise RuntimeError('Failed getting nodes for deployment id {0}'.format(deployment_id))
        return response.json()['nodes']

    def get_node(self, node_id):
        response = requests.get('{0}/nodes/{1}'.format(self.base_manager_rest_uri, node_id))
        if response.status_code != 200:
            raise RuntimeError('Failed getting node for node id {0}'.format(node_id))
        return response.json()

    def execute_uninstall_workflow(self, deployment_id):
        response = requests.post('{0}/deployments/{1}/executions'.format(
                                 self.base_manager_rest_uri, deployment_id),
                                 headers={'Content-Type': 'application/json'},
                                 data=json.dumps({'workflowId': 'uninstall'}))
        if response.status_code != 201:
            raise RuntimeError('Uninstall workflow execution failed for deployment id: {0}'.format(deployment_id))
        return response.json()

    def _tar_blueprint(self, blueprint_path):
        blueprint_name = path.basename(path.splitext(blueprint_path)[0])
        blueprint_directory = path.dirname(blueprint_path)
        tar_path = '{0}/{1}.tar.gz'.format(TEMP_DIR, blueprint_name)
        with tarfile.open(tar_path, "w:gz") as tar:
            tar.add(blueprint_directory, arcname=os.path.basename(blueprint_directory))
        return tar_path

manager_client = ManagerRestClientTestClient()


@celery.task
def submit_and_execute_workflow(blueprint_path, **kwargs):
    blueprint = manager_client.submit_blueprint(blueprint_path)
    deployment = manager_client.create_deployment(blueprint)
    return manager_client.execute_install_workflow(deployment)


@celery.task
def submit_and_validate_blueprint(blueprint_path, **kwargs):
    blueprint = manager_client.submit_blueprint(blueprint_path)
    return manager_client.validate_blueprint(blueprint)


@celery.task
def get_execution_status(execution_id, **kwargs):
    return manager_client.get_execution_status(execution_id)


@celery.task
def get_deployment_events(deployment_id, first_event=0, events_count=500, **kwargs):
    return manager_client.get_deployment_events(deployment_id, first_event, events_count)


@celery.task
def get_deployment_nodes(deployment_id, **kwargs):
    return manager_client.get_deployment_nodes(deployment_id)


@celery.task
def get_node(node_id, **kwargs):
    return manager_client.get_node(node_id)


@celery.task
def uninstall_deployment(deployment_id, **kwargs):
    return manager_client.execute_uninstall_workflow(deployment_id)

