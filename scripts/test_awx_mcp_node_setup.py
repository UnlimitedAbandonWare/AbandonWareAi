import unittest

from scripts import awx_mcp_node_setup


class AwxMcpNodeSetupIsolationTest(unittest.TestCase):

    def test_posix_unc_path_is_shared(self) -> None:
        self.assertTrue(
            awx_mcp_node_setup.is_shared_source_path(
                "//server/share/producer-worktree"
            )
        )
        self.assertFalse(
            awx_mcp_node_setup.is_shared_source_path(
                "/server/share/producer-worktree"
            )
        )


if __name__ == "__main__":
    unittest.main()
