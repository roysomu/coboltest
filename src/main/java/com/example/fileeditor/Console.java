package com.example.fileeditor;

import java.io.BufferedInputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * Terminal boundary for the Java port of {@code file_editor.cob}: the only
 * class of the program that touches standard input or standard output. It owns
 * the two facts a JVM would otherwise get wrong when it stands in for the
 * GnuCOBOL runtime - the charset and the line-reading rule - so that neither
 * has to be restated at the eleven {@code WITH NO ADVANCING} prompt sites
 * [file_editor.cob:100,143,158,169,251,277,289,300,334,360,391] or at the
 * remaining newline-terminated {@code DISPLAY} statements.
 *
 * <p>Reproduces:</p>
 * <ul>
 *   <li>paragraph {@code read-answer} [file_editor.cob:131-138] -
 *       {@code move spaces to answer / accept answer / on exception ... stop
 *       run} - as {@link #readLine()};</li>
 *   <li>the {@code answer pic x(4096)} response field [file_editor.cob:39] -
 *       as the {@link #ANSWER_CAPACITY} truncation applied by
 *       {@link #readLine()};</li>
 *   <li>the {@code DISPLAY} and {@code DISPLAY ... WITH NO ADVANCING}
 *       conventions - as {@link #println(String)} and {@link #print(String)}.</li>
 * </ul>
 *
 * <p><b>Charset.</b> Every byte read and every byte written is mapped through
 * ISO-8859-1, so one {@code char} is one byte and {@code String.length()} is a
 * byte count exactly as the original {@code PIC X} fields counted bytes. Output
 * goes through {@link FileDescriptor#out} rather than {@link System#out}
 * because {@code System.out}'s encoder uses the platform stdout charset (UTF-8
 * on the reference JDK), which would re-encode bytes 0x80-0xFF and break the
 * byte-exact transcript.</p>
 *
 * <p><b>Line-reading rule.</b> A response ends at a line feed, which is
 * consumed and never part of the answer; a carriage return is ordinary data and
 * is never treated as a terminator or stripped (GnuCOBOL's {@code FUNCTION
 * TRIM} removes spaces only, so the menu answer {@code 1} followed by a
 * carriage return is an invalid choice, and the port must agree). End of input
 * before a terminator - including end of input with no bytes read at all -
 * raises {@link InputEndedException}, which discards any partial line, exactly
 * as the {@code ON EXCEPTION} arm of {@code accept answer} did. At most
 * {@link #ACCEPT_BUFFER} bytes are consumed per call and at most
 * {@link #ANSWER_CAPACITY} bytes are returned.</p>
 *
 * <p><b>Silence and flushing.</b> Nothing here writes to {@code System.err} and
 * nothing here terminates the process: the only throw is
 * {@link InputEndedException}, and write failures are swallowed by
 * {@link PrintStream} by design, so a closed standard output can neither raise
 * an exception nor produce a diagnostic. Every write is flushed immediately -
 * approved exception D5 of the conversion plan: the byte content is identical
 * to the original's C-stdio output and only the timing differs, and flushing is
 * required so that an operator sees {@code Choice: } and each prompt before the
 * program blocks on input.</p>
 *
 * <p>The class is not thread-safe and does not need to be: the program is
 * single-threaded and constructs exactly one instance, held by
 * {@code FileEditor}.</p>
 */
final class Console {

    /**
     * Bytes consumed by a single {@link #readLine()} call before it returns
     * without having seen a line feed. This is the GnuCOBOL runtime's
     * per-{@code ACCEPT} buffer size, measured against the oracle (the original
     * program compiled with GnuCOBOL 3.2.0 under its default runtime): a
     * physical line of 8,191 bytes plus a line feed is delivered as two
     * responses, 8,192 bytes as {@code 8,191 + 1}, and 20,000 bytes as three.
     * The bytes past the limit - including the line feed - stay in the stream
     * and become the next response, so a long line is chunked rather than
     * swallowed.
     */
    private static final int ACCEPT_BUFFER = 8191;

    /**
     * Width of the response field {@code answer pic x(4096)}
     * [file_editor.cob:39], which truncates whatever {@code ACCEPT} delivered
     * into it. Declared here and nowhere else in the program, because the
     * truncation it governs is implemented here: a duplicate elsewhere would be
     * unreferenced and could drift.
     *
     * <p>The two limits do different jobs and are deliberately different
     * numbers: up to {@link #ACCEPT_BUFFER} bytes are consumed from the stream,
     * at most {@code ANSWER_CAPACITY} of them are returned to the caller. That
     * ordering is observable - a "New line" answer of 1,000 {@code x} followed
     * by 3,100 spaces and a {@code y} (4,101 bytes) is accepted as 1,000
     * {@code x}, because the field truncation happens before the caller's width
     * check.</p>
     */
    private static final int ANSWER_CAPACITY = 4096;

    /** Value {@link InputStream#read()} returns at end of input. */
    private static final int END_OF_STREAM = -1;

    /** Response terminator; consumed by {@link #readLine()}, never returned. */
    private static final int LINE_FEED = 0x0A;

    /**
     * Line terminator written by {@link #println(String)}. Written explicitly
     * so the transcript never depends on {@code System.lineSeparator()}.
     */
    private static final char NEWLINE = '\n';

    /** Operator input, buffered so the byte-at-a-time loop stays cheap. */
    private final InputStream in;

    /**
     * Operator output. Wraps {@link FileDescriptor#out} directly, with
     * automatic flushing disabled because {@link #print(String)} and
     * {@link #println(String)} flush explicitly after every write.
     */
    private final PrintStream out;

    /**
     * Record area for {@link #readLine()}, allocated once exactly as the COBOL
     * program declared its single {@code answer} field once
     * [file_editor.cob:39]. Only the bytes filled by the current call are ever
     * converted, which is what made {@code move spaces to answer}
     * [file_editor.cob:132] unnecessary here rather than merely redundant.
     */
    private final byte[] answer = new byte[ACCEPT_BUFFER];

    /**
     * Binds the console to the process's standard input and standard output.
     * Constructed exactly once, by {@code FileEditor}; nothing here can fail,
     * because wrapping an already-open file descriptor performs no I/O.
     */
    Console() {
        this.in = new BufferedInputStream(System.in);
        this.out = new PrintStream(
                new FileOutputStream(FileDescriptor.out), false, StandardCharsets.ISO_8859_1);
    }

    /**
     * Reads one operator response: the translation of {@code accept answer} and
     * its {@code ON EXCEPTION} arm in paragraph {@code read-answer}
     * [file_editor.cob:131-138], delivering exactly what the
     * {@code answer pic x(4096)} field [file_editor.cob:39] would have held.
     *
     * <p>Bytes are taken one at a time until a line feed is consumed or
     * {@link #ACCEPT_BUFFER} bytes have been retained, whichever comes first.
     * When the limit is what stops the loop, no further byte is consumed: the
     * remainder of the physical line, its line feed included, is the next
     * response. A carriage return is retained as data. An answer consisting of
     * nothing but its terminator is the empty string - not {@code null}, and
     * not end of input - which is how a blank line comes to be appended to a
     * document.</p>
     *
     * @return the response as an ISO-8859-1 string of at most
     *         {@link #ANSWER_CAPACITY} characters, each character one byte of
     *         input, with the terminating line feed removed
     * @throws InputEndedException if input ends before a terminating line feed,
     *         including the case where no byte at all was read, or if the read
     *         fails; any bytes already read are discarded, as the original did
     *         when its {@code ACCEPT} raised the exception condition
     */
    String readLine() {
        int length = 0;
        while (length < ACCEPT_BUFFER) {
            int b;
            try {
                b = in.read();
            } catch (IOException e) {
                // The stream is unusable, which is the condition libcob's
                // ACCEPT reported through ON EXCEPTION. No diagnostic is
                // produced here and nothing reaches standard error: the
                // catching method in FileEditor owns the two lifecycle lines
                // [file_editor.cob:135-136].
                throw new InputEndedException();
            }
            if (b == END_OF_STREAM) {
                // End of input before a terminator. The throw is unconditional,
                // so a partial line is discarded rather than returned - the
                // oracle's behavior, and the case the acceptance suite drives
                // when its final command has been consumed.
                throw new InputEndedException();
            }
            if (b == LINE_FEED) {
                // The terminator is consumed and is never part of the answer.
                break;
            }
            answer[length++] = (byte) b;
        }
        String response = new String(answer, 0, length, StandardCharsets.ISO_8859_1);
        // The field is narrower than the runtime's ACCEPT buffer, so what was
        // consumed and what is delivered differ for a long response.
        return response.length() > ANSWER_CAPACITY
                ? response.substring(0, ANSWER_CAPACITY)
                : response;
    }

    /**
     * Writes text with no line terminator: the translation of
     * {@code DISPLAY ... WITH NO ADVANCING}, used by the eleven prompts
     * [file_editor.cob:100,143,158,169,251,277,289,300,334,360,391]. The flush
     * is what lets a prompt reach the operator before the program blocks on
     * {@link #readLine()}, and it is also why a prompt and the message that
     * follows it share one output line, as in the original transcript.
     *
     * @param text the bytes to write, as ISO-8859-1 characters; written
     *             verbatim, with nothing trimmed, collapsed or appended
     */
    void print(String text) {
        out.print(text);
        out.flush();
    }

    /**
     * Writes text followed by a single line feed: the translation of a plain
     * {@code DISPLAY}. The terminator is written as {@link #NEWLINE} rather
     * than through {@link PrintStream#println(String)}, which would append the
     * platform line separator and make the transcript host-dependent.
     *
     * <p>Nothing is trimmed or collapsed, so {@code println(" ")} emits exactly
     * one space and a line feed, reproducing {@code display ' '}
     * [file_editor.cob:93,135].</p>
     *
     * @param text the bytes to write, as ISO-8859-1 characters
     */
    void println(String text) {
        out.print(text);
        out.print(NEWLINE);
        out.flush();
    }
}
