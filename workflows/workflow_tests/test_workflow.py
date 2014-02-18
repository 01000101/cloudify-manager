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

from cosmo_manager_rest_client.cosmo_manager_rest_client import \
    CosmoManagerRestCallError

from testenv import undeploy_application as undeploy

from workflow_tests.testenv import TestCase
from workflow_tests.testenv import get_resource as resource
from workflow_tests.testenv import deploy_application as deploy


class BasicWorkflowsTest(TestCase):

    def test_execute_operation(self):
        dsl_path = resource("dsl/basic.yaml")
        deploy(dsl_path)

        from plugins.cloudmock.tasks import get_machines
        result = self.send_task(get_machines)
        machines = result.get(timeout=10)

        self.assertEquals(1, len(machines))

    def test_dependencies_order_with_two_nodes(self):
        dsl_path = resource("dsl/dependencies-order-with-two-nodes.yaml")
        deploy(dsl_path)

        from plugins.testmockoperations.tasks import get_state as \
            testmock_get_state
        states = self.send_task(testmock_get_state)\
            .get(timeout=10)
        self.assertEquals(2, len(states))
        self.assertTrue('containing_node' in states[0]['id'])
        self.assertTrue('contained_in_node' in states[1]['id'])

    def test_cloudify_runtime_properties_injection(self):
        dsl_path = resource("dsl/dependencies-order-with-two-nodes.yaml")
        deploy(dsl_path)

        from plugins.testmockoperations.tasks import get_state as \
            testmock_get_state
        states = self.send_task(testmock_get_state).get(timeout=10)
        node_runtime_props = None
        for k, v in states[1]['capabilities'].iteritems():
            if 'containing_node' in k:
                node_runtime_props = v
                break
        self.assertEquals('value1', node_runtime_props['property1'])
        # length should be 2 because of auto injected ip property
        self.assertEquals(2, len(node_runtime_props))

    def test_dsl_with_manager_plugin(self):
        dsl_path = resource("dsl/with_manager_plugin.yaml")
        deployment_id = deploy(dsl_path).id

        from plugins.worker_installer.tasks import \
            RESTARTED, STARTED, INSTALLED, STOPPED, UNINSTALLED

        from plugins.worker_installer.tasks \
            import get_current_worker_state as \
            test_get_current_worker_state
        result = self.send_task(test_get_current_worker_state)
        state = result.get(timeout=10)
        self.assertEquals(state, [INSTALLED, STARTED, RESTARTED])

        undeploy(deployment_id)
        result = self.send_task(test_get_current_worker_state)
        state = result.get(timeout=10)
        self.assertEquals(state,
                          [INSTALLED, STARTED,
                           RESTARTED, STOPPED, UNINSTALLED])

    def test_non_existing_operation_exception(self):
        dsl_path = resource("dsl/wrong_operation_name.yaml")
        self.assertRaises(CosmoManagerRestCallError, deploy, dsl_path)

    def test_inject_properties_to_operation(self):
        dsl_path = resource("dsl/hardcoded-operation-properties.yaml")
        deploy(dsl_path)
        from plugins.testmockoperations.tasks import get_state as \
            testmock_get_state
        states = self.send_task(testmock_get_state).get(timeout=10)
        from plugins.testmockoperations.tasks import \
            get_mock_operation_invocations as testmock_get__invocations
        invocations = self.send_task(testmock_get__invocations).get(timeout=10)
        self.assertEqual(1, len(invocations))
        invocation = invocations[0]
        self.assertEqual('mockpropvalue', invocation['mockprop'])
        self.assertEqual('mockpropvalue2', invocation['kwargs']['mockprop2'])
        self.assertTrue('__cloudify_context' in invocation['kwargs'])
        self.assertEqual(states[0]['id'], invocation['id'])

    # TODO runtime-model: can be enabled if storage will be cleared
    # after each test (currently impossible since storage is in-memory)
    # def test_set_note_state_in_plugin(self):
    #     dsl_path = resource("dsl/basic.yaml")
    #     deploy(dsl_path)
    #     from testenv import get_deployment_nodes
    #     nodes = get_deployment_nodes()
    #     self.assertEqual(1, len(nodes))
    #
    #     from testenv import logger
    #     logger.info("nodes: {0}".format(nodes))
    #
    #     node_id = nodes[0]['id']
    #     from testenv import get_node_state
    #     node_state = get_node_state(node_id)
    #     self.assertEqual(node_id, node_state['id'])
