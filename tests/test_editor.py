"""End-to-end checks against the compiled COBOL program; no dependencies."""

from pathlib import Path
import subprocess
import tempfile
import unittest


PROGRAM = Path(__file__).resolve().parents[1] / "file_editor"


class EditorTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.path = self.root / "my document.txt"

    def run_editor(self, *commands):
        result = subprocess.run(
            [str(PROGRAM)], input="\n".join(map(str, commands)) + "\n",
            text=True, capture_output=True, cwd=self.root, timeout=10,
        )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(result.stderr, "", result.stderr)
        self.assertFalse(list(self.root.glob("*.tmp.*")))
        return result.stdout

    def test_full_editing_workflow(self):
        output = self.run_editor(
            "1", self.path, "Y", "3", "alpha beta gamma",
            "7", "1", "2", "river", "4", "1", "delta epsilon",
            "8", "1", "3", "2", "6", "1", "moon star",
            "3", "remove me", "9", "2", "5", "4", "S", "Q",
        )
        self.assertIn("1: alpha river delta epsilon", output)
        lines = self.path.read_text().splitlines()
        self.assertEqual(lines[0], "moon star")
        self.assertEqual(len(lines), 2)
        self.assertEqual(len(lines[1].split()), 4)
        vocabulary = set("apple river mountain sunshine forest ocean garden "
                         "cloud journey coffee window music planet silver "
                         "meadow breeze cobalt story lantern dream".split())
        self.assertTrue(set(lines[1].split()) <= vocabulary)
        self.assertIn("Saved:", output)
        self.assertIn("1: moon star", self.run_editor("1", self.path, "2", "Q"))

    def test_word_deletion_positions_and_duplicates(self):
        for text, word, expected in [
            ("one two three", 1, "two three"),
            ("one two three", 2, "one three"),
            ("one two three", 3, "one two"),
            ("solo", 1, ""),
            ("same same same", 2, "same same"),
            ("  one   two   three  ", 2, "  one   three"),
        ]:
            with self.subTest(text=text, word=word):
                self.path.write_text(text + "\n")
                self.run_editor("1", self.path, "8", "1", word, "S", "Q")
                self.assertEqual(self.path.read_text(), expected + "\n")

    def test_replacement_changes_selected_word_only(self):
        self.path.write_text("cat scatter cat! cat\n")
        self.run_editor("1", self.path, "7", "1", "4", "dog", "S", "Q")
        self.assertEqual(self.path.read_text(), "cat scatter cat! dog\n")

    def test_empty_file_blank_lines_and_line_deletion(self):
        self.run_editor("1", self.path, "Y", "S", "Q")
        self.assertEqual(self.path.read_bytes(), b"")
        self.run_editor("1", self.path, "3", "", "3", "last", "S", "Q")
        self.assertEqual(self.path.read_text(), "\nlast\n")
        self.run_editor("1", self.path, "9", "1", "9", "1", "S", "Q")
        self.assertEqual(self.path.read_bytes(), b"")

    def test_cancel_and_discard_leave_disk_unchanged(self):
        self.path.write_text("original\n")
        output = self.run_editor("1", self.path, "6", "1", "edited",
                                 "Q", "C", "2", "Q", "D")
        self.assertIn("1: edited", output)
        self.assertEqual(self.path.read_text(), "original\n")

    def test_save_when_quitting(self):
        self.path.write_text("old\n")
        self.run_editor("1", self.path, "6", "1", "new", "Q", "S")
        self.assertEqual(self.path.read_text(), "new\n")

    def test_save_before_switching_files(self):
        other = self.root / "other.txt"
        other.write_text("second\n")
        self.run_editor("1", self.path, "Y", "3", "first",
                        "1", "S", other, "Q")
        self.assertEqual(self.path.read_text(), "first\n")

    def test_invalid_input_does_not_change_document(self):
        self.path.write_text("keep me\n")
        output = self.run_editor(
            "2", "1", self.path, "nope", "6", "0", "6", "-1",
            "6", "abc", "6", "1.5", "9", "9999", "7", "1", "8",
            "7", "1", "1", "two words", "5", "51", "S", "Q",
        )
        self.assertIn("Open or create a file first", output)
        self.assertIn("That word does not exist", output)
        self.assertEqual(self.path.read_text(), "keep me\n")

    def test_failed_save_keeps_changes_and_can_cancel_quit(self):
        missing = self.root / "missing" / "file.txt"
        output = self.run_editor("1", missing, "Y", "3", "keep in memory",
                                 "Q", "S", "2", "Q", "D")
        self.assertIn("Cannot create a temporary file", output)
        self.assertIn("1: keep in memory", output)
        self.assertFalse(missing.exists())

    def test_failed_open_keeps_current_document(self):
        self.path.write_text("keep\n")
        huge = self.root / "huge.txt"
        for contents in ["x\n" * 1001, "x" * 1025 + "\n",
                         "x" * 5000 + "\n"]:
            with self.subTest(size=len(contents)):
                huge.write_text(contents)
                output = self.run_editor("1", self.path, "1", huge,
                                         "2", "S", "Q")
                self.assertIn("The current document has been kept", output)
                self.assertIn("1: keep", output)
                self.assertEqual(self.path.read_text(), "keep\n")
                self.assertEqual(huge.read_text(), contents)

    def test_line_width_boundary_and_overflow_edits(self):
        line = "x" * 1024
        self.path.write_text(line + "\n")
        output = self.run_editor("1", self.path, "4", "1", "more",
                                 "6", "1", "z" * 1025,
                                 "3", "z" * 1025, "S", "Q")
        self.assertIn("too long", output)
        self.assertEqual(self.path.read_text(), line + "\n")

    def test_maximum_line_count(self):
        contents = "hello\n" * 1000
        self.path.write_text(contents)
        output = self.run_editor("1", self.path, "3", "5", "S", "Q")
        self.assertIn("already has 1000 lines", output)
        self.assertEqual(self.path.read_text(), contents)

    def test_replacement_overflow_keeps_original_line(self):
        contents = "a " + "b" * 1022 + "\n"
        self.path.write_text(contents)
        output = self.run_editor("1", self.path, "7", "1", "1",
                                 "longer", "S", "Q")
        self.assertIn("Result is too long", output)
        self.assertEqual(self.path.read_text(), contents)

    def test_eof_does_not_loop_or_autosave(self):
        self.path.write_text("original\n")
        output = self.run_editor("1", self.path, "6", "1", "edited")
        self.assertIn("Input ended", output)
        self.assertEqual(self.path.read_text(), "original\n")


if __name__ == "__main__":
    unittest.main(verbosity=2)
