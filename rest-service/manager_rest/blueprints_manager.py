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

__author__ = 'dan'


from datetime import datetime
import json
import time
import uuid
import contextlib

from dsl_parser import tasks
from urllib2 import urlopen
from flask import g, current_app

from manager_rest import models
from manager_rest import manager_exceptions
from manager_rest.workflow_client import workflow_client
from manager_rest.storage_manager import get_storage_manager
from manager_rest.util import maybe_register_teardown
from manager_rest.celery_client import celery_client
from manager_rest.celery_client import TASK_STATE_FAILURE as \
    CELERY_TASK_STATE_FAILURE


class DslParseException(Exception):
    pass


class BlueprintAlreadyExistsException(Exception):
    def __init__(self, blueprint_id, *args):
        Exception.__init__(self, args)
        self.blueprint_id = blueprint_id


class BlueprintsManager(object):

    @property
    def sm(self):
        return get_storage_manager()

    def blueprints_list(self):
        return self.sm.blueprints_list()

    def deployments_list(self):
        return self.sm.deployments_list()

    def executions_list(self):
        return self.sm.executions_list()

    def get_blueprint(self, blueprint_id, fields=None):
        return self.sm.get_blueprint(blueprint_id, fields)

    def get_deployment(self, deployment_id):
        return self.sm.get_deployment(deployment_id)

    def get_execution(self, execution_id):
        return self.sm.get_execution(execution_id)

    def get_deployment_executions(self, deployment_id):
        return self.sm.get_deployment_executions(deployment_id)

    # TODO: call celery tasks instead of doing this directly here
    # TODO: prepare multi instance plan should be called on workflow execution
    def publish_blueprint(self, dsl_location, alias_mapping_url,
                          resources_base_url, blueprint_id=None):
        # TODO: error code if parsing fails (in one of the 2 tasks)
        try:
            plan = tasks.parse_dsl(dsl_location, alias_mapping_url,
                                   resources_base_url)

            with contextlib.closing(urlopen(dsl_location)) as f:
                source = f.read()
        except Exception, ex:
            raise DslParseException(*ex.args)

        now = str(datetime.now())
        parsed_plan = json.loads(plan)
        if not blueprint_id:
            blueprint_id = parsed_plan['name']

        new_blueprint = models.BlueprintState(plan=parsed_plan,
                                              id=blueprint_id,
                                              created_at=now, updated_at=now,
                                              source=source)
        self.sm.put_blueprint(new_blueprint.id, new_blueprint)
        return new_blueprint

    def delete_blueprint(self, blueprint_id):
        blueprint_deployments = get_storage_manager()\
            .get_blueprint_deployments(blueprint_id)

        if len(blueprint_deployments) > 0:
            raise manager_exceptions.DependentExistsError(
                "Can't delete blueprint {0} - There exist "
                "deployments for this blueprint; Deployments ids: {1}"
                .format(blueprint_id,
                        ','.join([dep.id for dep
                                  in blueprint_deployments])))

        return get_storage_manager().delete_blueprint(blueprint_id)

    def delete_deployment(self, deployment_id, ignore_live_nodes=False):
        storage = get_storage_manager()

        # Verify deployment exists.
        storage.get_deployment(deployment_id)

        # validate there are no running executions for this deployment
        executions = storage.get_deployment_executions(deployment_id)
        if any(execution.status not in ('terminated', 'failed') for
           execution in executions):
            raise manager_exceptions.DependentExistsError(
                "Can't delete deployment {0} - There are running "
                "executions for this deployment. Running executions ids: {1}"
                .format(
                    deployment_id,
                    ','.join([execution.id for execution in
                              executions if execution.status not
                              in ('terminated', 'failed')])))

        if not ignore_live_nodes:
            node_instances = storage.get_node_instances(
                deployment_id=deployment_id)
            # validate either all nodes for this deployment are still
            # uninitialized or have been deleted
            if any(node.state not in ('uninitialized', 'deleted') for node in
                   node_instances):
                raise manager_exceptions.DependentExistsError(
                    "Can't delete deployment {0} - There are live nodes for "
                    "this deployment. Live nodes ids: {1}"
                    .format(deployment_id,
                            ','.join([node.id for node in node_instances
                                     if node.state not in
                                     ('uninitialized', 'deleted')])))

        self._uninstall_deployment_workers(deployment_id)
        return storage.delete_deployment(deployment_id)

    def execute_workflow(self, deployment_id, workflow_id):
        deployment = self.get_deployment(deployment_id)

        if workflow_id not in deployment.plan['workflows']:
            raise manager_exceptions.NonexistentWorkflowError(
                'Workflow {0} does not exist in deployment {1}'.format(
                    workflow_id, deployment_id))
        workflow = deployment.plan['workflows'][workflow_id]

        self._verify_deployment_workers_installed_successfully(deployment_id)

        execution_id = str(uuid.uuid4())
        workflow_client().execute_workflow(
            workflow_id,
            workflow,
            blueprint_id=deployment.blueprint_id,
            deployment_id=deployment_id,
            execution_id=execution_id)

        new_execution = models.Execution(
            id=execution_id,
            status='pending',
            created_at=str(datetime.now()),
            blueprint_id=deployment.blueprint_id,
            workflow_id=workflow_id,
            deployment_id=deployment_id,
            error='')

        get_storage_manager().put_execution(new_execution.id, new_execution)
        return new_execution

    def cancel_workflow(self, execution_id):
        execution = self.get_execution(execution_id)
        workflow_client().cancel_workflow(
            execution.id
        )
        return execution

    def create_deployment(self, blueprint_id, deployment_id):
        blueprint = self.get_blueprint(blueprint_id)
        plan = blueprint.plan
        deployment_json_plan = tasks.prepare_deployment_plan(plan)

        now = str(datetime.now())
        new_deployment = models.Deployment(
            id=deployment_id, plan=json.loads(deployment_json_plan),
            blueprint_id=blueprint_id, created_at=now, updated_at=now)

        self.sm.put_deployment(deployment_id, new_deployment)
        self._create_deployment_nodes(blueprint_id, deployment_id, plan)

        self._install_deployment_workers(new_deployment, now)

        node_instances = new_deployment.plan['node_instances']
        for node_instance in node_instances:
            instance_id = node_instance['id']
            node_id = node_instance['name']
            relationships = node_instance.get('relationships', [])
            host_id = node_instance.get('host_id')

            instance = models.DeploymentNodeInstance(
                id=instance_id,
                node_id=node_id,
                host_id=host_id,
                relationships=relationships,
                deployment_id=deployment_id,
                state='uninitialized',
                runtime_properties=None,
                version=None)
            self.sm.put_node_instance(instance)

        self._wait_for_count(expected_count=len(node_instances),
                             query_method=self.sm.get_node_instances,
                             deployment_id=deployment_id)

        return new_deployment

    def _create_deployment_nodes(self, blueprint_id, deployment_id, plan):
        for raw_node in plan['nodes']:
            self.sm.put_node(models.DeploymentNode(
                id=raw_node['name'],
                deployment_id=deployment_id,
                blueprint_id=blueprint_id,
                type=raw_node['type'],
                type_hierarchy=raw_node['type_hierarchy'],
                number_of_instances=raw_node['instances']['deploy'],
                host_id=raw_node['host_id'] if 'host_id' in raw_node else None,
                properties=raw_node['properties'],
                operations=raw_node['operations'],
                plugins=raw_node['plugins'],
                plugins_to_install=raw_node.get('plugins_to_install'),
                relationships=self._prepare_node_relationships(raw_node)
            ))

        self._wait_for_count(expected_count=len(plan['nodes']),
                             query_method=self.sm.get_nodes,
                             deployment_id=deployment_id)

    @staticmethod
    def _prepare_node_relationships(raw_node):
        if 'relationships' not in raw_node:
            return []
        prepared_relationships = []
        for raw_relationship in raw_node['relationships']:
            relationship = {
                'target_id': raw_relationship['target_id'],
                'type': raw_relationship['type'],
                'type_hierarchy': raw_relationship['type_hierarchy'],
                'properties': raw_relationship['properties'],
                'source_operations': raw_relationship['source_operations'],
                'target_operations': raw_relationship['target_operations'],
            }
            prepared_relationships.append(relationship)
        return prepared_relationships

    def _verify_deployment_workers_installed_successfully(self,
                                                          deployment_id,
                                                          is_retry=False):
        workers_installation_execution = next(
            (execution for execution in
             get_storage_manager().get_deployment_executions(
                 deployment_id) if execution.workflow_id ==
                'workers_installation'),
            None)

        if not workers_installation_execution:
            raise RuntimeError('Failed to find "workers_installation" '
                               'execution for deployment {0}'.format(
                                   deployment_id))

        if workers_installation_execution.status == 'terminated':
            # workers installation is complete
            return
        elif workers_installation_execution.status == 'launched':
            # workers installation is still in process
            raise manager_exceptions\
                .DeploymentWorkersNotYetInstalledError(
                    'Deployment workers are still being installed, '
                    'try again in a minute')
        elif workers_installation_execution.status == 'failed':
            # workers installation workflow failed
            raise RuntimeError(
                "Can't launch executions since workers for deployment {0} "
                'failed to be installed: {1}'.format(
                    deployment_id, workers_installation_execution.error))

        # status is 'pending'. Waiting for a few seconds and retrying to
        # verify (to avoid eventual consistency issues). If this is already a
        # failed retry, it might mean there was a problem with the Celery task
        if not is_retry:
            time.sleep(5)
            self._verify_deployment_workers_installed_successfully(
                deployment_id, True)
        else:
            # workers installation failed but not on the workflow level -
            # retrieving the celery task's status for the error message,
            # and the error object from celery if one is available
            celery_task_status = celery_client().get_task_status(
                workers_installation_execution.id)
            error_message = \
                "Can't launch executions since workers for deployment {0}" \
                " haven't been installed (Execution status is still " \
                "'pending'). Celery task status is ".format(deployment_id)
            if celery_task_status != CELERY_TASK_STATE_FAILURE:
                raise RuntimeError(
                    "{0} {1}".format(error_message, celery_task_status))
            else:
                celery_error = celery_client().get_failed_task_error(
                    workers_installation_execution.id)
                raise RuntimeError(
                    "{0} {1}; Error is of type {2}; Error message: {3}"
                    .format(error_message, celery_task_status,
                            celery_error.__class__.__name__, celery_error))

    def _install_deployment_workers(self, deployment, now):
        workers_installation_task_id = str(uuid.uuid4())
        wf_id = 'workers_installation'
        workers_install_task_name = \
            'system_workflows.workers_installation.install'

        context = self._build_context_from_deployment(
            deployment, workers_installation_task_id, wf_id,
            workers_install_task_name)

        new_execution = models.Execution(
            id=workers_installation_task_id,
            status='pending',
            created_at=now,
            blueprint_id=deployment.blueprint_id,
            workflow_id=wf_id,
            deployment_id=deployment.id,
            error='')
        get_storage_manager().put_execution(new_execution.id, new_execution)

        celery_client().execute_task(
            workers_install_task_name,
            'cloudify.management',
            workers_installation_task_id,
            kwargs={
                'management_plugins_to_install':
                deployment.plan['management_plugins_to_install'],
                'workflow_plugins_to_install':
                deployment.plan['workflow_plugins_to_install'],
                '__cloudify_context': context})

    def _build_context_from_deployment(self, deployment, task_id, wf_id,
                                       task_name):
        return {
            'task_id': task_id,
            'task_name': task_name,
            'task_target': 'cloudify.management',
            'blueprint_id': deployment.blueprint_id,
            'deployment_id': deployment.id,
            'execution_id': task_id,
            'workflow_id': wf_id,
        }

    def _uninstall_deployment_workers(self, deployment_id):
        deployment = get_storage_manager().get_deployment(deployment_id)

        workers_uninstallation_task_id = str(uuid.uuid4())
        wf_id = 'workers_uninstallation'
        workers_uninstall_task_name = \
            'system_workflows.workers_installation.uninstall'

        context = self._build_context_from_deployment(
            deployment,
            workers_uninstallation_task_id,
            wf_id,
            workers_uninstall_task_name)

        new_execution = models.Execution(
            id=workers_uninstallation_task_id,
            status='pending',
            created_at=str(datetime.now()),
            blueprint_id=deployment.blueprint_id,
            workflow_id=wf_id,
            deployment_id=deployment_id,
            error='')
        get_storage_manager().put_execution(new_execution.id, new_execution)

        uninstall_workers_task_async_result = celery_client().execute_task(
            workers_uninstall_task_name,
            'cloudify.management',
            workers_uninstallation_task_id,
            kwargs={'__cloudify_context': context})

        # wait for workers uninstall to complete
        uninstall_workers_task_async_result.get(timeout=300,
                                                propagate=True)
        # verify uninstall completed successfully
        execution = get_storage_manager().get_execution(
            workers_uninstallation_task_id)
        if execution.status != 'terminated':
            raise RuntimeError('Failed to uninstall deployment workers for '
                               'deployment {0}'.format(deployment_id))

    @staticmethod
    def _wait_for_count(expected_count, query_method, deployment_id):
        import time
        timeout = time.time() + 30
        # workaround ES eventual consistency
        # TODO check if there is a count query and do that
        actual_count = len(query_method(deployment_id))
        while actual_count < expected_count and time.time() < timeout:
            time.sleep(1)
            actual_count = len(query_method(deployment_id))
        if actual_count < expected_count:
            raise RuntimeError('Timed out while waiting for nodes count')


def teardown_blueprints_manager(exception):
    # print "tearing down blueprints manager!"
    pass


# What we need to access this manager in Flask
def get_blueprints_manager():
    """
    Get the current blueprints manager
    or create one if none exists for the current app context
    """
    if 'blueprints_manager' not in g:
        g.blueprints_manager = BlueprintsManager()
        maybe_register_teardown(current_app, teardown_blueprints_manager)
    return g.blueprints_manager
