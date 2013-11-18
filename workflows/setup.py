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

__author__ = "idanmo"

from setuptools import setup
import os
import sys


PLUGIN_INSTALLER_VERSION = '0.3'
PLUGIN_INSTALLER_BRANCH = 'develop'
PLUGIN_INSTALLER = "https://github.com/CloudifySource/cosmo-plugin-plugin-installer/tarball/{" \
    "0}#egg=cosmo-plugin-plugin-installer-{1}".format(PLUGIN_INSTALLER_BRANCH, PLUGIN_INSTALLER_VERSION)

RIEMANN_CONFIGURER_VERSION = '0.3'
RIEMANN_CONFIGURER_BRANCH = 'develop'
RIEMANN_CONFIGURER = "https://github.com/CloudifySource/cosmo-plugin-riemann-configurer/tarball/{" \
                     "0}#egg=cosmo-plugin-riemann-configurer-{1}".format(RIEMANN_CONFIGURER_BRANCH,
                                                                         RIEMANN_CONFIGURER_VERSION)
DSL_PARSER_VERSION = '0.3'
DSL_PARSER_BRANCH = 'develop'
DSL_PARSER = "https://github.com/CloudifySource/cosmo-plugin-dsl-parser/tarball/{" \
                     "0}#egg=cosmo-plugin-dsl-parser-{1}".format(DSL_PARSER_BRANCH, DSL_PARSER_VERSION)

os.chdir(sys.path[0])

setup(
    name='cloudify-workflows',
    version='0.3',
    author='Idan Moyal',
    author_email='idan@gigaspaces.com',
    packages=['tests'],
    license='LICENSE',
    description='Cloudify workflow python tests',
    zip_safe=False,
    install_requires=[
        "celery==3.0.24",
        "bernhard",
        "nose",
        "cosmo-plugin-plugin-installer",
        "cosmo-plugin-riemann-configurer",
        "cosmo-plugin-dsl-parser"
    ],
    dependency_links=[PLUGIN_INSTALLER, RIEMANN_CONFIGURER, DSL_PARSER]
)