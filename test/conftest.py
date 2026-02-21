"""
pytest configuration for sysmlv2-tool regression tests.

Performs a pre-flight check before any test is collected so that a missing
JAR produces a single clear message instead of 46 individual subprocess
errors.
"""

import shutil
from pathlib import Path

_JAR = (
    Path(__file__).parent.parent
    / "src"
    / "sysmlv2-tool-assembly"
    / "target"
    / "sysmlv2-tool-fat.jar"
)


def pytest_configure(config):
    missing = []
    if not _JAR.exists():
        missing.append(
            f"JAR not found: {_JAR}\n"
            "  Build first: cd src && mvn compile package"
        )
    if shutil.which("java") is None:
        missing.append("'java' not found on PATH")

    if missing:
        import pytest
        pytest.exit("\n".join(["Pre-flight check failed:"] + missing), returncode=2)
