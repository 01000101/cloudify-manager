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
#

__author__ = 'dan'


import config
import os
import models
import responses
import requests_schema
import tarfile
import zipfile
import urllib
import tempfile
import shutil
import uuid
import chunked
import elasticsearch

from functools import wraps
from blueprints_manager import DslParseException, \
    BlueprintAlreadyExistsException
from workflow_client import WorkflowServiceError
from manager_rest.exceptions import ConflictError
from flask import request
from flask.ext.restful import Resource, abort, marshal_with, marshal, reqparse
from flask_restful_swagger import swagger
from os import path


CONVENTION_APPLICATION_BLUEPRINT_FILE = 'blueprint.yaml'


def blueprints_manager():
    import blueprints_manager
    return blueprints_manager.instance()


def storage_manager():
    import storage_manager
    return storage_manager.instance()


def riemann_client():
    import riemann_client
    return riemann_client.instance()


def exceptions_handled(func):
    @wraps(func)
    def wrapper(*args, **kwargs):
        try:
            return func(*args, **kwargs)
        except ConflictError, e:
            abort_conflict(e)
        except WorkflowServiceError, e:
            abort_workflow_service_operation(e)
    return wrapper


def verify_json_content_type():
    if request.content_type != 'application/json':
        abort(415, message='415: Content type must be application/json')


def verify_blueprint_exists(blueprint_id):
    if blueprints_manager().get_blueprint(blueprint_id) is None:
        abort(404,
              message='404: blueprint {0} not found'.format(blueprint_id))


def verify_deployment_exists(deployment_id):
    if blueprints_manager().get_deployment(deployment_id) is None:
        abort(404,
              message='404: deployment {0} not found'.format(deployment_id))


def verify_blueprint_does_not_exist(blueprint_id):
    if blueprints_manager().get_blueprint(blueprint_id) is not None:
        abort(400,
              message='400: blueprint {0} already exists'
                      .format(blueprint_id))


def verify_deployment_does_not_exist(deployment_id):
    if blueprints_manager().get_deployment(deployment_id) is not None:
        abort(400,
              message='400: deployment {0} already exists'
                      .format(deployment_id))


def verify_execution_exists(execution_id):
    if blueprints_manager().get_execution(execution_id) is None:
        abort(404,
              message='404: execution_id {0} not found'.format(execution_id))


def abort_workflow_service_operation(workflow_service_error):
    abort(500,
          message='500: Workflow service failed with status code {0},'
                  ' full response {1}'
                  .format(workflow_service_error.status_code,
                          workflow_service_error.json))


def abort_conflict(conflict_error):
    abort(409,
          message='409: Conflict occurred - {0}'.format(str(conflict_error)))


def verify_and_convert_bool(attribute_name, str_bool):
    if str_bool.lower() == 'true':
        return True
    if str_bool.lower() == 'false':
        return False
    abort(400,
          message='400: {0} must be <true/false>, got {1}'
                  .format(attribute_name, str_bool))


def setup_resources(api):
    api = swagger.docs(api,
                       apiVersion='0.1',
                       basePath='http://localhost:8100')

    api.add_resource(Blueprints,
                     '/blueprints')
    api.add_resource(BlueprintsId,
                     '/blueprints/<string:blueprint_id>')
    api.add_resource(BlueprintsIdValidate,
                     '/blueprints/<string:blueprint_id>/validate')
    api.add_resource(ExecutionsId,
                     '/executions/<string:execution_id>')
    api.add_resource(Deployments,
                     '/deployments')
    api.add_resource(DeploymentsId,
                     '/deployments/<string:deployment_id>')
    api.add_resource(DeploymentsIdExecutions,
                     '/deployments/<string:deployment_id>/executions')
    api.add_resource(DeploymentsIdWorkflows,
                     '/deployments/<string:deployment_id>/workflows')
    api.add_resource(DeploymentsIdNodes,
                     '/deployments/<string:deployment_id>/nodes')
    api.add_resource(NodesId,
                     '/nodes/<string:node_id>')
    api.add_resource(Events, '/events')


