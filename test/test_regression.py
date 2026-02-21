#!/usr/bin/env python3
"""
Black-box regression tests for sysmlv2-tool.

Runs the fat JAR via subprocess and validates exit codes and output.
Each JVM startup takes a few seconds; the full suite is expected to take
a couple of minutes.

Usage (from project root):
    python test/test_regression.py

Usage (from test/ directory):
    python test_regression.py

Requirements:
    - Python 3.8+
    - 'java' available on PATH
    - JAR built at:  src/sysmlv2-tool-assembly/target/sysmlv2-tool-fat.jar
      Build with:    cd src && mvn compile package
"""

import re
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

# ---------------------------------------------------------------------------
# Paths
# ---------------------------------------------------------------------------
SCRIPT_DIR = Path(__file__).parent.resolve()
PROJECT_ROOT = SCRIPT_DIR.parent
JAR = PROJECT_ROOT / "src" / "sysmlv2-tool-assembly" / "target" / "sysmlv2-tool-fat.jar"
TEST_MODEL = PROJECT_ROOT / "test" / "test_model"
DEP_MODEL = PROJECT_ROOT / "test" / "dependency_test_model"


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def run_tool(*args, cwd=None):
    """Run the sysmlv2-tool JAR with the given arguments.

    Returns (returncode, stdout, stderr).
    The tool is always started from PROJECT_ROOT so that the standard
    library is found automatically via the relative paths in SysMLEngineHelper.
    """
    cmd = ["java", "-jar", str(JAR)] + [str(a) for a in args]
    result = subprocess.run(
        cmd,
        capture_output=True,
        text=True,
        cwd=str(cwd or PROJECT_ROOT),
    )
    return result.returncode, result.stdout, result.stderr


def combined(out, err):
    """Return stdout + stderr as a single string for assertions."""
    return out + err


def fail_msg(description, rc, out, err, max_chars=600):
    """Format a human-readable failure message that shows rc, stdout, stderr."""
    return (
        f"{description}\n"
        f"  exit code : {rc}\n"
        f"  stdout    : {out.strip()[:max_chars]}\n"
        f"  stderr    : {err.strip()[:max_chars]}"
    )


# ---------------------------------------------------------------------------
# Test suites
# ---------------------------------------------------------------------------

class TestVersionAndHelp(unittest.TestCase):
    """Basic sanity: the tool responds to --version and produces help text."""

    def test_version_short_flag(self):
        rc, out, err = run_tool("-v")
        self.assertEqual(rc, 0, fail_msg("'-v' should exit 0", rc, out, err))
        self.assertRegex(
            combined(out, err), r"\d+\.\d+",
            "Expected a version number (x.y) in -v output",
        )

    def test_version_long_flag(self):
        rc, out, err = run_tool("--version")
        self.assertEqual(rc, 0, fail_msg("'--version' should exit 0", rc, out, err))
        self.assertRegex(
            combined(out, err), r"\d+\.\d+",
            "Expected a version number (x.y) in --version output",
        )

    def test_no_args_shows_usage(self):
        """Running with no arguments should print usage / available commands."""
        rc, out, err = run_tool()
        c = combined(out, err)
        self.assertRegex(
            c, r"(?i)usage|validate|diagram|views",
            "Expected usage / command names when run with no arguments",
        )

    def test_help_validate(self):
        rc, out, err = run_tool("help", "validate")
        c = combined(out, err)
        self.assertRegex(c, r"(?i)validate", "Expected 'validate' in help output")

    def test_help_diagram(self):
        rc, out, err = run_tool("help", "diagram")
        c = combined(out, err)
        self.assertRegex(
            c, r"(?i)diagram|element|view",
            "Expected 'diagram' keyword in diagram help output",
        )

    def test_help_views(self):
        rc, out, err = run_tool("help", "views")
        c = combined(out, err)
        self.assertRegex(c, r"(?i)view", "Expected 'view' keyword in views help output")


# ---------------------------------------------------------------------------

