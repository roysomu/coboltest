# Text File Editor (Java port of the COBOL editor)

An interactive terminal application. It is a Java translation of the original
free-format COBOL editor and preserves that program's behavior, including the
`COBOL Text File Editor` banner it still prints when it starts. Open an
existing text file or create one, generate random words, append text, replace
words or lines, delete words or lines, and save your changes.

## Build and run

Requires **JDK 17 at patch level 17.0.20.1 or later**, **Apache Maven 3.8.x or
later** (tested with Maven 3.9.16), and macOS or Linux. `17.0.20.1+1` is the
current Java 17 security baseline, published in the August 2026 critical
security patch update; earlier Java 17 builds, `17.0.20` among them, are
missing its fixes. JDK 21 and 25 also build the project, each at its own
current patch level, because the compiler targets release 17. Python 3.7 or
later is needed only for the automated tests.

Install the toolchain on macOS. Homebrew's Maven depends on an unversioned
`openjdk` formula, so `JAVA_HOME` has to point at JDK 17; otherwise Maven runs
on whichever JDK Homebrew installed alongside it:

```sh
brew install --cask temurin@17 && brew install maven
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
```

Put that export in your shell profile to make it permanent.

On Debian/Ubuntu, installing packages needs root, so run these in a root shell
or with `sudo` as shown:

```sh
sudo apt-get update
sudo apt-get install openjdk-17-jdk-headless maven
```

Check the versions first; expect `17.0.20.1` or later from the JDK and `3.8.x`
or later from Maven:

```sh
java -version
mvn -version
```

`mvn -version` also prints a `Java version:` line for the JDK Maven itself
runs on, which need not be the first `java` on your path. It has to name the
JDK you mean to build with, at the same `17.0.20.1` or later baseline, because
Maven resolves the build plugins over TLS on that JDK. If it names another
JDK, export `JAVA_HOME` as shown above and check again.

An older `17.0.x` build still compiles and runs the project, but it lacks the
August 2026 security fixes, so install a `17.0.20.1` or later build first:
Homebrew's `temurin@17` cask and the Debian/Ubuntu `openjdk-17-jdk-headless`
package both carry it once the package index is current.

From this directory:

```sh
make
make run
```

Or build and run without `make`:

```sh
mvn -q -B -DskipTests package
java -jar target/file-editor.jar
```

`make` packages `target/file-editor.jar`, `make run` starts the editor,
`make test` runs the tests, and `make clean` removes the build output.
`mvn package -DskipTests=false` also builds the artifact, but it runs no
acceptance tests: there is no Java test tier, so Surefire reports
`No tests to run.`

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
- Saved files have private permissions (`0600`) from exclusive temporary-file
  creation. Saving replaces the directory entry; file metadata is not
  preserved, and a symbolic link path is replaced rather than updating its
  target. Use regular text files. Simultaneous edits by other programs are not
  detected.
- Supports up to **1,000 lines**, **1,024 bytes per line**, and **512 bytes in a
  path**. Oversized documents are rejected; a failed open keeps the current
  document. Each interactive response reads at most **8,191 bytes** of input
  and keeps at most the first **4,096 bytes** of them, so a 4,097-byte answer
  is one response truncated to 4,096 bytes. A line longer than 8,191 bytes
  arrives as several successive responses: the bytes past that limit, the line
  feed included, stay in the input and become the next response.
- Intended for ordinary plain text, especially ASCII. Operations count bytes
  rather than Unicode characters: text is handled as ISO-8859-1, so one
  character is one byte. Line feeds separate lines, and a carriage return
  immediately before a line feed is removed when a file is read. Trailing
  spaces are removed when a file is saved, and each saved line ends with a
  newline. A line containing any other control character (bytes `0x00`–`0x1F`)
  except backspace, tab, form feed, shift-in, or escape is refused on open with
  `File status: 09`; a document containing any control character, tab included,
  cannot be saved and reports `File status: 71`. A line longer than 1,025
  bytes, or a file ending in a bare carriage return, is refused with
  `File status: 06`. These match the GnuCOBOL 3.2 line-sequential rules the
  original ran under. The path you type is used literally: there is no
  environment-variable substitution, and the path must be representable in the
  terminal's locale charset, where UTF-8 is expected. Binary files and exact
  whitespace preservation are outside the editor's scope.
- End of terminal input exits without saving pending changes. Use `S` or the
  save prompt before ending a session.

## Tests

```sh
make test
```

`make test` packages the JAR if it is missing or out of date, then runs the
suite. After packaging yourself, run the harness directly:

```sh
mvn -q -B -DskipTests package && python3 tests/test_editor.py
```

`mvn package` itself runs no acceptance tests; the suite is this separate gate.
It requires a JVM that prints nothing to standard error, so unset
`JAVA_TOOL_OPTIONS` before running it: the JVM would otherwise print a
`Picked up JAVA_TOOL_OPTIONS` line on standard error, and the harness asserts
that standard error is empty.

The tests run the packaged application in temporary directories and check
saved file contents, random-word counts, edits and deletions, empty files,
unsaved-change handling, failed saves, invalid input, and capacity limits.

The original COBOL implementation remains available in git history:

```sh
git show fe90b36:file_editor.cob
```