class BlueprintsUpload(object):
    def do_request(self, blueprint_id=None):
        file_server_root = config.instance().file_server_root
        archive_target_path = tempfile.mktemp(dir=file_server_root)
        try:
            self._save_file_locally(archive_target_path)
            application_dir = self._extract_file_to_file_server(
                file_server_root, archive_target_path)
        finally:
            if os.path.exists(archive_target_path):
                os.remove(archive_target_path)
        self._process_plugins(file_server_root, application_dir)

        return self._prepare_and_submit_blueprint(file_server_root,
                                                  application_dir,
                                                  blueprint_id), 201

    def _process_plugins(self, file_server_root, application_dir):
        blueprint_directory = path.join(file_server_root, application_dir)
        plugins_directory = path.join(blueprint_directory, 'plugins')
        if not path.isdir(plugins_directory):
            return
        plugins = [path.join(plugins_directory, directory)
                   for directory in os.listdir(plugins_directory)
                   if path.isdir(path.join(plugins_directory, directory))]

        for plugin_dir in plugins:
            final_zip_name = '{0}.zip'.format(path.basename(plugin_dir))
            target_zip_path = path.join(file_server_root, final_zip_name)
            self._zip_dir(plugin_dir, target_zip_path)

    def _zip_dir(self, dir_to_zip, target_zip_path):
        zipf = zipfile.ZipFile(target_zip_path, 'w', zipfile.ZIP_DEFLATED)
        try:
            plugin_dir_base_name = path.basename(dir_to_zip)
            rootlen = len(dir_to_zip) - len(plugin_dir_base_name)
            for base, dirs, files in os.walk(dir_to_zip):
                for entry in files:
                    fn = os.path.join(base, entry)
                    zipf.write(fn, fn[rootlen:])
        finally:
            zipf.close()

    def _save_file_locally(self, archive_file_name):
        # save uploaded file
        if 'Transfer-Encoding' in request.headers:
            with open(archive_file_name, 'w') as f:
                for buffered_chunked in chunked.decode(request.input_stream):
                    f.write(buffered_chunked)
        else:
            if not request.data:
                abort(400,
                      message='Missing application archive in request body')
            uploaded_file_data = request.data
            with open(archive_file_name, 'w') as f:
                f.write(uploaded_file_data)

    def _extract_file_to_file_server(self, file_server_root,
                                     archive_target_path):
        # extract application to file server
        tar = tarfile.open(archive_target_path)
        tempdir = tempfile.mkdtemp('-blueprint-submit')
        try:
            tar.extractall(tempdir)
            archive_file_list = os.listdir(tempdir)
            if len(archive_file_list) != 1 or not path.isdir(
                    path.join(tempdir, archive_file_list[0])):
                abort(400,
                      message='400: archive must contain exactly 1 directory')
            application_dir_base_name = archive_file_list[0]
            #generating temporary unique name for app dir, to allow multiple
            #uploads of apps with the same name (as it appears in the file
            # system, not the app name field inside the blueprint.
            # the latter is guaranteed to be unique).
            generated_app_dir_name = '{0}-{1}'.format(
                application_dir_base_name, uuid.uuid4())
            temp_application_dir = path.join(tempdir,
                                             application_dir_base_name)
            temp_application_target_dir = path.join(tempdir,
                                                    generated_app_dir_name)
            shutil.move(temp_application_dir, temp_application_target_dir)
            shutil.move(temp_application_target_dir, file_server_root)
            return generated_app_dir_name
        finally:
            shutil.rmtree(tempdir)

    def _prepare_and_submit_blueprint(self, file_server_root,
                                      application_dir,
                                      blueprint_id=None):
        application_file = self._extract_application_file(file_server_root,
                                                          application_dir)

        file_server_base_url = config.instance().file_server_base_uri
        dsl_path = '{0}/{1}'.format(file_server_base_url, application_file)
        alias_mapping = '{0}/{1}'.format(file_server_base_url,
                                         'cloudify/alias-mappings.yaml')
        resources_base = file_server_base_url + '/'

        # add to blueprints manager (will also dsl_parse it)
        try:
            blueprint = blueprints_manager().publish_blueprint(
                dsl_path, alias_mapping, resources_base, blueprint_id)

            #moving the app directory in the file server to be under a
            # directory named after the blueprint's app name field
            shutil.move(os.path.join(file_server_root, application_dir),
                        os.path.join(file_server_root, blueprint.id))
            return blueprint
        except DslParseException, ex:
            abort(400, message='400: Invalid blueprint - {0}'.format(ex.args))
        except BlueprintAlreadyExistsException, ex:
            abort(400, message='400: Blueprint - {0} already exists'
                               .format(ex.blueprint_id))

    def _extract_application_file(self, file_server_root, application_dir):
        if 'application_file_name' in request.args:
            application_file = urllib.unquote(
                request.args['application_file_name']).decode('utf-8')
            application_file = '{0}/{1}'.format(application_dir,
                                                application_file)
            return application_file
        else:
            full_application_dir = path.join(file_server_root, application_dir)
            full_application_file = path.join(
                full_application_dir, CONVENTION_APPLICATION_BLUEPRINT_FILE)
            if path.isfile(full_application_file):
                application_file = path.join(
                    application_dir, CONVENTION_APPLICATION_BLUEPRINT_FILE)
                return application_file
        abort(400, message='Missing application_file_name query parameter or '
                           'application directory is missing blueprint.yaml')


