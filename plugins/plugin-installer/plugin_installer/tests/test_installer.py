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

import os
from os.path import dirname
import tempfile
import shutil

import testtools

from cloudify.exceptions import NonRecoverableError
from cloudify.mocks import MockCloudifyContext
from cloudify.utils import LocalCommandRunner
from cloudify.utils import setup_default_logger
from plugin_installer.tasks import install, update_includes
from cloudify.constants import CELERY_WORK_DIR_PATH_KEY
from cloudify.constants import VIRTUALENV_PATH_KEY
from cloudify.constants import LOCAL_IP_KEY
from cloudify.constants import MANAGER_FILE_SERVER_BLUEPRINTS_ROOT_URL_KEY
from plugin_installer.tests.file_server import FileServer
from plugin_installer.tests.file_server import PORT

logger = setup_default_logger('test_plugin_installer')


def _get_local_path(ctx, plugin):
    return os.path.join(dirname(__file__),
                        plugin['source'])


class PluginInstallerTestCase(testtools.TestCase):

    TEST_BLUEPRINT_ID = 'mock_blueprint_id'
    MANAGER_FILE_SERVER_BLUEPRINTS_ROOT_URL = "http://localhost:{0}"\
                                              .format(PORT)

    def setUp(self):
        super(PluginInstallerTestCase, self).setUp()
        self.temp_folder = tempfile.mkdtemp()

        # Create a virtualenv in a temp folder.
        # this will be used for actually installing plugins of tests.
        os.environ[LOCAL_IP_KEY] = 'localhost'
        LocalCommandRunner().run('virtualenv {0}'.format(self.temp_folder))
        os.environ[VIRTUALENV_PATH_KEY] = self.temp_folder

        self.ctx = MockCloudifyContext(
            blueprint_id=self.TEST_BLUEPRINT_ID
        )
        os.environ[CELERY_WORK_DIR_PATH_KEY] = self.temp_folder
        os.environ[MANAGER_FILE_SERVER_BLUEPRINTS_ROOT_URL_KEY] \
            = self.MANAGER_FILE_SERVER_BLUEPRINTS_ROOT_URL

        try:
            # start file server
            local_dir = dirname(__file__)
            test_file_server = FileServer(local_dir)
            test_file_server.start()
        except:
            if test_file_server:
                try:
                    test_file_server.stop()
                except:
                    # TODO: log "failed to stop file server"
                    print 'failed to stop file server'

    def tearDown(self):
        if os.path.exists(self.temp_folder):
            shutil.rmtree(self.temp_folder)

        local_dir = dirname(__file__)
        test_file_server = FileServer(local_dir)
        if test_file_server:
            try:
                test_file_server.stop()
            except:
                # TODO: log "failed to stop file server"
                print 'failed to stop file server'

        super(PluginInstallerTestCase, self).tearDown()

    def _assert_plugin_installed(self, package_name,
                                 plugin, dependencies=None):
        if not dependencies:
            dependencies = []
        runner = LocalCommandRunner()
        out = runner.run(
            '{0}/bin/pip list | grep {1}'
            .format(self.temp_folder, plugin['name'])).std_out
        self.assertIn(package_name, out)
        for dependency in dependencies:
            self.assertIn(dependency, out)

    def test_get_url_and_args_http_no_args(self):
        from plugin_installer.tasks import get_url_and_args
        url, args = get_url_and_args(self.ctx.blueprint.id,
                                     {'source': 'http://google.com'})
        self.assertEqual(url, 'http://google.com')
        self.assertEqual(args, '')

    def test_get_url_and_args_http_empty_args(self):
        from plugin_installer.tasks import get_url_and_args
        url, args = get_url_and_args(self.ctx.blueprint.id,
                                     {'source': 'http://google.com',
                                      'installation_args': ''})
        self.assertEqual(url, 'http://google.com')
        self.assertEqual(args, '')

    def test_get_url_and_args_http_with_args(self):
        from plugin_installer.tasks import get_url_and_args
        url, args = get_url_and_args(self.ctx.blueprint.id,
                                     {'source': 'http://google.com',
                                      'installation_args':
                                          '-r requirements.txt'})
        self.assertEqual(url, 'http://google.com')
        self.assertEqual(args, '-r requirements.txt')

    def test_get_url_and_args_https(self):
        from plugin_installer.tasks import get_url_and_args
        url, args = get_url_and_args(self.ctx.blueprint.id,
                                     {'source': 'https://google.com',
                                      'installation_args': '--pre'})
        self.assertEqual(url, 'https://google.com')
        self.assertEqual(args, '--pre')

    def test_get_url_faulty_schema(self):
        from plugin_installer.tasks import get_url_and_args
        self.assertRaises(NonRecoverableError,
                          get_url_and_args,
                          self.ctx.blueprint.id,
                          {'source': 'bla://google.com'})

    def test_get_url_and_args_local_plugin(self):
        from plugin_installer.tasks import get_url_and_args
        url, args = get_url_and_args(self.ctx.blueprint.id,
                                     {'source': 'mock-plugin',
                                      'installation_args': '-r requirements'})
        self.assertEqual(url,
                         '{0}/{1}/plugins/mock-plugin.zip'
                         .format(
                             self.MANAGER_FILE_SERVER_BLUEPRINTS_ROOT_URL,
                             self.TEST_BLUEPRINT_ID))

        self.assertEqual(args, '-r requirements')

    def test_install(self):

        plugin = {
            'name': 'mock-plugin',
            'source': 'mock-plugin'
        }

        ctx = MockCloudifyContext(blueprint_id=self.TEST_BLUEPRINT_ID)
        install(ctx, plugins=[plugin])
        self._assert_plugin_installed('mock-plugin', plugin)

        # Assert includes file was written
        out = LocalCommandRunner().run(
            'cat {0}'.format(
                os.path.join(self.temp_folder,
                             'celeryd-includes'))).std_out
        self.assertIn('mock_for_test.module', out)

    def test_install_with_dependencies(self):

        plugin = {
            'name': 'mock-with-dependencies-plugin',
            'source': 'mock-with-dependencies-plugin'
        }

        ctx = MockCloudifyContext(blueprint_id=self.TEST_BLUEPRINT_ID)
        install(ctx, plugins=[plugin])
        self._assert_plugin_installed('mock-with-dependencies-plugin',
                                      plugin,
                                      dependencies=['simplejson'])

        # Assert includes file was written
        out = LocalCommandRunner().run(
            'cat {0}'.format(
                os.path.join(self.temp_folder,
                             'celeryd-includes'))).std_out
        self.assertIn('mock_with_dependencies_for_test.module', out)

    def test_write_to_empty_includes(self):

        update_includes(['a.tasks', 'b.tasks'])

        # The includes file will be created
        # in the temp folder for this test
        with open('{0}/celeryd-includes'
                  .format(self.temp_folder), mode='r') as f:
            includes = f.read()
            self.assertEquals("INCLUDES=a.tasks,b.tasks\n", includes)

    def test_write_to_existing_includes(self):

        # Create initial includes file
        update_includes(['test.tasks'])

        # Append to that file
        update_includes(['a.tasks', 'b.tasks'])
        with open('{0}/celeryd-includes'
                  .format(self.temp_folder), mode='r') as f:
            includes = f.read()
            self.assertEquals(
                "INCLUDES=test.tasks,a.tasks,b.tasks\n",
                includes)
