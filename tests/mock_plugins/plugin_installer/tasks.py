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

from cloudify.decorators import operation
import os
import json


DATA_FILE_PATH = '/tmp/plugin-installer-data.json'


@operation
def install(ctx, plugins, **kwargs):

    installed_plugins = _get_installed_plugins()

    for plugin in plugins:
        ctx.logger.info("in plugin_installer.install --> "
                        "installing plugin {0}".format(plugin))
        installed_plugins.append(plugin['name'])

    _store_installed_plugins(installed_plugins)


@operation
def get_installed_plugins(**kwargs):
    return _get_installed_plugins()


def _get_installed_plugins():
    with open(DATA_FILE_PATH, 'r') as f:
        installed_plugins = json.load(f)
        return installed_plugins


def _store_installed_plugins(installed_plugins):
    with open(DATA_FILE_PATH, 'w') as f:
        json.dump(installed_plugins, f)


def setup_plugin():
    _store_installed_plugins([])


def teardown_plugin():
    os.remove(DATA_FILE_PATH)
