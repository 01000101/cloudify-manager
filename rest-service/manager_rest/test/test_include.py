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

__author__ = 'idanmo'

from base_test import BaseServerTestCase


class IncludeQueryParamTests(BaseServerTestCase):

    def setUp(self):
        super(IncludeQueryParamTests, self).setUp()
        self.put_test_deployment(blueprint_file_name='blueprint.yaml')
        self.client.manager.create_context('test', {'hello': 'world'})

    def test_blueprints(self):
        response = self.client.blueprints.list(_include=['id'])
        for b in response:
            self.assertEqual(1, len(b))
            self.assertTrue('id' in b)
        blueprint_id = self.client.blueprints.list()[0].id
        response = self.client.blueprints.get(blueprint_id,
                                              _include=['id', 'created_at'])
        self.assertEqual(2, len(response))
        self.assertEqual(blueprint_id, response.id)
        self.assertTrue(response.created_at)

    def test_deployments(self):
        response = self.client.deployments.list(_include=['id'])
        for d in response:
            self.assertEqual(1, len(d))
            self.assertTrue('id' in d)
            deployment_id = d.id
        response = self.client.deployments.get(deployment_id,
                                               _include=['id', 'blueprint_id'])
        self.assertEqual(2, len(response))
        self.assertEqual(deployment_id, response.id)
        self.assertIsNotNone(response.blueprint_id)

    def test_executions(self):
        deployment_id = self.client.deployments.list()[0].id
        response = self.client.executions.list(deployment_id, _include=['id'])
        for e in response:
            self.assertEqual(1, len(e))
            self.assertTrue('id' in e)
            execution_id = e.id
        response = self.client.executions.get(execution_id,
                                              _include=['id', 'status'])
        self.assertEqual(2, len(response))
        self.assertEqual(execution_id, response.id)
        self.assertIsNotNone(response.status)

    def test_nodes(self):
        response = self.client.nodes.list(_include=['id', 'properties'])
        for n in response:
            self.assertEqual(2, len(n))
            self.assertTrue('id' in n)
            self.assertTrue('properties' in n)

    def test_node_instances(self):
        response = self.client.node_instances.list(
            _include=['id', 'runtime_properties'])
        for n in response:
            self.assertEqual(2, len(n))
            self.assertTrue('id' in n)
            self.assertTrue('runtime_properties' in n)
            instance_id = n.id
        response = self.client.node_instances.get(instance_id, _include=['id'])
        self.assertEqual(1, len(response))
        self.assertTrue(instance_id, response.id)

    def test_provider_context(self):
        response = self.client.manager.get_context()
        print response
