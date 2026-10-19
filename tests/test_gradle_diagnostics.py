import tempfile
import unittest
from pathlib import Path
from tools.report_gradle_failure import failure_excerpt, test_failure_excerpt as extract_test_failure_excerpt


class GradleDiagnosticsTest(unittest.TestCase):
    def test_task_noise_does_not_hide_actual_failure(self):
        log = "> Task :app:compileDebugKotlin\n" * 100
        log += "FAILURE: Build failed\n\n* What went wrong:\nExecution failed for task ':app:mergeDebugResources'.\n> Missing resource\n\n* Try:\n> Run with --info\n"
        result = failure_excerpt(log)
        self.assertIn("Missing resource", result)
        self.assertNotIn("compileDebugKotlin", result)
        self.assertNotIn("Run with --info", result)

    def test_latest_failure_is_prioritized(self):
        log = "* What went wrong:\nold failure\n* Try:\nignore\n* What went wrong:\nnew failure\n* Exception is:\nstacktrace"
        self.assertIn("new failure", failure_excerpt(log))
        self.assertNotIn("old failure", failure_excerpt(log))

    def test_compiler_location_is_retained(self):
        result = failure_excerpt("e: file:///app/build.gradle.kts:90: Unresolved reference\nFAILURE: Build failed\n* What went wrong:\nScript compilation errors\n* Try:\nHelp")
        self.assertIn("Unresolved reference", result)
        self.assertIn("Script compilation errors", result)

    def test_unit_test_failures_are_extracted_from_gradle_xml(self):
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "TEST-suite.xml"
            report.write_text('<testsuite><testcase classname="Example" name="bad"><failure message="expected 1"/></testcase></testsuite>')
            result = extract_test_failure_excerpt(Path(directory))
        self.assertIn("Example.bad: expected 1", result)