class TestValidateSingleFile(unittest.TestCase):
    """validate command on individual .sysml files."""

    # --- Valid files --------------------------------------------------------

    def test_empty_file_exits_zero(self):
        rc, out, err = run_tool("validate", TEST_MODEL / "test_empty.sysml")
        self.assertEqual(
            rc, 0,
            fail_msg("test_empty.sysml should validate cleanly", rc, out, err),
        )

    def test_scalar_values_exits_zero(self):
        rc, out, err = run_tool("validate", TEST_MODEL / "test_ScalarValues.sysml")
        self.assertEqual(
            rc, 0,
            fail_msg("test_ScalarValues.sysml should validate cleanly", rc, out, err),
        )

    def test_views_file_exits_zero(self):
        rc, out, err = run_tool("validate", TEST_MODEL / "test_views.sysml")
        self.assertEqual(
            rc, 0,
            fail_msg("test_views.sysml should validate cleanly", rc, out, err),
        )

    # --- Invalid files (must fail) -----------------------------------------

    def test_syntax_error_exits_nonzero(self):
        rc, out, err = run_tool("validate", TEST_MODEL / "test_syntax_error.sysml")
        self.assertNotEqual(
            rc, 0,
            fail_msg(
                "test_syntax_error.sysml must fail validation (non-zero exit)",
                rc, out, err,
            ),
        )

    def test_syntax_error_output_mentions_error(self):
        rc, out, err = run_tool("validate", TEST_MODEL / "test_syntax_error.sysml")
        self.assertRegex(
            combined(out, err).lower(),
            r"error|invalid|fail|syntax|parse",
            "Expected an error message for test_syntax_error.sysml",
        )

    def test_invalid_type_exits_nonzero(self):
        rc, out, err = run_tool("validate", TEST_MODEL / "test_invalid_type.sysml")
        self.assertNotEqual(
            rc, 0,
            fail_msg(
                "test_invalid_type.sysml must fail validation (non-zero exit)",
                rc, out, err,
            ),
        )

    def test_invalid_type_output_mentions_error(self):
        rc, out, err = run_tool("validate", TEST_MODEL / "test_invalid_type.sysml")
        self.assertRegex(
            combined(out, err).lower(),
            r"error|invalid|fail|type|unknown",
            "Expected an error message for test_invalid_type.sysml",
        )

    # --- Output format: XML -------------------------------------------------

    def test_xml_format_valid_file_exits_zero(self):
        rc, out, err = run_tool(
            "validate", "-f", "xml", TEST_MODEL / "test_empty.sysml"
        )
        self.assertEqual(
            rc, 0,
            fail_msg("validate -f xml on valid file should exit 0", rc, out, err),
        )

    def test_xml_format_valid_file_contains_testsuite(self):
        rc, out, err = run_tool(
            "validate", "-f", "xml", TEST_MODEL / "test_empty.sysml"
        )
        self.assertRegex(
            combined(out, err),
            r"(?i)<testsuite|testsuite",
            "Expected <testsuite> element in XML output for valid file",
        )

    def test_xml_format_invalid_file_contains_failure(self):
        rc, out, err = run_tool(
            "validate", "-f", "xml", TEST_MODEL / "test_syntax_error.sysml"
        )
        self.assertNotEqual(rc, 0)
        self.assertRegex(
            combined(out, err),
            r"(?i)<failure|<error|failure|error",
            "Expected failure/error elements in XML output for invalid file",
        )


# ---------------------------------------------------------------------------

class TestValidateFolder(unittest.TestCase):
    """validate command on directories (recursive scanning)."""

    def test_test_model_folder_exits_nonzero(self):
        """test_model/ contains invalid files; the folder result must be non-zero."""
        rc, out, err = run_tool("validate", TEST_MODEL)
        self.assertNotEqual(
            rc, 0,
            fail_msg(
                "validate test_model/ should fail (contains invalid .sysml files)",
                rc, out, err,
            ),
        )

    def test_test_model_folder_output_mentions_errors(self):
        rc, out, err = run_tool("validate", TEST_MODEL)
        self.assertRegex(
            combined(out, err).lower(),
            r"error|invalid|fail|syntax",
            "Expected error keywords in output for test_model/ folder",
        )

    def test_dependency_model_folder_exits_zero(self):
        """dependency_test_model/ should validate cleanly when loaded as a unit."""
        rc, out, err = run_tool("validate", DEP_MODEL)
        self.assertEqual(
            rc, 0,
            fail_msg(
                "validate dependency_test_model/ should succeed", rc, out, err
            ),
        )

    def test_dependency_model_xml_format(self):
        """validate -f xml on a valid folder should produce testsuite XML."""
        rc, out, err = run_tool("validate", "-f", "xml", DEP_MODEL)
        self.assertEqual(
            rc, 0,
            fail_msg("validate -f xml on dep model should exit 0", rc, out, err),
        )
        self.assertRegex(
            combined(out, err),
            r"(?i)<testsuite|testsuite",
            "Expected <testsuite> element in XML output",
        )


