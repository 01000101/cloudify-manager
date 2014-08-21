########
# Copyright (c) 2014 GigaSpaces Technologies Ltd. All rights reserved
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


from nose.tools import nottest

from testenv import TestCase
from testenv import get_resource as resource
from testenv import deploy_application as deploy
from testenv import send_task
# from testenv import undeploy_application as undeploy

from plugins.testmockoperations.tasks import \
    get_mock_operation_invocations as testmock_get_invocations


@nottest
class TestPolicies(TestCase):

    def test_policies(self):
        dsl_path = resource("dsl/with_policies.yaml")
        deployment, _ = deploy(dsl_path)
        self.publish_riemann_event(deployment.id,
                                   node_name='node')

        def assertion():
            executions = self.client.executions.list(deployment.id)
            self.assertEqual(3, len(executions))
            invocations = send_task(testmock_get_invocations).get(timeout=10)
            self.assertEqual(1, len(invocations))
            invocation = invocations[0]
            self.assertEqual('op1_value', invocation['op1_key'])
        self.do_assertions(assertion)