class Blueprints(Resource):

    @swagger.operation(
        responseClass='List[{0}]'.format(responses.BlueprintState.__name__),
        nickname="list",
        notes="Returns a list a submitted blueprints."
    )
    def get(self):
        """
        Returns a list of submitted blueprints.
        """
        return [marshal(blueprint,
                        responses.BlueprintState.resource_fields) for
                blueprint in blueprints_manager().blueprints_list()]

    @swagger.operation(
        responseClass=responses.BlueprintState,
        nickname="upload",
        notes="Submitted blueprint should be a tar "
              "gzipped directory containing the blueprint.",
        parameters=[{'name': 'application_file_name',
                     'description': 'File name of yaml '
                                    'containing the "main" blueprint.',
                     'required': False,
                     'allowMultiple': False,
                     'dataType': 'string',
                     'paramType': 'query',
                     'defaultValue': 'blueprint.yaml'},
                    {
                        'name': 'body',
                        'description': 'Binary form of the tar '
                                       'gzipped blueprint directory',
                        'required': True,
                        'allowMultiple': False,
                        'dataType': 'binary',
                        'paramType': 'body',
                    }],
        consumes=[
            "application/octet-stream"
        ]

    )
    @marshal_with(responses.BlueprintState.resource_fields)
    def post(self):
        """
        Submit a new blueprint.
        """
        return BlueprintsUpload().do_request()


class BlueprintsId(Resource):

    @swagger.operation(
        responseClass=responses.BlueprintState,
        nickname="getById",
        notes="Returns a blueprint by its id."
    )
    @marshal_with(responses.BlueprintState.resource_fields)
    def get(self, blueprint_id):
        """
        Returns a blueprint by its id.
        """
        verify_blueprint_exists(blueprint_id)
        blueprint = blueprints_manager().get_blueprint(blueprint_id)
        return responses.BlueprintState(**blueprint.to_dict())

    @swagger.operation(
        responseClass=responses.BlueprintState,
        nickname="upload",
        notes="Submitted blueprint should be a tar "
              "gzipped directory containing the blueprint.",
        parameters=[{'name': 'application_file_name',
                     'description': 'File name of yaml '
                                    'containing the "main" blueprint.',
                     'required': False,
                     'allowMultiple': False,
                     'dataType': 'string',
                     'paramType': 'query',
                     'defaultValue': 'blueprint.yaml'},
                    {
                        'name': 'body',
                        'description': 'Binary form of the tar '
                                       'gzipped blueprint directory',
                        'required': True,
                        'allowMultiple': False,
                        'dataType': 'binary',
                        'paramType': 'body',
                        }],
        consumes=[
            "application/octet-stream"
        ]

    )
    @marshal_with(responses.BlueprintState.resource_fields)
    def put(self, blueprint_id):
        """
        Submit a new blueprint with a blueprint_id.
        """
        verify_blueprint_does_not_exist(blueprint_id)
        return BlueprintsUpload().do_request(blueprint_id=blueprint_id)


class BlueprintsIdValidate(Resource):

    @swagger.operation(
        responseClass=responses.BlueprintValidationStatus,
        nickname="validate",
        notes="Validates a given blueprint."
    )
    @marshal_with(responses.BlueprintValidationStatus.resource_fields)
    def get(self, blueprint_id):
        """
        Validates a given blueprint.
        """
        verify_blueprint_exists(blueprint_id)
        return blueprints_manager().validate_blueprint(blueprint_id)


class ExecutionsId(Resource):

    @swagger.operation(
        responseClass=responses.Execution,
        nickname="getById",
        notes="Returns the execution state by its id."
    )
    @marshal_with(responses.Execution.resource_fields)
    @exceptions_handled
    def get(self, execution_id):
        """
        Returns the execution state by its id.
        """
        verify_execution_exists(execution_id)
        execution = blueprints_manager().get_workflow_state(execution_id)
        return responses.Execution(**execution.to_dict())