# ---------------------------------------------------------------------------

class TestViewsCommand(unittest.TestCase):
    """views command — lists ViewDefinition and ViewUsage elements."""

    # --- test_views.sysml defines softwareView + hardwareView ---------------

    def test_views_file_exits_zero(self):
        rc, out, err = run_tool("views", TEST_MODEL / "test_views.sysml")
        self.assertEqual(
            rc, 0,
            fail_msg("views command should succeed on test_views.sysml", rc, out, err),
        )

    def test_views_shows_software_view(self):
        rc, out, err = run_tool("views", TEST_MODEL / "test_views.sysml")
        self.assertIn(
            "softwareView",
            combined(out, err),
            "Expected 'softwareView' to appear in views output",
        )

    def test_views_shows_hardware_view(self):
        rc, out, err = run_tool("views", TEST_MODEL / "test_views.sysml")
        self.assertIn(
            "hardwareView",
            combined(out, err),
            "Expected 'hardwareView' to appear in views output",
        )

    def test_views_shows_viewusages_section(self):
        rc, out, err = run_tool("views", TEST_MODEL / "test_views.sysml")
        self.assertRegex(
            combined(out, err),
            r"(?i)ViewUsage|ViewDefinition",
            "Expected 'ViewUsages' or 'ViewDefinitions' section header in output",
        )

    def test_views_lists_exposed_elements(self):
        """softwareView exposes sws001/tsc001, hardwareView exposes hws001/tsc001."""
        rc, out, err = run_tool("views", TEST_MODEL / "test_views.sysml")
        c = combined(out, err)
        self.assertRegex(
            c,
            r"(?i)expose|sws001|tsc001|hws001",
            "Expected exposed elements (sws001, tsc001, hws001) in views output",
        )

    def test_views_no_java_exception(self):
        """Output must never contain a raw Java stack trace."""
        rc, out, err = run_tool("views", TEST_MODEL / "test_views.sysml")
        c = combined(out, err)
        self.assertNotRegex(
            c,
            r"at org\.|at java\.",
            "Unexpected Java stack trace in views output",
        )

    # --- dependency_test_model defines softwareSafetyView + hardwareSafetyView

    def test_views_dep_model_folder_exits_zero(self):
        rc, out, err = run_tool("views", DEP_MODEL)
        self.assertEqual(
            rc, 0,
            fail_msg("views on dependency_test_model/ should succeed", rc, out, err),
        )

    def test_views_dep_model_shows_safety_views(self):
        rc, out, err = run_tool("views", DEP_MODEL)
        self.assertRegex(
            combined(out, err),
            r"(?i)software.*view|hardware.*view|SafetyView|safetyView",
            "Expected software/hardware safety view names in dep model output",
        )


# ---------------------------------------------------------------------------

