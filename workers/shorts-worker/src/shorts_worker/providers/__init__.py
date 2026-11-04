"""Capability-based local and optional real production providers."""

from .registry import ProviderRegistry, default_registry

__all__ = ["ProviderRegistry", "default_registry"]
