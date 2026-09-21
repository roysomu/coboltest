package com.example.fileeditor;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Interactive text file editor: the Java translation of {@code file_editor.cob}
 * (program {@code file-editor}, one compilation unit of 515 free-format lines).
 * The former {@code WORKING-STORAGE} [file_editor.cob:17-85] becomes the owned
 * state of this class and each of the fifteen {@code PROCEDURE DIVISION}
 * paragraphs [file_editor.cob:88-515] becomes one private method, so that every
 * decision point, message and early exit sits where it did in the original and
 * can be checked against it line by line. Each method names the paragraph and
 * line range it reproduces.
 *
 * <p><b>Equivalence boundary.</b> The behavior reproduced is that of the
 * original compiled with GnuCOBOL 3.2.0 under its default runtime and driven
 * through standard input and output: the transcript, every operator-visible
 * string, every limit, the exit status, the bytes written to disk and the
 * side effects on the filesystem. Deliberate, approved differences are limited
 * to the random word sequence (a fresh {@link Random} replaces
 * {@code FUNCTION RANDOM} seeded from {@code ACCEPT ... FROM TIME}
 * [file_editor.cob:89-90,345-346]), the shape of the temporary file's suffix,
 * flushing after every write, the I/O status set reachable without fault
 * injection, and paths whose bytes the filesystem charset cannot represent
 * (see {@link #toPath(String)}). The banner
 * {@code COBOL Text File Editor} [file_editor.cob:91] is part of the transcript
 * and is therefore kept verbatim.
 *
 * <p><b>Byte semantics.</b> Every byte of input, output and file content is
 * mapped through ISO-8859-1 by {@link Console} and {@link LineSequentialFile},
 * so one {@code char} is one byte and {@link String#length()} is a byte count
 * exactly as the original {@code PIC X} fields counted bytes. The 1,024-,
 * 512- and four-digit limits below are therefore byte limits. The one place
 * this class converts between the two worlds is {@link #toPath(String)}, where
 * an operator's path bytes have to become a {@link Path}.
 *
 * <p><b>Process contract.</b> The exit status is {@code 0} on every path -
 * normal quit, end of input, and after any refused operation or failed save -
 * because {@link #main(String[])} always returns normally and never calls
 * {@code System.exit}. Nothing in the program writes to {@code System.err}:
 * every anticipated failure is reported to the operator on standard output as
 * the original reported it, and a failed operation leaves both the in-memory
 * document and the file on disk untouched.
 *
 * <p>The class is single-threaded by construction: one instance is created by
 * {@link #main(String[])}, it owns the only {@link Console}, and no state is
 * shared with anything else.
 */
public final class FileEditor {

    /**
     * Maximum number of lines a document may hold:
     * {@code 78 max-lines value 1000} [file_editor.cob:18]. Checked on load
     * [file_editor.cob:192] and before appending [file_editor.cob:273,330].
     */
    private static final int MAX_LINES = 1000;

    /**
     * Maximum bytes in one line: {@code 78 max-width value 1024}
     * [file_editor.cob:19]. Checked on load [file_editor.cob:194], on the text
     * just read [file_editor.cob:265] and on every assembled result before it
     * replaces a line [file_editor.cob:313,427].
     */
    private static final int MAX_WIDTH = 1024;

    /**
     * Maximum bytes in a file path: the width of {@code file-path pic x(512)}
     * [file_editor.cob:26], enforced explicitly at the prompt
     * [file_editor.cob:161].
     */
    private static final int MAX_PATH = 512;

    /**
     * Largest number of words option 5 will generate [file_editor.cob:337].
     */
    private static final int MAX_RANDOM_WORDS = 50;

    /**
     * Longest numeric answer accepted, in digits: the upper bound of the
     * combined condition {@code > 0 and <= 4} [file_editor.cob:237]. Four
     * digits cap every accepted number at 9,999, so no arithmetic here can
     * overflow.
     */
    private static final int MAX_NUMBER_DIGITS = 4;

    /**
     * Width of {@code menu-choice pic x(4)} [file_editor.cob:40]. The answer is
     * moved into that fixed-width field before the {@code EVALUATE} compares it
     * [file_editor.cob:102-104], so a longer answer is truncated rather than
     * rejected: {@code 1   xyz} selects option 1, while {@code 12345} and
     * {@code 1xyz5} are invalid choices.
     */
    private static final int MENU_CHOICE_WIDTH = 4;

    /**
     * The twenty words option 5 draws from, in source order: the
     * {@code FILLER pic x(12)} literals redefined as
     * {@code random-word occurs 20 times} [file_editor.cob:63-85]. The
     * twelve-byte padding of the original table is dropped because every use
     * trimmed it [file_editor.cob:347].
     */
    private static final String[] VOCABULARY = {
        "apple", "river", "mountain", "sunshine", "forest",
        "ocean", "garden", "cloud", "journey", "coffee",
        "window", "music", "planet", "silver", "meadow",
        "breeze", "cobalt", "story", "lantern", "dream",
    };

    /**
     * The document: {@code document-line pic x(1024) occurs 1000 times} plus
     * {@code line-count} [file_editor.cob:20-21,24] as one bounded list, whose
     * size is the former line count. Every element is stored with its trailing
     * spaces removed and is at most {@link #MAX_WIDTH} bytes long, which is
     * observably identical to the space-padded fixed-width original because
     * every read of a line went through {@code FUNCTION TRIM ... TRAILING} and
     * every {@code WRITE} removed trailing spaces.
     *
     * <p>Not final: {@link #openDocument()} replaces the reference with the
     * staged list only after the whole file has been read and closed cleanly -
     * the {@code move pending-buffer to document-buffer}
     * [file_editor.cob:214] - and a confirmed creation installs a fresh empty
     * document [file_editor.cob:173-174].
     */
    private List<String> lines = new ArrayList<>();

    /**
     * Path of the open document: {@code file-path pic x(512)}
     * [file_editor.cob:26], held space-trimmed as the original held it
     * [file_editor.cob:165,175,216] so that displaying it reproduces
     * {@code FUNCTION TRIM(file-path)} [file_editor.cob:219,225,506].
     */
    private String filePath = "";

    /** {@code opened-flag} [file_editor.cob:32]: a document is available. */
    private boolean opened;

    /**
     * {@code dirty-flag} [file_editor.cob:33]: the document differs from the
     * file on disk. Cleared only by a completed save [file_editor.cob:505] or a
     * completed load [file_editor.cob:218].
     */
    private boolean dirty;

    /**
     * Source of the words option 5 generates, replacing {@code seed-value},
     * {@code ACCEPT ... FROM TIME} and {@code FUNCTION RANDOM}
     * [file_editor.cob:60-61,89-90,345-346]. The choice stays uniform over the
     * twenty entries of {@link #VOCABULARY}; only the sequence differs, which
     * the original varied per run as well.
     */
    private final Random random = new Random();

    /** The program's only terminal boundary; see {@link Console}. */
    private final Console console = new Console();

    /**
     * Entry point named by the JAR manifest
     * ({@code Main-Class: com.example.fileeditor.FileEditor}).
     *
     * <p>Runs the menu loop and converts the one condition that ended the
     * original program early into a normal return: {@link InputEndedException},
     * thrown by {@link Console#readLine()} when input ends before a terminating
     * line feed, is the {@code ON EXCEPTION} arm of {@code accept answer}
     * [file_editor.cob:134-137], whose two lines are printed here because the
     * exception may be raised from any depth of the paragraph call graph. The
     * transcript is identical to the original's in-place display, since the
     * exception propagates immediately and nothing is printed in between.
     *
     * <p>The status is {@code 0} on every path and nothing reaches standard
     * error. There is deliberately no catch-all for other exceptions: the
     * design confines every anticipated failure to a diagnostic on standard
     * output, so anything else escaping here is a defect that must be visible
     * rather than swallowed.
     *
     * @param args ignored; the original took no arguments and this port adds
     *             none
     */
    public static void main(String[] args) {
        FileEditor editor = new FileEditor();
        try {
            editor.run();
        } catch (InputEndedException ended) {
            editor.console.println(" ");
            editor.console.println("Input ended; unsaved changes were not saved.");
        }
    }

    /**
     * Paragraph {@code main} [file_editor.cob:88-129]: the banner, the menu
     * loop and the dispatch of every option.
     *
     * <p>The seeding statements [file_editor.cob:89-90] have no counterpart
     * here; they are subsumed by the construction of {@link #random}. The
     * loop is the {@code PERFORM UNTIL done-flag = 1} [file_editor.cob:92] with
     * its termination condition as a local, set only by a quit that was not
     * cancelled [file_editor.cob:106-108].
     *
     * <p>The answer is space-trimmed, upper-cased and then truncated to
     * {@link #MENU_CHOICE_WIDTH} bytes, which is the {@code MOVE} into
     * {@code menu-choice pic x(4)} [file_editor.cob:102-103] followed by a
     * space-padded comparison; the trailing spaces of the truncated field are
     * removed so the comparison can be written as a {@code switch}. This is not
     * interchangeable with the whole-string comparisons at the two confirmation
     * prompts [file_editor.cob:146,172] - {@code 1   xyz} selects option 1,
     * whereas {@code S   xyz} is not {@code S} - so the two styles are kept
     * apart.
     */
    private void run() {
        console.println("COBOL Text File Editor");
        boolean done = false;
        while (!done) {
            console.println(" ");
            console.println("1 Open / create file   2 View file");
            console.println("3 Append a line        4 Append words to a line");
            console.println("5 Generate random words (new line)");
            console.println("6 Replace a line       7 Replace a word");
            console.println("8 Delete a word        9 Delete a line");
            console.println("S Save                 Q Quit");
            console.print("Choice: ");
            String answer = asciiUpper(trimSpaces(readAnswer()));
            String choice = rstripSpaces(answer.length() > MENU_CHOICE_WIDTH
                    ? answer.substring(0, MENU_CHOICE_WIDTH)
                    : answer);
            switch (choice) {
                case "1":
                    openDocument();
                    break;
                case "Q":
                    // Quitting is abandoned when the unsaved-changes check
                    // cancels it, including when the save it performed failed
                    // [file_editor.cob:106-108,147-149].
                    if (!checkUnsaved()) {
                        done = true;
                    }
                    break;
                case "2":
                case "3":
                case "4":
                case "5":
                case "6":
                case "7":
                case "8":
                case "9":
                case "S":
                    // The grouped WHENs [file_editor.cob:109-110]: every option
                    // that needs a document shares one gate.
                    if (!opened) {
                        console.println("Open or create a file first (option 1).");
                    } else {
                        // The nested EVALUATE [file_editor.cob:114-123] has no
                        // WHEN OTHER, so this switch needs no default.
                        switch (choice) {
                            case "2":
                                viewDocument();
                                break;
                            case "3":
                                appendLine();
                                break;
                            case "4":
                                appendWords();
                                break;
                            case "5":
                                generateWords();
                                break;
                            case "6":
                                replaceLine();
                                break;
                            case "7":
                            case "8":
                                editWord(choice);
                                break;
                            case "9":
                                deleteLine();
                                break;
                            case "S":
                                // No dirty test here, by design: saving a clean
                                // document rewrites it byte for byte
                                // [file_editor.cob:122].
                                saveDocument();
                                break;
                            default:
                                break;
                        }
                    }
                    break;
                default:
                    console.println("Invalid choice.");
                    break;
            }
        }
        console.println("Goodbye.");
    }

    /**
     * Paragraph {@code read-answer} [file_editor.cob:131-138]: one operator
     * response.
     *
     * <p>{@code move spaces to answer} [file_editor.cob:132] and the
     * {@code answer pic x(4096)} truncation are subsumed by
     * {@link Console#readLine()}, which also raises the
     * {@code ON EXCEPTION ... STOP RUN} arm [file_editor.cob:134-137] as
     * {@link InputEndedException}.
     *
     * @return the response with its terminating line feed removed, at most
     *         4,096 bytes long, never {@code null}
     */
    private String readAnswer() {
        return console.readLine();
    }

    /**
     * Paragraph {@code check-unsaved} [file_editor.cob:140-153]: the
     * save / discard / cancel question asked before a quit or a file switch
     * abandons unsaved work.
     *
     * <p>The answer is compared as a whole trimmed, upper-cased string with no
     * fixed-width truncation, because no fixed-width field is involved
     * [file_editor.cob:146]; {@code S   xyz} therefore cancels, as it does in
     * the original. Anything that is neither {@code S} nor {@code D} cancels
     * [file_editor.cob:151].
     *
     * @return {@code true} when the caller must abandon what it was about to do
     *         - the operator cancelled, or chose to save and the save failed,
     *         leaving the document dirty [file_editor.cob:147-149]; otherwise
     *         {@code false}, which is also the answer when there is nothing
     *         unsaved [file_editor.cob:142]
     */
    private boolean checkUnsaved() {
        if (!dirty) {
            return false;
        }
        console.print("Unsaved changes: S = save, D = discard, C = cancel: ");
        String answer = asciiUpper(trimSpaces(readAnswer()));
        if (answer.equals("S")) {
            saveDocument();
            // A save that failed leaves the document dirty, which cancels the
            // quit or the file switch rather than losing the changes.
            return dirty;
        }
        if (answer.equals("D")) {
            return false;
        }
        return true;
    }

    /**
     * Paragraph {@code open-document} [file_editor.cob:155-222]: option 1,
     * which opens an existing file or offers to start a new document.
     *
     * <p>Three orderings are reproduced exactly because operator input is
     * consumed by them. The unsaved-changes check runs <em>before</em> the path
     * prompt [file_editor.cob:156-157], so a cancelled switch consumes only the
     * one answer. The path is validated before anything is opened
     * [file_editor.cob:160-164]. The file is read into a staging list and the
     * document is replaced only after the read loop and the close have both
     * succeeded [file_editor.cob:185-222], so a refused file leaves the current
     * document and the file on disk untouched - the validate-before-mutate
     * guarantee the original made with its {@code pending-buffer}.
     *
     * <p>The close is unconditional and its own status is checked
     * [file_editor.cob:208-212], which is why it is written out rather than
     * delegated to try-with-resources: a read diagnostic and a close diagnostic
     * can both appear, in that order.
     */
    private void openDocument() {
        if (checkUnsaved()) {
            return;
        }
        console.print("Text file path (up to 512 characters): ");
        String path = trimSpaces(readAnswer());
        // An all-space answer trims to the empty string, which is the
        // `function trim(answer) = spaces` test [file_editor.cob:160]; the
        // length test counts bytes [file_editor.cob:161].
        if (path.isEmpty() || path.length() > MAX_PATH) {
            console.println("Invalid file path.");
            return;
        }
        Path target = toPath(path);
        if (target == null) {
            console.println("Invalid file path.");
            return;
        }
        LineSequentialFile.Input input;
        try {
            input = LineSequentialFile.Input.open(target);
        } catch (LineSequentialFile.FileStatusException status) {
            // `evaluate io-status` after OPEN INPUT [file_editor.cob:167-184].
            if ("35".equals(status.status)) {
                console.print("File does not exist. Create it? (Y/N): ");
                // Whole-string comparison, as in the original: `Y   x` is not
                // `Y` [file_editor.cob:172].
                if (asciiUpper(trimSpaces(readAnswer())).equals("Y")) {
                    lines = new ArrayList<>();
                    filePath = path;
                    opened = true;
                    dirty = true;
                    console.println("New document ready. Use S to save it.");
                }
                // Declining prints nothing and changes nothing
                // [file_editor.cob:178-179].
                return;
            }
            console.println("Cannot open file. File status: " + status.status);
            return;
        }
        List<String> pending = new ArrayList<>();
        boolean eof = false;
        boolean failed = false;
        // `perform until eof-flag = 1 or failed-flag = 1`
        // [file_editor.cob:187-207].
        while (!eof && !failed) {
            try {
                String record = input.readRecord();
                if (record == null) {
                    // io-status 10 [file_editor.cob:190].
                    eof = true;
                } else {
                    String stored = rstripSpaces(record);
                    // One diagnostic covers both limits, as the original's two
                    // concatenated literals did [file_editor.cob:192-197].
                    if (pending.size() >= MAX_LINES || stored.length() > MAX_WIDTH) {
                        console.println(
                                "File exceeds 1000 lines or 1024 characters per line.");
                        failed = true;
                    } else {
                        pending.add(stored);
                    }
                }
            } catch (LineSequentialFile.FileStatusException status) {
                console.println(
                        "Cannot read file safely. File status: " + status.status);
                failed = true;
            } catch (IOException e) {
                // Any other read failure is libcob's catch-all permanent error.
                console.println("Cannot read file safely. File status: 30");
                failed = true;
            }
        }
        try {
            input.close();
        } catch (IOException e) {
            console.println("Cannot close file. File status: 30");
            failed = true;
        }
        if (!failed) {
            lines = pending;
            filePath = path;
            opened = true;
            dirty = false;
            console.println("Opened: " + filePath);
        } else {
            console.println("The current document has been kept.");
        }
    }

    /**
     * Paragraph {@code view-document} [file_editor.cob:224-232]: option 2, the
     * numbered listing.
     *
     * <p>{@link #filePath} is already space-trimmed, so printing it directly
     * reproduces {@code FUNCTION TRIM(file-path)} [file_editor.cob:225]. Each
     * line is printed with its trailing spaces removed and its leading spaces
     * intact - {@code FUNCTION TRIM(... TRAILING)} [file_editor.cob:231] - so
     * an empty line renders as its number, a colon and one space. The number
     * comes from {@code display-number pic ZZZ9} then {@code FUNCTION TRIM}
     * [file_editor.cob:62,229-230], which for 1 to 1,000 is exactly the
     * decimal form.
     */
    private void viewDocument() {
        console.println("File: " + filePath);
        if (dirty) {
            console.println("(unsaved changes)");
        }
        if (lines.isEmpty()) {
            console.println("(empty file)");
        }
        for (int index = 1; index <= lines.size(); index++) {
            console.println(index + ": " + rstripSpaces(lines.get(index - 1)));
        }
    }

    /**
     * Paragraph {@code read-number} [file_editor.cob:234-243]: one positive
     * whole number.
     *
     * <p>Accepts one to four digits with a value above zero and nothing else,
     * which is the combined condition on the trimmed length, the
     * {@code IS NUMERIC} class test and the {@code > 0} test
     * [file_editor.cob:237-240]; {@code 0001} is accepted as 1 while
     * {@code 0}, {@code -1}, {@code 1.5}, {@code abc}, {@code 12345} and a
     * blank answer are refused.
     *
     * @return the number, or {@code -1} for an answer that was refused - the
     *         {@code valid-flag = 0} outcome, whose diagnostic has already been
     *         printed [file_editor.cob:243]
     */
    private int readNumber() {
        String answer = trimSpaces(readAnswer());
        if (isPositiveNumber(answer)) {
            return Integer.parseInt(answer);
        }
        console.println("Enter a positive whole number.");
        return -1;
    }

    /**
     * Paragraph {@code choose-line} [file_editor.cob:245-260]: the line number
     * every line-oriented option asks for.
     *
     * <p>The empty-document check comes <em>before</em> the prompt and consumes
     * no input [file_editor.cob:247-250], so on an empty document the next
     * answer is read as the next menu choice.
     *
     * @return the one-based line number, or {@code -1} when there is no line to
     *         choose, the answer was not a positive number, or the number is
     *         past the end of the document [file_editor.cob:254-256]
     */
    private int chooseLine() {
        if (lines.isEmpty()) {
            console.println("The file has no lines.");
            return -1;
        }
        console.print("Line number: ");
        int value = readNumber();
        if (value < 0) {
            return -1;
        }
        if (value > lines.size()) {
            console.println("That line does not exist.");
            return -1;
        }
        return value;
    }

    /**
     * Paragraph {@code read-text} [file_editor.cob:262-270]: one line of text,
     * for which a blank answer is legitimate.
     *
     * <p>The width check uses the trailing-trimmed length
     * [file_editor.cob:265], so leading spaces count toward the limit and are
     * preserved. An accepted answer is then truncated to the width of
     * {@code new-text pic x(1024)} [file_editor.cob:269], which can only drop
     * trailing spaces because the check has already passed. Callers store the
     * result with its trailing spaces removed, matching the fixed-width
     * original.
     *
     * @return the text, or {@code null} when it was too long and the diagnostic
     *         has been printed - the {@code valid-flag = 0} outcome
     */
    private String readText() {
        String answer = readAnswer();
        if (rstripSpaces(answer).length() > MAX_WIDTH) {
            console.println("Text is too long (maximum 1024 characters).");
            return null;
        }
        return answer.length() > MAX_WIDTH ? answer.substring(0, MAX_WIDTH) : answer;
    }

    /**
     * Paragraph {@code append-line} [file_editor.cob:272-284]: option 3.
     *
     * <p>The capacity check comes <em>before</em> the prompt and consumes no
     * input [file_editor.cob:273-276]; on a full document the next answer is
     * therefore read as the next menu choice, exactly as in the original.
     */
    private void appendLine() {
        if (lines.size() >= MAX_LINES) {
            console.println("The file already has 1000 lines.");
            return;
        }
        console.print("New line (blank is allowed): ");
        String text = readText();
        if (text == null) {
            return;
        }
        lines.add(rstripSpaces(text));
        dirty = true;
        console.println("Line appended.");
    }

    /**
     * Paragraph {@code replace-line} [file_editor.cob:286-295]: option 6.
     */
    private void replaceLine() {
        int lineNumber = chooseLine();
        if (lineNumber < 0) {
            return;
        }
        console.print("Replacement line (blank is allowed): ");
        String text = readText();
        if (text == null) {
            return;
        }
        lines.set(lineNumber - 1, rstripSpaces(text));
        dirty = true;
        console.println("Line replaced.");
    }

    /**
     * Paragraph {@code append-words} [file_editor.cob:297-327]: option 4, which
     * appends text to the end of a line, separated by one space.
     *
     * <p>The words are trimmed on both sides [file_editor.cob:303] and a blank
     * answer is refused [file_editor.cob:304-306]. The resulting width is
     * computed and checked before the line is touched
     * [file_editor.cob:308-316], so a refused result leaves the line as it was;
     * the separator is counted only when there is something to separate from
     * [file_editor.cob:312]. The fragments are then assembled in the original's
     * order [file_editor.cob:317-324].
     */
    private void appendWords() {
        int lineNumber = chooseLine();
        if (lineNumber < 0) {
            return;
        }
        console.print("Words to append: ");
        String text = readText();
        if (text == null) {
            return;
        }
        String words = trimSpaces(text);
        if (words.isEmpty()) {
            console.println("No words supplied.");
            return;
        }
        String line = lines.get(lineNumber - 1);
        int oldLength = rstripSpaces(line).length();
        int newLength = words.length();
        int resultLength = oldLength + newLength;
        if (oldLength > 0) {
            resultLength++;
        }
        if (resultLength > MAX_WIDTH) {
            console.println("Result is too long (maximum 1024 characters).");
            return;
        }
        StringBuilder result = new StringBuilder();
        if (oldLength > 0) {
            result.append(line, 0, oldLength).append(' ');
        }
        result.append(words, 0, newLength);
        lines.set(lineNumber - 1, rstripSpaces(result.toString()));
        dirty = true;
        console.println("Words appended.");
    }

    /**
     * Paragraph {@code generate-words} [file_editor.cob:329-353]: option 5,
     * which appends a new line of randomly chosen vocabulary words.
     *
     * <p>The capacity check comes <em>before</em> the prompt and consumes no
     * input [file_editor.cob:330-333]. Each word is appended followed by one
     * space [file_editor.cob:347], and the trailing space of the last word
     * disappears when the line is stored, as it did in the fixed-width
     * {@code result-text} field. There is no width check because the original
     * has none and needs none: fifty words of at most eight bytes plus their
     * separators cannot reach {@link #MAX_WIDTH}.
     */
    private void generateWords() {
        if (lines.size() >= MAX_LINES) {
            console.println("The file already has 1000 lines.");
            return;
        }
        console.print("How many random words? (1-50): ");
        int count = readNumber();
        if (count < 0) {
            return;
        }
        if (count > MAX_RANDOM_WORDS) {
            console.println("Choose between 1 and 50 words.");
            return;
        }
        StringBuilder result = new StringBuilder();
        for (int generated = 0; generated < count; generated++) {
            result.append(VOCABULARY[random.nextInt(VOCABULARY.length)]).append(' ');
        }
        String text = result.toString();
        lines.add(rstripSpaces(text));
        dirty = true;
        console.println("Generated: " + trimSpaces(text));
    }

    /**
     * Paragraph {@code edit-word} [file_editor.cob:355-448]: options 7 and 8,
     * which replace or delete the n-th space-separated word of a line.
     *
     * <p>A literal transliteration, including the one-based positions the
     * original scanned with: the variables hold one-based byte positions and
     * are converted to zero-based indices only where a character is read or a
     * fragment is copied. The word scan [file_editor.cob:367-383] walks the
     * line once, counting words and remembering where the requested one starts
     * and where it ends - {@code end-pos} is one past the last byte of the
     * word, which is why it can be one past the end of the line.
     *
     * <p>Deleting also absorbs the spaces adjoining the word so the remaining
     * words join cleanly [file_editor.cob:406-424]: the spaces that follow it
     * when the word is not the last one, and otherwise the spaces that precede
     * it. The resulting width is checked before anything is written
     * [file_editor.cob:425-430].
     *
     * @param op the menu choice as it was truncated to
     *           {@link #MENU_CHOICE_WIDTH} bytes and stripped, which is the
     *           {@code move menu-choice to operation-choice}
     *           [file_editor.cob:356]: {@code "7"} replaces the word and
     *           anything else - {@code "8"} - deletes it
     *           [file_editor.cob:390]
     */
    private void editWord(String op) {
        int lineNumber = chooseLine();
        if (lineNumber < 0) {
            return;
        }
        String line = lines.get(lineNumber - 1);
        // The echo keeps leading spaces: TRAILING trim only
        // [file_editor.cob:359].
        console.println(rstripSpaces(line));
        console.print("Word number (words are separated by spaces): ");
        int wordNumber = readNumber();
        if (wordNumber < 0) {
            return;
        }
        int oldLength = rstripSpaces(line).length();
        int scanPos = 1;
        int wordCount = 0;
        int startPos = 0;
        int endPos = 0;
        while (scanPos <= oldLength && wordCount != wordNumber) {
            if (line.charAt(scanPos - 1) == ' ') {
                scanPos++;
            } else {
                wordCount++;
                startPos = scanPos;
                while (scanPos <= oldLength) {
                    if (line.charAt(scanPos - 1) == ' ') {
                        break;
                    }
                    scanPos++;
                }
                endPos = scanPos;
            }
        }
        if (wordCount != wordNumber) {
            console.println("That word does not exist.");
            return;
        }
        // Cleared before the option test, so a deletion runs with an empty
        // replacement of length zero [file_editor.cob:388-389].
        String replacement = "";
        int newLength = 0;
        if (op.equals("7")) {
            console.print("Replacement word: ");
            String text = readText();
            if (text == null) {
                return;
            }
            replacement = trimSpaces(text);
            newLength = replacement.length();
            if (newLength == 0) {
                console.println("Use option 8 to delete a word.");
                return;
            }
            for (int index = 1; index <= newLength; index++) {
                char candidate = replacement.charAt(index - 1);
                // `= space or x'09'` [file_editor.cob:401]: exactly those two.
                if (candidate == ' ' || candidate == '\t') {
                    console.println("Enter one word, without spaces or tabs.");
                    return;
                }
            }
        } else {
            // Delete adjoining spaces so the remaining words join cleanly.
            if (endPos <= oldLength) {
                while (endPos <= oldLength) {
                    if (line.charAt(endPos - 1) != ' ') {
                        break;
                    }
                    endPos++;
                }
            } else {
                // Pre-tested, so the first word of a line absorbs nothing
                // [file_editor.cob:416].
                while (startPos > 1) {
                    int index = startPos - 1;
                    if (line.charAt(index - 1) != ' ') {
                        break;
                    }
                    startPos--;
                }
            }
        }
        // Zero when the word ran to the end of the line, which is why the
        // assembly below guards each fragment [file_editor.cob:425-426].
        int tailLength = oldLength - endPos + 1;
        int resultLength = startPos - 1 + newLength + tailLength;
        if (resultLength > MAX_WIDTH) {
            console.println("Result is too long (maximum 1024 characters).");
            return;
        }
        StringBuilder result = new StringBuilder();
        if (startPos > 1) {
            result.append(line, 0, startPos - 1);
        }
        if (newLength > 0) {
            result.append(replacement, 0, newLength);
        }
        if (tailLength > 0) {
            result.append(line, endPos - 1, endPos - 1 + tailLength);
        }
        lines.set(lineNumber - 1, rstripSpaces(result.toString()));
        dirty = true;
        console.println("Word updated.");
    }

    /**
     * Paragraph {@code delete-line} [file_editor.cob:450-459]: option 9.
     *
     * <p>Removing the element produces exactly the sequence the original's
     * shift-up loop, cleared last entry and decremented count produced
     * [file_editor.cob:453-457].
     */
    private void deleteLine() {
        int lineNumber = chooseLine();
        if (lineNumber < 0) {
            return;
        }
        lines.remove(lineNumber - 1);
        dirty = true;
        console.println("Line deleted.");
    }

    /**
     * Paragraph {@code save-document} [file_editor.cob:461-515]: option S, and
     * the save the unsaved-changes check performs on the operator's behalf.
     *
     * <p>The sequence is the original's, and it is the sequence that makes the
     * save atomic and non-destructive: a unique private sibling temporary file
     * is created, every line is written to it, it is closed, and only then is
     * it renamed over the destination [file_editor.cob:465-510]. Each of the
     * four steps has its own diagnostic, so a failure names the step it
     * happened at. {@link #dirty} is cleared only after the rename has
     * succeeded [file_editor.cob:504-505], and every failure path removes the
     * temporary file [file_editor.cob:512-515], which is why no
     * {@code *.tmp.*} sibling ever survives a run. The destination is never
     * opened, truncated or written directly, so a failed save leaves both the
     * document in memory and the file on disk exactly as they were.
     *
     * <p>The C-string plumbing around the original's four {@code CALL STATIC}
     * sites [file_editor.cob:464-466,474-477,500-501] has no counterpart:
     * {@link Path} values carry the two names, and the descriptor
     * {@code mkstemp} returned is never held open here.
     */
    private void saveDocument() {
        Path target = toPath(filePath);
        if (target == null) {
            // Unreachable in practice - the same string produced a usable path
            // when the document was opened or created - but reported like a
            // failed temporary file so that nothing escapes and nothing on disk
            // is touched.
            console.println(
                    "Cannot create a temporary file. Check the path and directory permissions.");
            return;
        }
        Path temp;
        try {
            temp = LineSequentialFile.createTemp(target);
        } catch (IOException e) {
            // Nothing was created, so there is nothing to clean up
            // [file_editor.cob:469-473].
            console.println(
                    "Cannot create a temporary file. Check the path and directory permissions.");
            return;
        }
        boolean failed = false;
        LineSequentialFile.Output output = null;
        try {
            output = LineSequentialFile.Output.open(temp);
        } catch (IOException e) {
            console.println("Cannot open temporary file. File status: 30");
            failed = true;
        }
        if (output != null) {
            // `perform varying idx ... until idx > line-count or
            // failed-flag = 1` [file_editor.cob:484-492]: the first refused
            // record stops the loop.
            for (int index = 0; index < lines.size() && !failed; index++) {
                try {
                    output.writeRecord(lines.get(index));
                } catch (LineSequentialFile.FileStatusException status) {
                    console.println("Cannot write file. File status: " + status.status);
                    failed = true;
                } catch (IOException e) {
                    console.println("Cannot write file. File status: 30");
                    failed = true;
                }
            }
            // Closed unconditionally and its status checked, as the original
            // did [file_editor.cob:493-497].
            try {
                output.close();
            } catch (IOException e) {
                console.println("Cannot close saved file. File status: 30");
                failed = true;
            }
        }
        if (!failed) {
            try {
                LineSequentialFile.replace(temp, target);
                dirty = false;
                console.println("Saved: " + filePath);
            } catch (IOException e) {
                console.println("Cannot replace destination. Changes remain unsaved.");
                failed = true;
            }
        }
        if (failed) {
            LineSequentialFile.deleteQuietly(temp);
        }
    }

    /**
     * Removes leading and trailing spaces, reproducing
     * {@code FUNCTION TRIM(x)} at its twenty call sites
     * [file_editor.cob:102,146,160,161,165,172,219,225,303,310,347,353,394,395,465,500,506].
     *
     * <p>Only the byte 0x20 is removed, because that is all GnuCOBOL's
     * {@code TRIM} removes: a tab or a carriage return survives, which is what
     * makes the menu answer {@code 1} followed by a carriage return an invalid
     * choice. {@link String#trim()} would strip both, and
     * {@link String#strip()} is Unicode-whitespace aware, so neither is used.
     * An all-space argument yields the empty string, matching the zero-length
     * result GnuCOBOL produces for an all-space field.
     *
     * @param s the value to trim
     * @return {@code s} without leading or trailing spaces, possibly empty
     */
    private static String trimSpaces(String s) {
        int begin = 0;
        int end = s.length();
        while (begin < end && s.charAt(begin) == ' ') {
            begin++;
        }
        while (end > begin && s.charAt(end - 1) == ' ') {
            end--;
        }
        return begin == 0 && end == s.length() ? s : s.substring(begin, end);
    }

    /**
     * Removes trailing spaces only, reproducing
     * {@code FUNCTION TRIM(x TRAILING)} at its six call sites
     * [file_editor.cob:194,231,265,309,359,366] and the trailing-space removal
     * the fixed-width {@code PIC X} receiving fields performed implicitly.
     * Leading spaces are preserved, which is why an indented line stays
     * indented when it is listed, echoed or saved.
     *
     * @param s the value to strip
     * @return {@code s} without trailing spaces, possibly empty
     */
    private static String rstripSpaces(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == ' ') {
            end--;
        }
        return end == s.length() ? s : s.substring(0, end);
    }

    /**
     * Upper-cases the twenty-six ASCII letters and nothing else, reproducing
     * {@code FUNCTION UPPER-CASE} [file_editor.cob:102,146,172].
     *
     * <p>{@link String#toUpperCase()} is deliberately not used: it is
     * locale-dependent, and a Turkish default locale would map {@code i} to a
     * dotted capital that matches no option letter. Restricting the mapping to
     * ASCII makes the outcome identical for every input, because only ASCII
     * option letters can match anything.
     *
     * @param s the value to upper-case
     * @return {@code s} with {@code a}-{@code z} mapped to {@code A}-{@code Z}
     */
    private static String asciiUpper(String s) {
        char[] characters = null;
        for (int index = 0; index < s.length(); index++) {
            char current = s.charAt(index);
            if (current >= 'a' && current <= 'z') {
                if (characters == null) {
                    characters = s.toCharArray();
                }
                characters[index] = (char) (current - 'a' + 'A');
            }
        }
        return characters == null ? s : new String(characters);
    }

    /**
     * Reports whether an answer is the kind of number {@code read-number}
     * accepts: one to {@link #MAX_NUMBER_DIGITS} digits with a value above zero
     * [file_editor.cob:237-240].
     *
     * <p>The original's {@code IS NUMERIC} class test on an alphanumeric item
     * accepts digits and nothing else - no sign, no decimal point, no spaces -
     * so {@code 0001} is accepted as 1 while {@code 0}, {@code -1},
     * {@code 1.5}, {@code abc}, {@code 12345} and a blank answer are refused.
     *
     * @param t the answer, already trimmed of spaces
     * @return {@code true} when {@code t} may be parsed as the operator's
     *         number
     */
    private static boolean isPositiveNumber(String t) {
        if (t.isEmpty() || t.length() > MAX_NUMBER_DIGITS) {
            return false;
        }
        for (int index = 0; index < t.length(); index++) {
            char digit = t.charAt(index);
            if (digit < '0' || digit > '9') {
                return false;
            }
        }
        return Integer.parseInt(t) > 0;
    }

    /**
     * Converts an operator-supplied path from the bytes that were typed into a
     * {@link Path}, the one place in this class where bytes and characters part
     * company.
     *
     * <p>The original handed the bytes to the C library unchanged. A Java
     * {@link Path} cannot carry arbitrary bytes, so the bytes are decoded with
     * the charset the JVM itself uses for filenames
     * ({@code sun.jnu.encoding}, falling back to {@code native.encoding} and
     * then to the default charset). Decoding reports errors rather than
     * substituting replacement characters, because a substitution would
     * silently name a different file; a path the charset cannot represent, and
     * a path containing a NUL byte, are therefore refused before anything is
     * opened, which is non-destructive and leaves the document untouched. Under
     * the UTF-8 locale this program is documented for, every valid UTF-8 path
     * reaches the same file the original reached.
     *
     * <p>This is also what keeps {@link InvalidPathException} from escaping to
     * standard error, which would break the program's silence there.
     *
     * @param s the path as the operator typed it, one char per byte, already
     *          trimmed of spaces
     * @return the path, or {@code null} when it cannot be represented - the
     *         caller reports {@code Invalid file path.} and touches nothing
     */
    private static Path toPath(String s) {
        try {
            byte[] bytes = s.getBytes(StandardCharsets.ISO_8859_1);
            String name = System.getProperty("sun.jnu.encoding");
            if (name == null) {
                name = System.getProperty("native.encoding");
            }
            Charset charset;
            try {
                charset = name == null ? Charset.defaultCharset() : Charset.forName(name);
            } catch (IllegalCharsetNameException | UnsupportedCharsetException e) {
                charset = Charset.defaultCharset();
            }
            CharsetDecoder decoder = charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            return Paths.get(decoder.decode(ByteBuffer.wrap(bytes)).toString());
        } catch (CharacterCodingException | InvalidPathException e) {
            return null;
        }
    }
}