class TestDiagramElement(unittest.TestCase):
    """diagram --element / -e option — render a named element."""

    def setUp(self):
        self.outdir = tempfile.mkdtemp(prefix="sysmltest_elem_")

    def tearDown(self):
        shutil.rmtree(self.outdir, ignore_errors=True)

    def test_element_batterySystem_exits_zero(self):
        rc, out, err = run_tool(
            "diagram", "--element", "batterySystem",
            "-o", self.outdir,
            TEST_MODEL / "test_views.sysml",
        )
        self.assertEqual(
            rc, 0,
            fail_msg("diagram --element batterySystem should exit 0", rc, out, err),
        )

    def test_element_batterySystem_creates_output_file(self):
        run_tool(
            "diagram", "--element", "batterySystem",
            "-o", self.outdir,
            TEST_MODEL / "test_views.sysml",
        )
        files = list(Path(self.outdir).rglob("*.*"))
        self.assertTrue(
            len(files) > 0,
            f"Expected at least one output file in {self.outdir} for --element batterySystem",
        )

    def test_element_default_format_is_puml(self):
        """Without -f, the default output format is .puml."""
        run_tool(
            "diagram", "--element", "batterySystem",
            "-o", self.outdir,
            TEST_MODEL / "test_views.sysml",
        )
        puml_files = list(Path(self.outdir).rglob("*.puml"))
        self.assertTrue(
            len(puml_files) > 0,
            f"Expected .puml file(s) in {self.outdir} (default format should be puml)",
        )

    def test_element_svg_format(self):
        rc, out, err = run_tool(
            "diagram", "--element", "batterySystem",
            "-f", "svg",
            "-o", self.outdir,
            TEST_MODEL / "test_views.sysml",
        )
        self.assertEqual(
            rc, 0,
            fail_msg("diagram --element -f svg should exit 0", rc, out, err),
        )
        svg_files = list(Path(self.outdir).rglob("*.svg"))
        self.assertTrue(
            len(svg_files) > 0,
            f"Expected .svg file(s) in {self.outdir} when -f svg is used",
        )

    def test_element_short_flag(self):
        """-e is the short form of --element."""
        rc, out, err = run_tool(
            "diagram", "-e", "batterySystem",
            "-o", self.outdir,
            TEST_MODEL / "test_views.sysml",
        )
        self.assertEqual(
            rc, 0,
            fail_msg("diagram -e batterySystem (short flag) should exit 0", rc, out, err),
        )


# ---------------------------------------------------------------------------

class TestDiagramView(unittest.TestCase):
    """diagram --view option — render a named view."""

    def setUp(self):
        self.outdir = tempfile.mkdtemp(prefix="sysmltest_view_")

    def tearDown(self):
        shutil.rmtree(self.outdir, ignore_errors=True)

    def test_software_view_exits_zero(self):
        rc, out, err = run_tool(
            "diagram", "--view", "softwareView",
            "-o", self.outdir,
            TEST_MODEL / "test_views.sysml",
        )
        self.assertEqual(
            rc, 0,
            fail_msg("diagram --view softwareView should exit 0", rc, out, err),
        )

    def test_software_view_creates_file(self):
        run_tool(
            "diagram", "--view", "softwareView",
            "-o", self.outdir,
            TEST_MODEL / "test_views.sysml",
        )
        files = list(Path(self.outdir).rglob("*.*"))
        self.assertTrue(
            len(files) > 0,
            f"Expected at least one output file in {self.outdir} for softwareView",
        )

    def test_hardware_view_exits_zero(self):
        rc, out, err = run_tool(
            "diagram", "--view", "hardwareView",
            "-o", self.outdir,
            TEST_MODEL / "test_views.sysml",
        )
        self.assertEqual(
            rc, 0,
            fail_msg("diagram --view hardwareView should exit 0", rc, out, err),
        )

    def test_hardware_view_creates_file(self):
        run_tool(
            "diagram", "--view", "hardwareView",
            "-o", self.outdir,
            TEST_MODEL / "test_views.sysml",
        )
        files = list(Path(self.outdir).rglob("*.*"))
        self.assertTrue(
            len(files) > 0,
            f"Expected at least one output file in {self.outdir} for hardwareView",
        )

    def test_view_dep_model_software_safety(self):
        """Render softwareSafetyView from the dependency_test_model folder."""
        rc, out, err = run_tool(
            "diagram", "--view", "softwareSafetyView",
            "-o", self.outdir,
            DEP_MODEL,
        )
        self.assertEqual(
            rc, 0,
            fail_msg("diagram --view softwareSafetyView should exit 0", rc, out, err),
        )
        files = list(Path(self.outdir).rglob("*.*"))
        self.assertTrue(
            len(files) > 0,
            f"Expected output file(s) for softwareSafetyView in {self.outdir}",
        )


