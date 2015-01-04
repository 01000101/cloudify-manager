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


import archiving
from base_test import BaseServerTestCase
from cloudify_rest_client.exceptions import CloudifyClientError


class BlueprintsTestCase(BaseServerTestCase):

    def test_get_empty(self):
        result = self.get('/blueprints')
        self.assertEquals(0, len(result.json))

    def test_get_nonexistent_blueprint(self):
        try:
            self.client.blueprints.get('15')
        except CloudifyClientError, e:
            self.assertEqual(404, e.status_code)

    def test_server_traceback_on_error(self):
        try:
            self.client.blueprints.get('15')
        except CloudifyClientError, e:
            self.assertIsNotNone(e.server_traceback)

    def test_post_and_then_search(self):
        post_blueprints_response = self.put_file(
            *self.put_blueprint_args(blueprint_id='hello_world')).json
        self.assertEquals('hello_world', post_blueprints_response['id'])
        get_blueprints_response = self.get('/blueprints').json
        self.assertEquals(1, len(get_blueprints_response))
        self.assertEquals(post_blueprints_response, get_blueprints_response[0])

    def test_post_blueprint_already_exists(self):
        self.put_file(*self.put_blueprint_args())
        post_blueprints_response = self.put_file(*self.put_blueprint_args())
        self.assertTrue('already exists' in
                        post_blueprints_response.json['message'])
        self.assertEqual(409, post_blueprints_response.status_code)

    def test_put_blueprint(self):
        self._test_put_blueprint(archiving.make_targzfile)

    def test_post_without_application_file_form_data(self):
        post_blueprints_response = self.put_file(
            *self.put_blueprint_args('blueprint_with_workflows.yaml',
                                     blueprint_id='hello_world')).json
        self.assertEquals('hello_world',
                          post_blueprints_response['id'])

    def test_get_blueprint_by_id(self):
        post_blueprints_response = self.put_file(
            *self.put_blueprint_args()).json
        get_blueprint_by_id_response = self.get(
            '/blueprints/{0}'.format(post_blueprints_response['id'])).json
        # setting 'source' field to be None as expected
        self.assertEquals(post_blueprints_response,
                          get_blueprint_by_id_response)

    def test_delete_blueprint(self):
        post_blueprints_response = self.put_file(
            *self.put_blueprint_args()).json

        # testing if resources are on fileserver
        self.assertTrue(
            self.check_if_resource_on_fileserver(
                post_blueprints_response['id'], 'blueprint.yaml'))

        # deleting the blueprint that was just uploaded
        delete_blueprint_response = self.delete(
            '/blueprints/{0}'.format(post_blueprints_response['id'])).json
        self.assertEquals(post_blueprints_response['id'],
                          delete_blueprint_response['id'])

        # verifying deletion of blueprint
        resp = self.get('/blueprints/{0}'.format(post_blueprints_response[
                        'id']))
        self.assertEquals(404, resp.status_code)

        # verifying deletion of fileserver resources
        self.assertFalse(
            self.check_if_resource_on_fileserver(
                post_blueprints_response['id'], 'blueprint.yaml'))

        # trying to delete a nonexistent blueprint
        resp = self.delete('/blueprints/nonexistent-blueprint')
        self.assertEquals(404, resp.status_code)

    def test_zipped_plugin(self):
        self.put_file(*self.put_blueprint_args())
        self.check_if_resource_on_fileserver('hello_world',
                                             'plugins/stub-installer.zip')

    def test_put_zip_blueprint(self):
        self._test_put_blueprint(archiving.make_zipfile)

    def test_put_tar_blueprint(self):
        self._test_put_blueprint(archiving.make_tarfile)

    def test_put_bz2_blueprint(self):
        self._test_put_blueprint(archiving.make_tarbz2file)

    def _test_put_blueprint(self, archive_func):
        blueprint_id = 'new_blueprint_id'
        put_blueprints_response = self.put_file(
            *self.put_blueprint_args(blueprint_id=blueprint_id,
                                     archive_func=archive_func)).json
        self.assertEqual(blueprint_id, put_blueprints_response['id'])