import tempfile
from worker_installer.tests import get_remote_runner, terminate_vagrant, launch_vagrant, get_local_runner

__author__ = 'elip'


def _test_run(runner):
    runner.run("ls -l")


def _test_sudo(runner):
    runner.sudo("ls -l")


def _test_put(runner):
    data = "test"
    file_path = tempfile.NamedTemporaryFile().name
    runner.put(data, file_path)


def _test_get(runner):
    data = "test"
    file_path = tempfile.NamedTemporaryFile().name
    runner.put(data, file_path)
    output = runner.get(file_path)
    assert output == data


class TestLocalRunnerCase:

    """
    Tests the fabric runner localhost functioanllity.
    """

    RUNNER = None

    @classmethod
    def setup_class(cls):
        cls.RUNNER = get_local_runner()

    def test_run(self):
        _test_run(self.RUNNER)

    def test_sudo(self):
        """
        Note
        ====
        You can only run this if you are a passwordless sudo user on the local host.
        This is the case for vagrant machines, as well as travis machines.
        """
        _test_sudo(self.RUNNER)

    def test_put(self):
        _test_put(self.RUNNER)

    def test_get(self):
        _test_get(self.RUNNER)


class TestRemoteRunnerCase:

    VM_ID = "TestRemoteRunnerCase"
    RUNNER = None

    @classmethod
    def setup_class(cls):
        launch_vagrant(cls.VM_ID)
        cls.RUNNER = get_remote_runner()

    @classmethod
    def teardown_class(cls):
        terminate_vagrant(cls.VM_ID)

    def test_run(self):
        _test_run(self.RUNNER)

    def test_sudo(self):
        _test_sudo(self.RUNNER)

    def test_put(self):
        _test_put(self.RUNNER)

    def test_get(self):
        _test_get(self.RUNNER)
