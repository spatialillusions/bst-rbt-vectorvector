"""Checkpoint-aware worker recycling policy; independent of Windows APIs."""
class RecyclePolicy:
    def __init__(self, max_seconds=0, pressure_mib=0, checkpoint_wait_seconds=120):
        if max_seconds < 0 or pressure_mib < 0 or checkpoint_wait_seconds <= 0:
            raise ValueError('Invalid recycle thresholds')
        self.max_seconds = max_seconds
        self.pressure_bytes = pressure_mib * 2**20
        self.wait_seconds = checkpoint_wait_seconds
        self.low_samples = 0
        self.requested_at = None
        self.reason = None
        self.checkpoint_count = None

    def observe(self, elapsed, available_bytes, done_count):
        self.low_samples = self.low_samples + 1 if self.pressure_bytes and available_bytes < self.pressure_bytes else 0
        if self.requested_at is None:
            if self.low_samples >= 5:
                self.reason = 'memory-pressure'
            elif self.max_seconds and elapsed >= self.max_seconds:
                self.reason = 'session-time-limit'
            if self.reason:
                self.requested_at = elapsed
                self.checkpoint_count = done_count
        if self.requested_at is None:
            return False
        return done_count > self.checkpoint_count or elapsed - self.requested_at >= self.wait_seconds
