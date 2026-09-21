package com.example.fileeditor;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * Line-sequential file primitives for the Java port of {@code file_editor.cob}.
 * This is the only class of the program that performs file-content I/O and
 * filesystem mutation - open, read, write, close, temporary-file creation,
 * atomic replace and delete - so every GnuCOBOL line-sequential rule and every
 * POSIX save primitive is implemented exactly once, here. The one other
 * {@code java.nio.file} use in the program is {@link FileEditor}'s path
 * conversion: it decodes the operator's path bytes into a
 * {@link java.nio.file.Path} and passes that in, without opening, reading,
 * writing or removing anything itself.
 *
 * <p>Reproduces:</p>
 * <ul>
 *   <li>the file connector {@code select text-file assign to io-path /
 *       organization is line sequential / file status is io-status} and its
 *       1,025-byte record area {@code 01 file-line pic x(1025)}
 *       [file_editor.cob:8-15];</li>
 *   <li>the {@code OPEN INPUT} / {@code READ} / {@code CLOSE} load path of
 *       paragraph {@code open-document} [file_editor.cob:166-212];</li>
 *   <li>the filesystem primitives of paragraph {@code save-document}
 *       [file_editor.cob:461-515]: {@code mkstemp}, {@code OPEN OUTPUT},
 *       {@code WRITE}, {@code CLOSE}, {@code rename} and {@code unlink}.</li>
 * </ul>
 *
 * <p>The behavioral oracle is that program compiled with GnuCOBOL 3.2.0 under
 * its default runtime configuration ({@code COB_LS_VALIDATE=yes},
 * {@code COB_LS_SPLIT=yes}). Several observable rules are supplied by that
 * runtime rather than by the COBOL source; each was measured against it and is
 * made explicit here:</p>
 * <ul>
 *   <li><b>Reading.</b> {@code LF} (0x0A) delimits records and is never part of
 *       one. A {@code CR} (0x0D) immediately followed by {@code LF} belongs to
 *       the delimiter and is discarded; a {@code CR} anywhere else in a record
 *       is data and therefore fails the control-byte test. An unterminated
 *       final record is still a record. A record longer than
 *       {@link #RECORD_SIZE} bytes, or a file whose final byte is a bare
 *       {@code CR}, yields status {@code 06}. A retained record byte in
 *       0x00-0x1F other than BS, TAB, FF, SI or ESC yields status {@code 09}.
 *       Bytes 0x7F-0xFF are ordinary data.</li>
 *   <li><b>Writing.</b> A line containing any byte in 0x00-0x1F - TAB included,
 *       with no exemptions - yields status {@code 71} and no byte of that
 *       record is emitted. Otherwise the line is written with its trailing
 *       spaces removed, terminated by a single {@code LF}. Bytes 0x7F-0xFF are
 *       written unchanged.</li>
 * </ul>
 *
 * <p>Every byte-to-char and char-to-byte conversion uses ISO-8859-1, so one
 * {@code char} is one byte and {@code String.length()} is a byte count exactly
 * as the original {@code PIC X} fields counted bytes.</p>
 *
 * <p>Failures are communicated only by throwing. {@link FileStatusException}
 * carries the two-character {@code io-status} value the caller displays; a bare
 * {@link IOException} is mapped by the caller to the catch-all status
 * {@code 30}. Nothing here writes to {@code System.out} or {@code System.err},
 * and nothing here terminates the process.</p>
 */
final class LineSequentialFile {

    /**
     * Width of the record area, one byte wider than the 1,024-byte line limit
     * so that an over-long line is detectable: {@code 01 file-line pic x(1025)}
     * [file_editor.cob:15]. A record of exactly this many bytes is returned to
     * the caller, whose own width check rejects it; only a longer record is
     * refused here with status {@code 06}. A {@code CR} that turns out to
     * belong to a {@code CRLF} delimiter never occupies one of these bytes;
     * {@link Input#readRecord()} documents that accounting and why it differs
     * from the plan's pseudocode.
     */
    static final int RECORD_SIZE = 1025;

    /** Record longer than {@link #RECORD_SIZE}, or file ending in a bare CR. */
    private static final String STATUS_RECORD_OVERFLOW = "06";

    /** Record byte that the line-sequential reader refuses to accept. */
    private static final String STATUS_INVALID_DATA = "09";

    /** libcob's catch-all permanent error, used for any other I/O failure. */
    private static final String STATUS_PERMANENT_ERROR = "30";

    /** File not found on {@code OPEN INPUT}; drives the "Create it?" flow. */
    private static final String STATUS_FILE_NOT_FOUND = "35";

    /** Permission denied on {@code OPEN INPUT}. */
    private static final String STATUS_PERMISSION_DENIED = "37";

    /** Record the line-sequential writer refuses to write. */
    private static final String STATUS_INVALID_WRITE_DATA = "71";

    /** Mask that widens a signed {@code byte} to its 0x00-0xFF value. */
    private static final int BYTE_MASK = 0xFF;

    /** Record delimiter. */
    private static final int LINE_FEED = 0x0A;

    /** Delimiter prefix when it immediately precedes {@link #LINE_FEED}. */
    private static final int CARRIAGE_RETURN = 0x0D;

    /** First non-control byte; everything below it is a control byte. */
    private static final int FIRST_PRINTABLE_BYTE = 0x20;

    /** The only byte trailing-space removal strips, on write. */
    private static final char SPACE = ' ';

    /**
     * Fixed part of the temporary file name. The COBOL template was
     * {@code <path>.tmp.XXXXXX} [file_editor.cob:466]; the six random
     * characters become the digits {@code Files.createTempFile} appends.
     */
    private static final String TEMP_INFIX = ".tmp.";

    /** Holder of nested types and static primitives; never instantiated. */
    private LineSequentialFile() {
    }

    /**
     * The value of {@code io-status} [file_editor.cob:10,31] for a condition the
     * COBOL program tested explicitly. Deliberately a <em>checked</em>
     * exception: the original tested {@code io-status} at a handful of known
     * points [file_editor.cob:167,189,209,480,488,494], and a checked type
     * forces the caller to handle it at exactly those points.
     */
    static final class FileStatusException extends Exception {

        private static final long serialVersionUID = 1L;

        /**
         * The two-character COBOL file status, one of {@code 06}, {@code 09},
         * {@code 30}, {@code 35}, {@code 37} or {@code 71}. Package-private so
         * the caller can splice it into its diagnostics verbatim, as the COBOL
         * spliced {@code io-status}.
         */
        final String status;

        /**
         * @param status two-character COBOL file status; also carried as the
         *               exception message, which is never displayed and exists
         *               only to make a stack trace readable while debugging
         */
        FileStatusException(String status) {
            super(status);
            this.status = status;
        }
    }

    /**
     * Reading half of the file connector: {@code OPEN INPUT text-file},
     * {@code READ text-file} and {@code CLOSE text-file} as paragraph
     * {@code open-document} drives them [file_editor.cob:166-212].
     *
     * <p>The caller closes this explicitly rather than through
     * try-with-resources so that its diagnostics appear in COBOL source order;
     * {@link #close()} is therefore safe to call after a failed read.</p>
     */
    static final class Input implements Closeable {

        private final InputStream in;

        /**
         * Record area, allocated once per open file exactly as the COBOL FD
         * allocated {@code file-line} once [file_editor.cob:15]. The reader is
         * bounded by this buffer, so an arbitrarily long physical line costs
         * constant memory.
         */
        private final byte[] buf = new byte[RECORD_SIZE];

        private Input(InputStream in) {
            this.in = in;
        }

        /**
         * Opens a file for reading, mapping the failure to the {@code io-status}
         * value the COBOL {@code evaluate} tested [file_editor.cob:166-184].
         *
         * <p>A directory opens as an <em>empty document</em>, which is the
         * oracle's behavior (it prints {@code Opened: adir} and lists nothing).
         * The pre-check is also functionally necessary: on Linux
         * {@code Files.newInputStream} succeeds for a directory and only fails
         * on the first read.</p>
         *
         * <p>{@code NoSuchFileException} covers both a missing file and a
         * missing parent directory, and both must map to status {@code 35} so
         * that the {@code File does not exist. Create it? (Y/N): } flow runs
         * [file_editor.cob:168-183].</p>
         *
         * @param p path to open
         * @return an open reader positioned before the first record
         * @throws FileStatusException status {@code 35} when the file or its
         *                             parent directory does not exist,
         *                             {@code 37} when access is denied, and
         *                             {@code 30} for any other I/O failure
         */
        static Input open(Path p) throws FileStatusException {
            if (Files.isDirectory(p)) {
                return new Input(new ByteArrayInputStream(new byte[0]));
            }
            try {
                return new Input(new BufferedInputStream(Files.newInputStream(p)));
            } catch (NoSuchFileException e) {
                throw new FileStatusException(STATUS_FILE_NOT_FOUND);
            } catch (AccessDeniedException e) {
                throw new FileStatusException(STATUS_PERMISSION_DENIED);
            } catch (IOException e) {
                throw new FileStatusException(STATUS_PERMANENT_ERROR);
            }
        }

        /**
         * Reads one record, reproducing {@code READ text-file}
         * [file_editor.cob:188] together with the GnuCOBOL 3.2 line-sequential
         * rules the COBOL source does not spell out.
         *
         * <p>The reader is bounded: at most {@link #RECORD_SIZE} bytes are
         * retained, and bytes beyond that are consumed and discarded while only
         * setting a saturating over-limit flag. The whole physical line is
         * always consumed, so the next call starts at the next record.</p>
         *
         * <p>A {@code CR} is held <em>pending</em> rather than stored: if the
         * next byte is {@code LF} the pending {@code CR} is part of the
         * delimiter and is discarded without ever counting toward the record
         * length; if the next byte is anything else the pending {@code CR} is
         * flushed into the record as data, where the control-byte scan below
         * refuses it with status {@code 09}. The flush happens exactly once,
         * before the byte that ended the hold is classified, so a run of
         * {@code CR} bytes stores all but its last one and that last one faces
         * the same {@code LF} test as any other held {@code CR}.</p>
         *
         * <p>Holding the {@code CR} outside the record area is deliberately
         * <em>not</em> the formulation AAP sections 0.5.2 and 0.7.5 write out,
         * which retains the {@code CR} in the {@link #RECORD_SIZE} buffer like
         * any other byte and strips it again when {@code LF} arrives. That
         * formulation is not reproduced because it reports status {@code 06}
         * one byte early: after a line of {@link #RECORD_SIZE} data bytes the
         * {@code CR} is the byte that overflows the buffer and sets the
         * over-limit flag, and because its removal at {@code LF} is itself
         * conditional on that flag being clear, the flag survives to the
         * finish sequence and becomes {@code 06}. AAP section 0.1.1 makes the
         * GnuCOBOL 3.2.0 oracle authoritative over the plan's pseudocode, and
         * the oracle was measured as:</p>
         * <ul>
         *   <li>{@link #RECORD_SIZE} data bytes then {@code CRLF} - the record
         *       is returned and the caller reports {@code File exceeds 1000
         *       lines or 1024 characters per line.}, not {@code 06};</li>
         *   <li>1,024 {@code x} then one space then {@code CRLF}, also
         *       {@link #RECORD_SIZE} data bytes - the file opens, the stored
         *       line being 1,024 bytes once trailing spaces are removed;</li>
         *   <li>{@code 06} appears only from {@link #RECORD_SIZE} + 1 data
         *       bytes upward, with or without a trailing {@code CR};</li>
         *   <li>a file whose final byte is a bare {@code CR} yields {@code 06},
         *       while a {@code CR} anywhere else inside a record yields
         *       {@code 09}.</li>
         * </ul>
         *
         * <p>Those four outcomes are what this method produces, and they are
         * what AAP section 0.5.2's own prose describes; only its pseudocode
         * disagrees.</p>
         *
         * @return the record without its delimiter, the empty string for a blank
         *         line, or {@code null} at end of file with nothing consumed -
         *         the {@code io-status} {@code 10} that ends the read loop
         *         [file_editor.cob:190]
         * @throws FileStatusException status {@code 06} when the record is
         *                             longer than {@link #RECORD_SIZE} bytes or
         *                             the file's final byte is a bare
         *                             {@code CR}; status {@code 09} when a
         *                             retained byte is an unacceptable control
         *                             byte
         * @throws IOException the underlying read failed; the caller maps this
         *                     to status {@code 30}
         */
        String readRecord() throws FileStatusException, IOException {
            int retained = 0;
            boolean overLimit = false;
            boolean any = false;
            boolean endedAtEof = false;
            boolean pendingCr = false;
            while (true) {
                int b = in.read();
                if (b == -1) {
                    if (!any && !pendingCr) {
                        return null;
                    }
                    endedAtEof = true;
                    break;
                }
                if (b == LINE_FEED) {
                    // A held CR belongs to the delimiter: neither stored nor
                    // counted. Tested before the flush below so it stays that
                    // way.
                    break;
                }
                // The hold ended with a byte that is not LF, so the held CR is
                // data. Flushed exactly once here, before b itself is
                // classified, which covers a CR run too: every CR but the last
                // is stored and the last one is held in turn.
                if (pendingCr) {
                    pendingCr = false;
                    any = true;
                    if (retained < RECORD_SIZE) {
                        buf[retained++] = (byte) CARRIAGE_RETURN;
                    } else {
                        overLimit = true;
                    }
                }
                if (b == CARRIAGE_RETURN) {
                    pendingCr = true;
                    continue;
                }
                any = true;
                if (retained < RECORD_SIZE) {
                    buf[retained++] = (byte) b;
                } else {
                    overLimit = true;
                }
            }
            if (overLimit) {
                throw new FileStatusException(STATUS_RECORD_OVERFLOW);
            }
            if (endedAtEof && pendingCr) {
                throw new FileStatusException(STATUS_RECORD_OVERFLOW);
            }
            for (int i = 0; i < retained; i++) {
                int v = buf[i] & BYTE_MASK;
                if (v < FIRST_PRINTABLE_BYTE && !isAcceptedControlByte(v)) {
                    throw new FileStatusException(STATUS_INVALID_DATA);
                }
            }
            return new String(buf, 0, retained, StandardCharsets.ISO_8859_1);
        }

        /**
         * {@code CLOSE text-file} after a load [file_editor.cob:208-212]; the
         * caller maps a failure to {@code Cannot close file. File status: 30}.
         *
         * @throws IOException the underlying close failed
         */
        @Override
        public void close() throws IOException {
            in.close();
        }
    }

    /**
     * Writing half of the file connector: {@code OPEN OUTPUT text-file},
     * {@code WRITE file-line} and {@code CLOSE text-file} as paragraph
     * {@code save-document} drives them on the temporary file
     * [file_editor.cob:479-497].
     *
     * <p>Open, write and close stay three separate calls so the caller can emit
     * the three distinguishable diagnostics the COBOL emitted at its three
     * separate {@code io-status} check points [file_editor.cob:480,488,494].
     * The caller closes this explicitly rather than through try-with-resources
     * so that its diagnostics appear in COBOL source order; {@link #close()} is
     * therefore safe to call after a refused or failed write.</p>
     */
    static final class Output implements Closeable {

        private final OutputStream out;

        private Output(OutputStream out) {
            this.out = out;
        }

        /**
         * {@code OPEN OUTPUT text-file} on the temporary file
         * [file_editor.cob:479-482]. The temporary already exists - it was
         * created by {@link LineSequentialFile#createTemp(Path)} - so the file
         * is opened for writing and truncated, never created here.
         *
         * @param temp the temporary file created by
         *             {@link LineSequentialFile#createTemp(Path)}
         * @return an open writer positioned at the start of the file
         * @throws IOException the file could not be opened for writing; the
         *                     caller maps this to
         *                     {@code Cannot open temporary file. File status: 30}
         */
        static Output open(Path temp) throws IOException {
            return new Output(new BufferedOutputStream(Files.newOutputStream(
                    temp, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)));
        }

        /**
         * {@code WRITE file-line} for one document line
         * [file_editor.cob:486-492].
         *
         * <p>Validation happens before any byte leaves the buffer, so a record
         * the writer refuses is never partially emitted. The refused set is
         * every byte in 0x00-0x1F, <em>TAB included and with no exemptions</em>
         * - measured against the oracle, which refuses a document containing
         * 0x01, 0x08, 0x09, 0x0B, 0x0C, 0x1B or 0x1F with status {@code 71}.
         * Note that this is deliberately <em>not</em> the set accepted on read:
         * the asymmetry is the oracle's behavior. Bytes 0x7F-0xFF are
         * written.</p>
         *
         * <p>An accepted record is written with its trailing spaces removed and
         * terminated by one {@code LF}, which is what line-sequential
         * organization does with the fixed-width {@code file-line} record. A
         * document with no lines therefore produces a zero-byte file, and a
         * blank or all-space line produces a bare {@code LF}.</p>
         *
         * @param line the document line to write, one char per byte
         * @throws FileStatusException status {@code 71} when the line contains
         *                             any control byte; nothing has been written
         * @throws IOException the underlying write failed; the caller maps this
         *                     to status {@code 30}
         */
        void writeRecord(String line) throws FileStatusException, IOException {
            for (int i = 0; i < line.length(); i++) {
                if (line.charAt(i) < FIRST_PRINTABLE_BYTE) {
                    throw new FileStatusException(STATUS_INVALID_WRITE_DATA);
                }
            }
            out.write(rstripSpaces(line).getBytes(StandardCharsets.ISO_8859_1));
            out.write(LINE_FEED);
        }

        /**
         * {@code CLOSE text-file} after a save [file_editor.cob:493-497]; the
         * caller maps a failure to
         * {@code Cannot close saved file. File status: 30}.
         *
         * <p>Closing the buffered stream is the whole operation: it flushes the
         * bytes still held in the buffer, so a failure to reach the disk
         * surfaces here as an {@link IOException} rather than being lost, and
         * it releases the underlying descriptor <em>even when that flush
         * fails</em>. Flushing in a separate statement beforehand would skip
         * the close on exactly that failure and leak the descriptor of every
         * save that failed.</p>
         *
         * @throws IOException the flush performed by the close, or the close
         *                     itself, failed; the descriptor has been released
         *                     either way
         */
        @Override
        public void close() throws IOException {
            out.close();
        }
    }

    /**
     * Creates the unique, private temporary file the save writes into,
     * replacing {@code call static "mkstemp"} over the template
     * {@code <path>.tmp.XXXXXX} [file_editor.cob:464-474].
     *
     * <p>{@code Files.createTempFile} creates the file exclusively and, on a
     * POSIX filesystem, with mode {@code 0600}; an empty suffix (not
     * {@code null}, which would append {@code .tmp}) yields
     * {@code <name>.tmp.<digits>}. The file is always a sibling of the
     * destination in the destination's own directory, so the later rename is
     * never across filesystems, and it matches the {@code *.tmp.*} shape the
     * acceptance suite looks for.</p>
     *
     * <p>A missing destination directory fails here exactly as {@code mkstemp}
     * did, with a {@code NoSuchFileException}: no directory is created and no
     * fallback location is used. The guard on a {@code null} parent or file
     * name covers the filesystem root, which {@link Input#open(Path)} accepts
     * as a directory and therefore opens as an empty document; the oracle's
     * {@code mkstemp("/.tmp.XXXXXX")} fails for an unprivileged operator, and
     * failing here produces the same diagnostic instead of letting an
     * exception escape.</p>
     *
     * @param target the destination file the save will replace
     * @return the newly created temporary sibling of {@code target}
     * @throws IOException the temporary file could not be created; the caller
     *                     reports {@code Cannot create a temporary file. Check
     *                     the path and directory permissions.}, leaves the
     *                     document unsaved and touches nothing on disk
     */
    static Path createTemp(Path target) throws IOException {
        Path abs = target.toAbsolutePath();
        Path parent = abs.getParent();
        Path name = abs.getFileName();
        if (parent == null || name == null) {
            throw new IOException(
                    "no sibling temporary file can be created for " + abs);
        }
        return Files.createTempFile(parent, name.toString() + TEMP_INFIX, "");
    }

    /**
     * Moves the completed temporary file over the destination, replacing
     * {@code call static "rename"} [file_editor.cob:500-510].
     *
     * <p>{@code ATOMIC_MOVE} alone <em>is</em> {@code rename(2)}: it is atomic,
     * it replaces an existing destination, it replaces a symbolic link rather
     * than following it, and it fails when the destination is a directory - in
     * which case the caller reports
     * {@code Cannot replace destination. Changes remain unsaved.} and the
     * document stays dirty.</p>
     *
     * @param temp   the temporary file holding the saved document
     * @param target the destination to replace
     * @throws IOException the rename failed; the destination is untouched
     */
    static void replace(Path temp, Path target) throws IOException {
        Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
    }

    /**
     * Removes the temporary file on every failure path, replacing
     * {@code call static "unlink"}, whose result the COBOL discarded
     * [file_editor.cob:512-515].
     *
     * <p>Silent by design: this runs while a diagnostic for the original
     * failure is already being reported, so a failure to clean up must neither
     * replace that diagnostic nor escape to the caller. Runtime exceptions are
     * swallowed along with {@link IOException} because nothing may reach
     * standard error.</p>
     *
     * @param p the temporary file to remove; a missing file is not an error
     */
    static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException | RuntimeException ignored) {
            // Cleanup is best effort; the caller is already reporting why the
            // save failed and must keep exit status 0 with an empty stderr.
        }
    }

    /**
     * Reports whether a control byte is one the line-sequential reader accepts
     * inside a record: BS (0x08), TAB (0x09), FF (0x0C), SI (0x0F) or ESC
     * (0x1B). Measured against the oracle, which refuses every other byte
     * below 0x20 with status {@code 09}. This set applies to reading only -
     * writing accepts no control byte at all.
     *
     * @param v a byte value in 0x00-0xFF
     * @return {@code true} when a record may contain {@code v}
     */
    private static boolean isAcceptedControlByte(int v) {
        return v == 0x08 || v == 0x09 || v == 0x0C || v == 0x0F || v == 0x1B;
    }

    /**
     * Removes trailing spaces only, reproducing what line-sequential
     * organization does to the fixed-width {@code file-line} record on
     * {@code WRITE} [file_editor.cob:486-487].
     *
     * <p>Only the byte 0x20 is removed. {@code String.trim()} would also strip
     * TAB and {@code CR}, and {@code String.strip()} is Unicode-whitespace
     * aware; either would destroy data the oracle preserves, so neither is
     * used.</p>
     *
     * @param s the line to strip
     * @return {@code s} without its trailing spaces, possibly empty
     */
    private static String rstripSpaces(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == SPACE) {
            end--;
        }
        return end == s.length() ? s : s.substring(0, end);
    }
}
