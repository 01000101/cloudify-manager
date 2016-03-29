# Copyright (c) 2016 GigaSpaces Technologies Ltd. All rights reserved
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
import os
import shutil
import time
import tempfile

from manager_rest.models import Execution
from testenv import TestCase
from testenv.utils import get_resource as resource
from testenv.utils import deploy_application as deploy
from testenv.utils import tar_blueprint

blueprints_base_path = 'dsl/deployment_update'


class TestDeploymentUpdate(TestCase):

    def _wait_for_execution(self, execution, timeout=900):
        # Poll for execution status until execution ends
        deadline = time.time() + timeout
        while True:
            if time.time() > deadline:
                raise Exception(
                    'execution of operation {0} for deployment {1} timed out'.
                    format(execution.workflow_id, execution.deployment_id))

            execution = self.client.executions.get(execution.id)
            if execution.status in Execution.END_STATES:
                return execution
            time.sleep(3)

    def _test_add_node(self, archive_mode=False):
        """
        add a node (type exists) which is contained in an existing node
        - assert that both node and node instance have been created
        - assert the node/instance relationships have been created
        - assert the 'update' workflow has been executed and
          all related operations were executed as well
        """
        predicted_relationship_type = 'cloudify.relationships.contained_in'
        predicted_node_type = 'new_node_type'
        test_name = 'dep_up_add_node'
        base_bp = '{0}_base.yaml'.format(test_name)
        modified_bp = '{0}.yaml'.format(test_name)

        initial_blueprint_path = resource(os.path.join(blueprints_base_path,
                                                       base_bp))
        deployment, _ = deploy(initial_blueprint_path)

        new_blueprint_path = \
            resource(os.path.join(blueprints_base_path,
                                  modified_bp))

        tempdir = tempfile.mkdtemp()
        try:
            if archive_mode:
                tar_path = tar_blueprint(new_blueprint_path, tempdir)
                dep_update = self.client.deployment_updates.\
                    stage_archive(deployment.id, tar_path,
                                  os.path.basename(new_blueprint_path))
            else:
                dep_update = \
                    self.client.deployment_updates.stage(deployment.id,
                                                         new_blueprint_path)
            self.client.deployment_updates.add(dep_update.id,
                                               entity_type='node',
                                               entity_id='new_site')
            self.client.deployment_updates.commit(dep_update.id)

            # assert that 'update' workflow was executed
            executions = \
                self.client.executions.list(deployment_id=deployment.id,
                                            workflow_id='update')
            execution = self._wait_for_execution(executions[0])
            self.assertEquals('terminated', execution['status'],
                              execution.error)

            added_nodes = self.client.nodes.list(deployment_id=deployment.id,
                                                 node_id='new_site')
            added_instances = \
                self.client.node_instances.list(deployment_id=deployment.id,
                                                node_id='new_site')

            # assert that node and node instance were added to storage
            self.assertEquals(1, len(added_nodes))
            self.assertEquals(1, len(added_instances))

            # assert that node has a relationship
            node = added_nodes[0]
            self.assertEquals(1, len(node.relationships))
            self._assert_relationship_exists(
                    node.relationships,
                    target='server',
                    expected_type=predicted_relationship_type)
            self.assertEquals(node.type, predicted_node_type)

            # assert that node instance has a relationship
            added_instance = added_instances[0]
            self.assertEquals(1, len(added_instance.relationships))
            self._assert_relationship_exists(
                 added_instance.relationships,
                 target='server',
                 expected_type=predicted_relationship_type)

            # assert all operations in 'update' ('install') workflow
            # are executed by making them increment a runtime property
            self.assertDictContainsSubset({'ops_counter': '6'},
                                          added_instance['runtime_properties'])
        finally:
            shutil.rmtree(tempdir, ignore_errors=True)

    @staticmethod
    def assert_equal_dictionaries(d1, d2, exceptions=()):
        for k, v in d1.iteritems():
            if cmp(d2[k], v) != 0 and k not in exceptions:
                raise Exception('The nodes differed on {0}. {1}!={2}'
                                .format(k, d1[k], d2[k]))

    def _deploy_and_get_modified_bp_path(self, base_bp, modified_bp):
        base_bp_path = \
            resource(os.path.join(blueprints_base_path, base_bp))
        deployment, _ = deploy(base_bp_path)
        modified_bp_path = \
            resource(os.path.join(blueprints_base_path, modified_bp))

        return deployment, modified_bp_path

    def test_add_relationship(self):
        bp_name = 'dep_up_add_relationship'
        base_bp = '{0}_base.yaml'.format(bp_name)
        modified_bp = '{0}.yaml'.format(bp_name)

        deployment, modified_bp_path = \
            self._deploy_and_get_modified_bp_path(base_bp, modified_bp)

        dep_update = \
            self.client.deployment_updates.stage(deployment.id,
                                                 modified_bp_path)

        self.client.deployment_updates.add(
                dep_update.id,
                entity_type='relationship',
                entity_id='site2:site1')

        self.client.deployment_updates.commit(dep_update.id)

        # assert that 'update' workflow was executed
        executions = \
            self.client.executions.list(deployment_id=deployment.id,
                                        workflow_id='update')
        execution = self._wait_for_execution(executions[0])
        self.assertEquals('terminated', execution['status'],
                          execution.error)

    def test_remove_relationship(self):
        bp_name = 'dep_up_remove_relationship'
        base_bp = '{0}_base.yaml'.format(bp_name)
        modified_bp = '{0}.yaml'.format(bp_name)

        deployment, modified_bp_path = \
            self._deploy_and_get_modified_bp_path(base_bp, modified_bp)

        dep_update = \
            self.client.deployment_updates.stage(deployment.id,
                                                 modified_bp_path)

        self.client.deployment_updates.remove(
                dep_update.id,
                entity_type='relationship',
                entity_id='site2:site1')

        self.client.deployment_updates.commit(dep_update.id)

        # assert that 'update' workflow was executed
        executions = \
            self.client.executions.list(deployment_id=deployment.id,
                                        workflow_id='update')
        execution = self._wait_for_execution(executions[0])
        self.assertEquals('terminated', execution['status'],
                          execution.error)

    def test_add_node(self):
        test_name = 'dep_up_add_node'
        base_bp = '{0}_base.yaml'.format(test_name)
        modified_bp = '{0}.yaml'.format(test_name)

        deployment, modified_bp_path = \
            self._deploy_and_get_modified_bp_path(base_bp, modified_bp)

        dep_update = \
            self.client.deployment_updates.stage(deployment.id,
                                                 modified_bp_path)

        self.client.deployment_updates.add(
                dep_update.id,
                entity_type='node',
                entity_id='site2')

        self.client.deployment_updates.commit(dep_update.id)

        # assert that 'update' workflow was executed
        executions = \
            self.client.executions.list(deployment_id=deployment.id,
                                        workflow_id='update')
        execution = self._wait_for_execution(executions[0])
        self.assertEquals('terminated', execution['status'],
                          execution.error)

    def _test_remove_node(self, archive_mode=False):
        bp_name = 'dep_up_remove_node'
        base_bp = '{0}_base.yaml'.format(bp_name)
        modified_bp = '{0}.yaml'.format(bp_name)

        deployment, modified_bp_path = \
            self._deploy_and_get_modified_bp_path(base_bp, modified_bp)

        tempdir = tempfile.mkdtemp()
        try:
            if archive_mode:
                tar_path = tar_blueprint(modified_bp_path, tempdir)
                dep_update = self.client.deployment_updates. \
                    stage_archive(deployment.id, tar_path,
                                  os.path.basename(modified_bp_path))
            else:
                dep_update = \
                    self.client.deployment_updates.stage(deployment.id,
                                                         modified_bp_path)
            self.client.deployment_updates.remove(
                    dep_update.id,
                    entity_type='node',
                    entity_id='site2')

            self.client.deployment_updates.commit(dep_update.id)

            # assert that 'update' workflow was executed
            executions = \
                self.client.executions.list(deployment_id=deployment.id,
                                            workflow_id='update')
            execution = self._wait_for_execution(executions[0])
            self.assertEquals('terminated', execution['status'],
                              execution.error)

        finally:
            shutil.rmtree(tempdir, ignore_errors=True)

    def test_add_node_bp(self):
        self._test_add_node()

    def test_add_nodes_archive(self):
        self._test_add_node(archive_mode=True)

    def test_add_relationship_bp(self):
        self._test_remove_node()

    def _assert_relationship_exists(self, relationships, target,
                                    expected_type=None):
        """
        assert that a node/node instance has a specific relationship
        :param relationships: node/node instance relationships list
        :param target: target name (node id, not instance id)
        :param expected_type: expected relationship type
        """
        expected_type = expected_type or 'cloudify.relationships.contained_in'
        for relationship in relationships:
            relationship_type = relationship['type']
            relationship_target = (relationship.get('target_name') or
                                   relationship.get('target_id'))

        if (relationship_type == expected_type and
                relationship_target == target):
                return

        self.fail('relationship of target "{}" and type "{}" is missing'
                  .format(target, expected_type))