class DeploymentsIdNodes(Resource):

    def __init__(self):
        self._args_parser = reqparse.RequestParser()
        self._args_parser.add_argument('reachable', type=str,
                                       default='false', location='args')

    @swagger.operation(
        responseClass=responses.DeploymentNodes,
        nickname="list",
        notes="Returns an object containing nodes associated with "
              "this deployment.",
        parameters=[{'name': 'reachable',
                     'description': 'Specifies whether to return reachable '
                                    'state for the nodes.',
                     'required': False,
                     'allowMultiple': False,
                     'dataType': 'boolean',
                     'defaultValue': False,
                     'paramType': 'query'}]
    )
    @marshal_with(responses.DeploymentNodes.resource_fields)
    def get(self, deployment_id):
        """
        Returns an object containing nodes associated with this deployment.
        """
        args = self._args_parser.parse_args()
        get_reachable_state = verify_and_convert_bool(
            'reachable', args['reachable'])
        verify_deployment_exists(deployment_id)

        deployment = blueprints_manager().get_deployment(deployment_id)
        node_ids = map(lambda node: node['id'],
                       deployment.plan['nodes'])

        reachable_states = {}
        if get_reachable_state:
            reachable_states = riemann_client().get_nodes_state(node_ids)

        nodes = []
        for node_id in node_ids:
            node_result = responses.DeploymentNode(id=node_id)
            if get_reachable_state:
                state = reachable_states[node_id]
                node_result.reachable = state['reachable']
            nodes.append(node_result)
        return responses.DeploymentNodes(deployment_id=deployment_id,
                                         nodes=nodes)


class Deployments(Resource):

    @swagger.operation(
        responseClass='List[{0}]'.format(responses.Deployment.__name__),
        nickname="list",
        notes="Returns a list existing deployments."
    )
    def get(self):
        """
        Returns a list of existing deployments.
        """
        return [marshal(responses.Deployment(**deployment.to_dict()),
                        responses.Deployment.resource_fields) for
                deployment in blueprints_manager().deployments_list()]

    @swagger.operation(
        responseClass=responses.Deployment,
        nickname="createDeployment",
        notes="Created a new deployment of the given blueprint.",
        parameters=[{'name': 'body',
                     'description': 'Deployment blue print',
                     'required': True,
                     'allowMultiple': False,
                     'dataType': requests_schema.DeploymentRequest.__name__,
                     'paramType': 'body'}],
        consumes=[
            "application/json"
        ]
    )
    @marshal_with(responses.Deployment.resource_fields)
    def post(self):
        """
        Creates a new deployment
        """
        verify_json_content_type()
        request_json = request.json
        if 'blueprintId' not in request_json:
            abort(400, message='400: Missing blueprintId in json request body')
        blueprint_id = request.json['blueprintId']
        verify_blueprint_exists(blueprint_id)
        deployment = blueprints_manager().create_deployment(blueprint_id)
        return responses.Deployment(**deployment.to_dict()), 201


class DeploymentsId(Resource):

    @swagger.operation(
        responseClass=responses.Deployment,
        nickname="getById",
        notes="Returns a deployment by its id."
    )
    @marshal_with(responses.Deployment.resource_fields)
    def get(self, deployment_id):
        """
        Returns a deployment by its id.
        """
        verify_deployment_exists(deployment_id)
        deployment = blueprints_manager().get_deployment(deployment_id)
        return responses.Deployment(**deployment.to_dict())

    @swagger.operation(
        responseClass=responses.Deployment,
        nickname="createDeployment",
        notes="Created a new deployment of the given blueprint.",
        parameters=[{'name': 'body',
                     'description': 'Deployment blue print',
                     'required': True,
                     'allowMultiple': False,
                     'dataType': requests_schema.DeploymentRequest.__name__,
                     'paramType': 'body'}],
        consumes=[
            "application/json"
        ]
    )
    @marshal_with(responses.Deployment.resource_fields)
    def put(self, deployment_id):
        """
        Creates a new deployment
        """
        verify_json_content_type()
        request_json = request.json
        if 'blueprintId' not in request_json:
            abort(400, message='400: Missing blueprintId in json request body')
        blueprint_id = request.json['blueprintId']
        verify_blueprint_exists(blueprint_id)
        verify_deployment_does_not_exist(deployment_id)
        return blueprints_manager().create_deployment(blueprint_id,
                                                      deployment_id), 201


