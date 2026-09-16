# COBOL Text File Editor

An interactive terminal application written in free-format COBOL. Open an
existing text file or create one, generate random words, append text, replace
words or lines, delete words or lines, and save your changes.

## Build and run

Requires **GnuCOBOL 3.2 or later**, `make`, and macOS or Linux. Python 3 is
needed only for the automated tests. The save routine uses the POSIX C library
functions `mkstemp`, `close`, `rename`, and `unlink`.

Install the compiler on macOS:

```sh
brew install gnucobol
```

On Debian/Ubuntu, install `gnucobol` and `make` through your package manager.
Check `cobc -V` to confirm the compiler version.

From this directory:

```sh
make
./file_editor
```

Or compile directly:

```sh
cobc -x -free -Wall -debug -o file_editor file_editor.cob
./file_editor
```

## Menu

| Option | Action |
| --- | --- |
| `1` | Open a file; offer to create it if it does not exist |
| `2` | Display the current document with line numbers |
| `3` | Append a new line of your own text; blank lines are allowed |
| `4` | Append words to a selected line, separated by a space |
| `5` | Generate 1–50 random words and append them as a new line |
| `6` | Replace an entire line |
| `7` | Replace a word by its position within a line |
| `8` | Delete a word by its position within a line |
| `9` | Delete a line |
| `S` | Save to the opened file path |
| `Q` | Quit |

Options are case-insensitive. Line and word numbers start at **1**. Paths may
contain spaces; type them without quotes. Word operations count space-separated
tokens, including attached punctuation: `hello, world` contains two words.
Deleting a word also removes adjacent separator spaces.

For example, choose `1`, enter `example.txt`, and confirm `Y`. Choose `5` and
enter `10` to generate ten random words. Choose `2` to view them, `7` to replace
a selected word, and `S` to save. Choose `Q` to exit.

## File behavior and limits

- Edits stay in memory until saved. Opening another file or quitting with
  unsaved changes offers **save**, **discard**, or **cancel**.
- New files are created on disk at save time. The parent directory must exist.
- Save writes a temporary file in the same directory and renames it over the
  destination after successful writing and closing. If saving fails, the
  document stays in memory and the previous destination is preserved.
- Saved files have private permissions from `mkstemp` (typically `0600`).
  Saving replaces the directory entry; file metadata is not preserved, and a
  symbolic link path is replaced rather than updating its target. Use regular
  text files. Simultaneous edits by other programs are not detected.
- Supports up to **1,000 lines**, **1,024 bytes per line**, and **512 bytes in a
  path**. Oversized documents are rejected; a failed open keeps the current
  document. Interactive responses are buffered up to 4,096 bytes.
- Intended for ordinary plain text, especially ASCII. COBOL operations count
  bytes rather than Unicode characters. Line-sequential I/O removes trailing
  spaces, normalizes line endings, and may expand tabs. Each saved line ends
  with a newline. Binary files and exact whitespace preservation are outside
  the editor's scope.
- End of terminal input exits without saving pending changes. Use `S` or the
  save prompt before ending a session.

## Tests

```sh
make test
```

The tests run the compiled application in temporary directories and check
saved file contents, random-word counts, edits and deletions, empty files,
unsaved-change handling, failed saves, invalid input, and capacity limits.

See the [GnuCOBOL manual](https://gnucobol.sourceforge.io/doc/gnucobol.html)
for compiler options and line-sequential file handling.
