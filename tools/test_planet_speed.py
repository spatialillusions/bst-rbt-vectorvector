import unittest
from benchmark_planet_speed import transfer_bytes, transfer_contended


class TransferPolicyTest(unittest.TestCase):
    def test_quiet_and_busy_intervals(self):
        self.assertEqual(transfer_bytes({1: 100}, {1: 200}), 100)
        self.assertFalse(transfer_contended(100, 10))
        self.assertTrue(transfer_contended(100*2**20, 10))

    def test_restarted_explorer_requires_retry(self):
        self.assertIsNone(transfer_bytes({1: 100}, {2: 200}))
        self.assertIsNone(transfer_bytes({1: 200}, {1: 100}))
        self.assertTrue(transfer_contended(None, 10))

    def test_no_explorer_is_valid_for_headless_runs(self):
        self.assertEqual(transfer_bytes({}, {}), 0)


if __name__ == '__main__': unittest.main()
