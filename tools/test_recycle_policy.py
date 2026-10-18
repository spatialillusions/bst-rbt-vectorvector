import unittest
from recycle_policy import RecyclePolicy

class RecyclePolicyTest(unittest.TestCase):
    def test_disabled(self):
        p = RecyclePolicy()
        self.assertFalse(p.observe(10000, 0, 3))

    def test_waits_for_new_checkpoint(self):
        p = RecyclePolicy(max_seconds=10)
        self.assertFalse(p.observe(9, 0, 3))
        self.assertFalse(p.observe(10, 0, 3))
        self.assertFalse(p.observe(11, 0, 3))
        self.assertTrue(p.observe(12, 0, 4))
        self.assertEqual(p.reason, 'session-time-limit')

    def test_pressure_requires_five_consecutive_samples(self):
        p = RecyclePolicy(pressure_mib=10)
        for t in range(4): self.assertFalse(p.observe(t, 0, 1))
        self.assertFalse(p.observe(4, 20*2**20, 1))
        for t in range(5,10): self.assertFalse(p.observe(t, 0, 1))
        self.assertTrue(p.observe(10, 0, 2))
        self.assertEqual(p.reason, 'memory-pressure')

    def test_timeout_allows_rebuilding_unfinished_shard(self):
        p = RecyclePolicy(max_seconds=10, checkpoint_wait_seconds=5)
        self.assertFalse(p.observe(10, 0, 3))
        self.assertFalse(p.observe(14, 0, 3))
        self.assertTrue(p.observe(15, 0, 3))

    def test_invalid_thresholds(self):
        with self.assertRaises(ValueError): RecyclePolicy(max_seconds=-1)
        with self.assertRaises(ValueError): RecyclePolicy(checkpoint_wait_seconds=0)

if __name__ == '__main__': unittest.main()