class NodesId(Resource):

    def __init__(self):
        self._args_parser = reqparse.RequestParser()
        self._args_parser.add_argument('reachable', type=str,
                                       default='false', location='args')
        self._args_parser.add_argument('runtime', type=str,
                                       default='true', location='args')

    @swagger.operation(
        responseClass=responses.DeploymentNode,
        nickname="getNodeState",
        notes="Returns node runtime/reachable state "
              "according to the provided query parameters.",
        parameters=[{'name': 'node_id',
                     'description': 'Node Id',
                     'required': True,
                     'allowMultiple': False,
                     'dataType': 'string',
                     'paramType': 'path'},
                    {'name': 'reachable',
                     'description': 'Specifies whether to return reachable '
                                    'state.',
                     'required': False,
                     'allowMultiple': False,
                     'dataType': 'boolean',
                     'defaultValue': False,
                     'paramType': 'query'},
                    {'name': 'runtime',
                     'description': 'Specifies whether to return runtime '
                                    'information.',
                     'required': False,
                     'allowMultiple': False,
                     'dataType': 'boolean',
                     'defaultValue': True,
                     'paramType': 'query'}]
    )
    @marshal_with(responses.DeploymentNode.resource_fields)
    @exceptions_handled
    def get(self, node_id):
        """
        Gets node runtime or reachable state.
        """
        args = self._args_parser.parse_args()
        get_reachable_state = verify_and_convert_bool(
            'reachable', args['reachable'])
        get_runtime_state = verify_and_convert_bool(
            'runtime', args['runtime'])

        reachable_state = None
        if get_reachable_state:
            state = riemann_client().get_node_state(node_id)
            reachable_state = state['reachable']
            # this is a temporary workaround for having a reachable
            # host ip injected to its runtime states.
            # it will be later removed once all plugins publish
            # such properties on their own.
            if 'host' in state:
                node = storage_manager().get_node(node_id)
                node_state = node.runtime_info if node else {}
                if 'ip' not in node_state:
                    update = {'ip': state['host']}
                    node = models.DeploymentNode(id=node_id,
                                                 runtime_info=update)
                    storage_manager().update_node(node_id, node)

        runtime_state = None
        if get_runtime_state:
            node = storage_manager().get_node(node_id)
            runtime_state = node.runtime_info if node else {}
        return responses.DeploymentNode(id=node_id, reachable=reachable_state,
                                        runtime_info=runtime_state)

    @swagger.operation(
        responseClass=responses.DeploymentNode,
        nickname="putNodeState",
        notes="Put node runtime state (state will be entirely replaced) " +
              "with the provided dictionary of keys and values.",
        parameters=[{'name': 'node_id',
                     'description': 'Node Id',
                     'required': True,
                     'allowMultiple': False,
                     'dataType': 'string',
                     'paramType': 'path'}]
    )
    @marshal_with(responses.DeploymentNode.resource_fields)
    @exceptions_handled
    def put(self, node_id):
        """
        Puts node runtime state.
        """
        verify_json_content_type()
        if request.json.__class__ is not dict:
            abort(400, message='request body is expected to be'
                               ' of key/value map type but is {0}'
                               .format(request.json.__class__.__name__))

        node = models.DeploymentNode(id=node_id, runtime_info=request.json)
        storage_manager().put_node(node_id, node)
        return responses.DeploymentNode(
            id=node_id,
            runtime_info=node.runtime_info)

    @swagger.operation(
        responseClass=responses.DeploymentNode,
        nickname="putNodeState",
        notes="Update node runtime state with the provided dictionary "
              "of keys and values. Each value in the dictionary should be a "
              "list where the first item is the new value and the second "
              "is the old value (for having some kind of optimistic locking). "
              "New keys should have a single element list with the new value "
              "only.",
        parameters=[{'name': 'node_id',
                     'description': 'Node Id',
                     'required': True,
                     'allowMultiple': False,
                     'dataType': 'string',
                     'paramType': 'path'},
                    {'name': 'body',
                     'description': 'Node state updated keys/values',
                     'required': True,
                     'allowMultiple': False,
                     'dataType': 'string',
                     'paramType': 'body'}],
        consumes=["application/json"]
    )
    @marshal_with(responses.DeploymentNode.resource_fields)
    @exceptions_handled
    def patch(self, node_id):
        """
        Updates node runtime state.
        """
        verify_json_content_type()
        if request.json.__class__ is not dict:
            abort(400, message='request body is expected to be of key/value'
                               ' map type but is {0}'
                               .format(request.json.__class__.__name__))
        node = models.DeploymentNode(id=node_id, runtime_info=request.json)
        runtime_info = storage_manager().update_node(node_id, node)
        return responses.DeploymentNode(
            id=node_id,
            runtime_info=runtime_info)


