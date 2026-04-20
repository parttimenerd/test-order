import argparse
import unittest
from unittest.mock import patch

import release


class ReleaseMainTest(unittest.TestCase):
    def make_args(self, **overrides):
        values = {
            "major": False,
            "minor": False,
            "patch": False,
            "no_its": False,
            "no_push": False,
            "no_github_release": False,
            "no_deploy": False,
            "no_release_profile": False,
            "github_release_only": False,
            "dry_run": False,
        }
        values.update(overrides)
        return argparse.Namespace(**values)

    @patch("release.ReleaseManager")
    @patch("release.parse_args")
    def test_main_pushes_before_deploy(self, parse_args, manager_class):
        parse_args.return_value = self.make_args()
        manager = manager_class.return_value
        manager.get_current_version.return_value = "0.1.0-SNAPSHOT"
        manager.to_base_version.return_value = "0.1.0"
        manager.bump_version.return_value = "0.2.0"
        manager.module_poms = []

        release.main()

        call_names = [call[0] for call in manager.mock_calls]
        self.assertLess(call_names.index("run_checks"), call_names.index("git_commit_tag"))
        self.assertLess(call_names.index("git_push"), call_names.index("deploy"))
        self.assertLess(call_names.index("deploy"), call_names.index("create_github_release"))

    @patch("release.sys.exit")
    @patch("release.ReleaseManager")
    @patch("release.parse_args")
    def test_push_failure_skips_deploy_and_restores_local_state(self, parse_args, manager_class, sys_exit):
        parse_args.return_value = self.make_args()
        manager = manager_class.return_value
        manager.get_current_version.return_value = "0.1.0-SNAPSHOT"
        manager.to_base_version.return_value = "0.1.0"
        manager.bump_version.return_value = "0.2.0"
        manager.module_poms = []
        manager.git_push.side_effect = RuntimeError("push failed")

        release.main()

        manager.deploy.assert_not_called()
        manager.create_github_release.assert_not_called()
        manager.restore_snapshots.assert_called_once()
        manager.cleanup_git_release_state.assert_called_once_with("0.2.0")
        sys_exit.assert_called_once_with(1)


if __name__ == "__main__":
    unittest.main()