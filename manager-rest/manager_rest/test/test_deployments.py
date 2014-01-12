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


from base_test import BaseServerTestCase
from test_blueprints import post_blueprint_args


class DeploymentsTestCase(BaseServerTestCase):

    def _post_test_deployment(self):
        blueprint_response = self.post_file(*post_blueprint_args()).json
        blueprint_id = blueprint_response['id']
        #Execute post deployment
        deployment_response = self.post('/deployments',
                                        {'blueprintId': blueprint_id}).json
        return (blueprint_id, deployment_response['id'], blueprint_response,
                deployment_response)

    def test_get_empty(self):
        result = self.get('/deployments')
        self.assertEquals(0, len(result.json))

    def test_post(self):
        (blueprint_id,
         deployment_id,
         blueprint_response,
         deployment_response) = self._post_test_deployment()

        self.assertIsNotNone(deployment_id)
        self.assertEquals(blueprint_id, deployment_response['blueprintId'])
        self.assertIsNotNone(deployment_response['createdAt'])
        self.assertIsNotNone(deployment_response['updatedAt'])
        import json
        typed_blueprint_plan = json.loads(blueprint_response['plan'])
        typed_deployment_plan = json.loads(deployment_response['plan'])
        self.assertEquals(typed_blueprint_plan['name'],
                          typed_deployment_plan['name'])

    def test_get_by_id(self):
        (blueprint_id, deployment_id, blueprint_response,
         deployment_response) = self._post_test_deployment()

        single_deployment = self.get('/deployments/{0}'
                                     .format(deployment_id)).json
        self.assertEquals(deployment_id, single_deployment['id'])
        self.assertEquals(deployment_response['blueprintId'],
                          single_deployment['blueprintId'])
        self.assertEquals(deployment_response['id'],
                          single_deployment['id'])
        self.assertEquals(deployment_response['createdAt'],
                          single_deployment['createdAt'])
        self.assertEquals(deployment_response['createdAt'],
                          single_deployment['updatedAt'])
        self.assertEquals(deployment_response['plan'],
                          single_deployment['plan'])

    def test_get(self):
        (blueprint_id, deployment_id, blueprint_response,
         deployment_response) = self._post_test_deployment()

        get_deployments_response = self.get('/deployments').json
        self.assertEquals(1, len(get_deployments_response))
        single_deployment = get_deployments_response[0]
        self.assertEquals(deployment_id, single_deployment['id'])
        self.assertEquals(deployment_response['blueprintId'],
                          single_deployment['blueprintId'])
        self.assertEquals(deployment_response['id'],
                          single_deployment['id'])
        self.assertEquals(deployment_response['createdAt'],
                          single_deployment['createdAt'])
        self.assertEquals(deployment_response['createdAt'],
                          single_deployment['updatedAt'])
        self.assertEquals(deployment_response['plan'],
                          single_deployment['plan'])

    def test_get_blueprints_id_executions_empty(self):
        (blueprint_id, deployment_id, blueprint_response,
         deployment_response) = self._post_test_deployment()
        get_executions = self.get('/deployments/{0}/executions'
                                  .format(deployment_response['id'])).json
        self.assertEquals(len(get_executions), 0)

    def test_get_execution_by_id(self):
        (blueprint_id, deployment_id, blueprint_response,
         deployment_response) = self._post_test_deployment()

        resource_path = '/deployments/{0}/executions'.format(deployment_id)
        execution = self.post(resource_path, {
            'workflowId': 'install'
        }).json
        get_execution_resource = '/executions/{0}'.format(execution['id'])
        get_execution = self.get(get_execution_resource).json
        self.assertEquals(get_execution['status'], 'terminated')
        self.assertEquals(get_execution['blueprintId'], blueprint_id)
        self.assertEquals(get_execution['deploymentId'],
                          deployment_response['id'])
        self.assertIsNotNone(get_execution['createdAt'])

    def test_get_executions_of_deployment(self):
        (blueprint_id, deployment_id, blueprint_response,
         deployment_response) = self._post_test_deployment()

        resource_path = '/deployments/{0}/executions'.format(deployment_id)
        execution = self.post(resource_path, {
            'workflowId': 'install'
        }).json
        self.assertEquals(execution['workflowId'], 'install')
        self.assertEquals(execution['blueprintId'], blueprint_id)
        self.assertEquals(execution['deploymentId'], deployment_response['id'])
        self.assertIsNotNone(execution['createdAt'])
        get_execution = self.get(resource_path).json
        self.assertEquals(1, len(get_execution))
        self.assertEquals(execution, get_execution[0])

    def test_get_workflows_of_deployment(self):
        (blueprint_id, deployment_id, blueprint_response,
         deployment_response) = self._post_test_deployment()

        resource_path = '/deployments/{0}/workflows'.format(deployment_id)
        workflows = self.get(resource_path).json
        self.assertEquals(workflows['blueprintId'], blueprint_id)
        self.assertEquals(workflows['deploymentId'], deployment_id)
        self.assertEquals(2, len(workflows['workflows']))
        self.assertEquals(workflows['workflows'][0]['name'], 'install')
        self.assertTrue('createdAt' in workflows['workflows'][0])
        self.assertEquals(workflows['workflows'][1]['name'], 'uninstall')
        self.assertTrue('createdAt' in workflows['workflows'][1])