class DeploymentsIdExecutions(Resource):

    @swagger.operation(
        responseClass='List[{0}]'.format(responses.Execution.__name__),
        nickname="list",
        notes="Returns a list of executions related to the provided"
              " deployment."
    )
    def get(self, deployment_id):
        """
        Returns a list of executions related to the provided deployment.
        """
        verify_deployment_exists(deployment_id)
        return [marshal(responses.Execution(**execution.to_dict()),
                        responses.Execution.resource_fields) for
                execution in storage_manager().get_deployment_executions(
                    deployment_id)]

    @swagger.operation(
        responseClass=responses.Execution,
        nickname="execute",
        notes="Executes the provided workflow under the given deployment "
              "context.",
        parameters=[{'name': 'body',
                     'description': 'Workflow execution request',
                     'required': True,
                     'allowMultiple': False,
                     'dataType': requests_schema.ExecutionRequest.__name__,
                     'paramType': 'body'}],
        consumes=[
            "application/json"
        ]
    )
    @marshal_with(responses.Execution.resource_fields)
    @exceptions_handled
    def post(self, deployment_id):
        """
        Execute a workflow
        """
        verify_json_content_type()
        verify_deployment_exists(deployment_id)
        request_json = request.json
        if 'workflowId' not in request_json:
            abort(400, message='400: Missing workflowId in json request body')
        workflow_id = request.json['workflowId']
        execution = blueprints_manager().execute_workflow(deployment_id,
                                                          workflow_id)
        return responses.Execution(**execution.to_dict()), 201


class DeploymentsIdWorkflows(Resource):

    @swagger.operation(
        responseClass='Workflows',
        nickname="workflows",
        notes="Returns a list of workflows related to the provided deployment."
    )
    @marshal_with(responses.Workflows.resource_fields)
    def get(self, deployment_id):
        """
        Returns a list of workflows related to the provided deployment.
        """
        verify_deployment_exists(deployment_id)
        deployment = blueprints_manager().get_deployment(deployment_id)
        deployment_workflows = deployment.plan['workflows']
        workflows = [responses.Workflow(name=wf_name) for wf_name in
                     deployment_workflows.keys()]

        return {
            'workflows': workflows,
            'blueprint_id': deployment.blueprint_id,
            'deployment_id': deployment.id
        }


def _query_elastic_search(index=None, doc_type=None, body=None):
    """Query ElasticSearch with the provided index and query body.

    Returns:
    ElasticSearch result as is (Python dict).
    """
    es = elasticsearch.Elasticsearch()
    return es.search(index=index, doc_type=doc_type, body=body)


class Events(Resource):

    def _query_events(self):
        """
        Returns events for the provided ElasticSearch query
        """
        verify_json_content_type()
        return _query_elastic_search(index='cloudify_events',
                                     body=request.json)

    @swagger.operation(
        nickname='events',
        notes='Returns a list of events for the provided ElasticSearch query. '
              'The response format is as ElasticSearch response format.',
        parameters=[{'name': 'body',
                     'description': 'ElasticSearch query.',
                     'required': True,
                     'allowMultiple': False,
                     'dataType': 'string',
                     'paramType': 'body'}],
        consumes=['application/json']
    )
    def get(self):
        """
        Returns events for the provided ElasticSearch query
        """
        return self._query_events()

    @swagger.operation(
        nickname='events',
        notes='Returns a list of events for the provided ElasticSearch query. '
              'The response format is as ElasticSearch response format.',
        parameters=[{'name': 'body',
                     'description': 'ElasticSearch query.',
                     'required': True,
                     'allowMultiple': False,
                     'dataType': 'string',
                     'paramType': 'body'}],
        consumes=['application/json']
    )
    def post(self):
        """
        Returns events for the provided ElasticSearch query
        """
        return self._query_events()
