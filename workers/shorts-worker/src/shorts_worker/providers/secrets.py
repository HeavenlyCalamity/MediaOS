"""Secret source abstraction; local environment only for this stage."""

import os
from typing import Protocol


class SecretResolver(Protocol):
    def resolve(self, name: str) -> str | None: ...


class EnvironmentSecretResolver:
    def resolve(self, name: str) -> str | None:
        return os.getenv(name)


def default_secret_resolver() -> SecretResolver:
    """Replaceable later by a Secret Manager adapter; no cloud lookup is done here."""
    return EnvironmentSecretResolver()
