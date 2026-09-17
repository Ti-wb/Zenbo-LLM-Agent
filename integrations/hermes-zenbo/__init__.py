"""Hermes drop-in plugin entry point. No network or provider work at import time."""

from .plugin import register

__all__ = ["register"]