# ---------------------------------------------------------------------------

class TestDiagramSingle(unittest.TestCase):
    """diagram --single / -s option — one file per element, mirroring package hierarchy."""

    def setUp(self):
        self.outdir = tempfile.mkdtemp(prefix="sysmltest_single_")

    def tearDown(self):
        shutil.rmtree(self.outdir, ignore_errors=True)

    def test_single_exits_zero(self):
        rc, out, err = run_tool(
            "diagram", "--single",
            "-o", self.outdir,
            DEP_MODEL,
        )
        self.assertEqual(
            rc, 0,
            fail_msg("diagram --single should exit 0", rc, out, err),
        )

    def test_single_creates_output_files(self):
        run_tool("diagram", "--single", "-o", self.outdir, DEP_MODEL)
        files = list(Path(self.outdir).rglob("*.*"))
        self.assertTrue(
            len(files) > 0,
            f"Expected diagram files in {self.outdir} after --single",
        )

    def test_single_creates_package_subdirectories(self):
        """--single must mirror the package hierarchy as subdirectories."""
        run_tool("diagram", "--single", "-o", self.outdir, DEP_MODEL)
        subdir_names = {p.name for p in Path(self.outdir).rglob("*") if p.is_dir()}
        expected = {"SystemModel", "ProjectRequirements", "Components"}
        self.assertTrue(
            subdir_names & expected,
            f"Expected package subdirs {expected} under output; found: {subdir_names}",
        )

    def test_single_short_flag(self):
        """-s is the short form of --single."""
        rc, out, err = run_tool(
            "diagram", "-s",
            "-o", self.outdir,
            DEP_MODEL,
        )
        self.assertEqual(
            rc, 0,
            fail_msg("diagram -s (short flag) should exit 0", rc, out, err),
        )

    def test_single_svg_format(self):
        rc, out, err = run_tool(
            "diagram", "--single", "-f", "svg",
            "-o", self.outdir,
            DEP_MODEL,
        )
        self.assertEqual(
            rc, 0,
            fail_msg("diagram --single -f svg should exit 0", rc, out, err),
        )
        svg_files = list(Path(self.outdir).rglob("*.svg"))
        self.assertTrue(
            len(svg_files) > 0,
            f"Expected .svg files in {self.outdir} when -f svg is used with --single",
        )

    def test_single_and_all_elements_are_mutually_exclusive(self):
        """--single combined with --all-elements must be rejected (non-zero exit)."""
        rc, out, err = run_tool(
            "diagram", "--single", "--all-elements",
            "-o", self.outdir,
            DEP_MODEL,
        )
        self.assertNotEqual(
            rc, 0,
            fail_msg(
                "--single + --all-elements should be rejected with non-zero exit",
                rc, out, err,
            ),
        )

    def test_single_on_single_file(self):
        """--single should also work on a single .sysml file, not just directories."""
        rc, out, err = run_tool(
            "diagram", "--single",
            "-o", self.outdir,
            TEST_MODEL / "test_views.sysml",
        )
        self.assertEqual(
            rc, 0,
            fail_msg("diagram --single on test_views.sysml should exit 0", rc, out, err),
        )
        files = list(Path(self.outdir).rglob("*.*"))
        self.assertTrue(
            len(files) > 0,
            f"Expected output files in {self.outdir} for --single on test_views.sysml",
        )

    def test_single_default_format_is_puml(self):
        run_tool("diagram", "--single", "-o", self.outdir, DEP_MODEL)
        puml_files = list(Path(self.outdir).rglob("*.puml"))
        self.assertTrue(
            len(puml_files) > 0,
            f"Expected .puml file(s) in {self.outdir} (default format should be puml)",
        )


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------

if __name__ == "__main__":
    # Pre-flight checks
    errors = []
    if not JAR.exists():
        errors.append(
            f"JAR not found: {JAR}\n"
            "  Build with: cd src && mvn compile package"
        )
    if shutil.which("java") is None:
        errors.append("'java' not found on PATH")

    if errors:
        for e in errors:
            print(f"ERROR: {e}", file=sys.stderr)
        sys.exit(1)

    unittest.main(verbosity=2)
