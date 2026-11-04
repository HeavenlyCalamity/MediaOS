"""Small local safeguards; intentionally no distributed queue or pricing guesses."""

import threading
import time

from .contracts import ProviderError


class ProviderGate:
    def __init__(self, max_concurrent: int = 1, requests_per_second: float = 1.0):
        if max_concurrent < 1 or requests_per_second <= 0:
            raise ValueError("provider concurrency and rate must be positive")
        self._semaphore = threading.BoundedSemaphore(max_concurrent)
        self._interval = 1.0 / requests_per_second
        self._lock = threading.Lock()
        self._last_started = 0.0

    def __enter__(self):
        self._semaphore.acquire()
        with self._lock:
            delay = self._interval - (time.monotonic() - self._last_started)
            if delay > 0:
                time.sleep(delay)
            self._last_started = time.monotonic()
        return self

    def __exit__(self, *_args):
        self._semaphore.release()


class BudgetGuard:
    def __init__(self, limit_usd: float | None):
        if limit_usd is not None and limit_usd < 0:
            raise ValueError("budget limit must be zero or greater")
        self.limit_usd = limit_usd
        self.spent_usd = 0.0

    def before_call(self, estimated_cost_usd: float | None, *, real_provider: bool) -> None:
        if self.limit_usd is None:
            return
        if estimated_cost_usd is None:
            if real_provider:
                raise ProviderError("budget_unverifiable", "job budget cannot be enforced without configured provider pricing")
            return
        if self.spent_usd + estimated_cost_usd > self.limit_usd:
            raise ProviderError("budget_exceeded", "configured job budget would be exceeded")

    def record(self, reported_cost_usd: float | None) -> None:
        if reported_cost_usd is not None:
            if reported_cost_usd < 0:
                raise ProviderError("invalid_usage", "provider returned invalid cost metadata")
            self.spent_usd += reported_cost_usd
            if self.limit_usd is not None and self.spent_usd > self.limit_usd:
                raise ProviderError("budget_exceeded", "reported provider cost exceeded the configured job budget")


def call_with_bounded_retry(operation, attempts: int = 1):
    """Retry only errors explicitly marked transient; default is one attempt."""
    if attempts < 1 or attempts > 3:
        raise ValueError("provider retries must be between zero and two")
    for index in range(attempts):
        try:
            return operation()
        except ProviderError as error:
            if not error.retryable or index + 1 == attempts:
                raise
    raise AssertionError("unreachable")
