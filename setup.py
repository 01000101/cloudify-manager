__author__ = 'elip'

from setuptools import setup

setup(
    name='cosmo-agent-installer',
    version='0.1.0',
    author='elip',
    author_email='elip@gigaspaces.com',
    packages=['worker_installer'],
    license='LICENSE',
    description='Plugin for starting a new cosmo agent on a remote host',
    install_requires=[
        "billiard==2.7.3.28",
        "fabric",
        "celery==3.0.19"
    ],
    tests_require=['nose', 'python-vagrant>=0.3.1']
)
