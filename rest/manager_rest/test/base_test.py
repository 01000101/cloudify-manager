__author__ = 'dan'

import unittest
import json
from manager_rest import server
import time

class BaseServerTestCase(unittest.TestCase):

    def setUp(self):
        server.main()
        server.app.config['Testing'] = True
        self.app = server.app.test_client()

    def tearDown(self):
        server.stop_file_server()
        time.sleep(2)

    def post(self, resource_path, data):
        result = self.app.post(resource_path, content_type='application/json', data=json.dumps(data))
        result.json = json.loads(result.data)
        return result

    def post_file(self, resource_path, file_path, attribute_name, file_name, data):
        with open(file_path) as f:
            result = self.app.post(resource_path, data=dict({attribute_name: (f, file_name)}.items() + data.items()))
            result.json = json.loads(result.data)
            return result

    def put(self, resource_path, data):
        result = self.app.put(resource_path, content_type='application/json', data=json.dumps(data))
        result.json = json.loads(result.data)
        return result

    def patch(self, resource_path, data):
        result = self.app.patch(resource_path, content_type='application/json', data=json.dumps(data))
        result.json = json.loads(result.data)
        return result

    def get(self, resource_path):
        result = self.app.get(resource_path)
        result.json = json.loads(result.data)
        return result

