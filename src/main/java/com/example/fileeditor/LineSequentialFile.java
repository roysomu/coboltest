package com.example.fileeditor;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Set;

/**
 * Line-sequential file primitives for the Java port of {@code file_editor.cob}.
 * This is the only class of the program that performs file-content I/O and
 * filesystem mutation - open, read, write, close, temporary-file creation,
 * atomic replace and delete - so every GnuCOBOL line-sequential rule and every
 * POSIX save primitive is implemented exactly once, here. {@link FileEditor}
 * decodes the operator's path bytes into a {@link java.nio.file.Path} and
 * passes it in; it opens, reads, writes and removes nothing itself.
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
 * {@code COB_LS_SPLIT=yes}), which supplies rules the COBOL source does not
 * spell out. Each member states the rules it implements; the two that shape the
 * class as a whole are deliberately asymmetric:</p>
 * <ul>
 *   <li><b>Reading</b> ({@link Input#readRecord()}) judges length before
 *       content. {@code LF} (0x0A) delimits records and is never part of one,
 *       and a {@code CR} (0x0D) immediately before it belongs to that
 *       delimiter. A record longer than {@link #RECORD_SIZE} bytes, or a file
 *       whose final byte is a bare {@code CR}, yields status {@code 06}; only a
 *       record within that limit is scanned for control bytes, which yield
 *       status {@code 09}. A {@code CR} anywhere else inside a record is data
 *       and so fails that scan - but it yields {@code 09} only where the record
 *       has not already yielded {@code 06}.</li>
 *   <li><b>Writing</b> ({@link Output#writeRecord(String)}) accepts no control
 *       byte at all, TAB included, so a line holding one is refused with status
 *       {@code 71} and none of it is emitted.</li>
 * </ul>
 *
 * <p>Every byte-to-char and char-to-byte conversion uses ISO-8859-1, so one
 * {@code char} is one byte and {@code String.length()} is a byte count exactly
 * as the original {@code PIC X} fields counted bytes. Bytes 0x7F-0xFF are
 * ordinary data in both directions.</p>
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
     * {@link Input#readRecord()} documents that accounting.
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

    private static final String STATUS_PERMISSION_DENIED = "37";

    private static final String STATUS_INVALID_WRITE_DATA = "71";

    /** Mask that widens a signed {@code byte} to its 0x00-0xFF value. */
    private static final int BYTE_MASK = 0xFF;

    private static final int LINE_FEED = 0x0A;

    /** Delimiter prefix when it immediately precedes {@link #LINE_FEED}. */
    private static final int CARRIAGE_RETURN = 0x0D;

    private static final int FIRST_PRINTABLE_BYTE = 0x20;

    private static final char SPACE = ' ';

    /**
     * Fixed part of the temporary file name. The COBOL template was
     * {@code <path>.tmp.XXXXXX} [file_editor.cob:465]; the six random
     * characters {@code mkstemp} substituted become the decimal digits of a
     * random {@code long}, so the name still has the {@code *.tmp.*} shape a
     * residue check looks for.
     */
    private static final String TEMP_INFIX = ".tmp.";

    /**
     * Exclusive, link-refusing create of the temporary file:
     * {@code O_CREAT | O_EXCL | O_NOFOLLOW}. A name that already exists - a
     * planted file or symbolic link - is refused rather than opened.
     */
    private static final Set<OpenOption> TEMP_OPEN_OPTIONS = Set.of(
            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS);

    /**
     * Mode {@code 0600} as {@code mkstemp} gave it [file_editor.cob:467],
     * applied by the create itself so the file is never briefly readable by
     * anyone else. Empty, never mutated, on a filesystem with no POSIX
     * permission view, which would reject the attribute.
     */
    private static final FileAttribute<?>[] TEMP_ATTRIBUTES =
            FileSystems.getDefault().supportedFileAttributeViews().contains("posix")
                    ? new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(
                            PosixFilePermissions.fromString("rw-------"))}
                    : new FileAttribute<?>[0];

    /** Distinct temporary names tried before the create is reported as failed. */
    private static final int TEMP_NAME_ATTEMPTS = 100;

    /**
     * Source of the temporary names. A guessable name can be occupied in
     * advance, which is why these are not predictable.
     */
    private static final SecureRandom TEMP_NAMES = new SecureRandom();

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
         * always consumed, so the next call starts at the next record and an
         * arbitrarily long line costs constant memory. A final record that end
         * of file cuts short, with no {@code LF} of its own, is still a
         * record.</p>
         *
         * <p>A {@code CR} is held <em>pending</em> rather than stored: if the
         * next byte is {@code LF} the pending {@code CR} is part of the
         * delimiter and is discarded without ever counting toward the record
         * length; if the next byte is anything else the pending {@code CR} is
         * flushed into the record as data, where the control-byte scan below
         * refuses it. The flush happens exactly once,
         * before the byte that ended the hold is classified, so a run of
         * {@code CR} bytes stores all but its last one and that last one faces
         * the same {@code LF} test as any other held {@code CR}.</p>
         *
         * <p>Because a delimiter {@code CR} never occupies a record byte, the
         * length boundary is the same for {@code LF} and {@code CRLF} files.
         * {@link #RECORD_SIZE} data bytes are returned rather than refused, and
         * it is the caller's own 1,024-byte width check that rejects them with
         * {@code File exceeds 1000 lines or 1024 characters per line.}
         * [file_editor.cob:192-197]; {@link #RECORD_SIZE} + 1 data bytes are
         * the first to yield status {@code 06} here. A line of 1,024 {@code x}
         * followed by one space is {@link #RECORD_SIZE} bytes and therefore
         * opens, the caller storing it as 1,024 bytes once trailing spaces are
         * removed.</p>
         *
         * <p>The finish sequence fixes the precedence of the two statuses: the
         * over-limit {@code 06} is raised first, then the {@code 06} for a file
         * whose final byte is a bare {@code CR}, and only a record within the
         * limit is scanned byte by byte. An embedded {@code CR}, or any other
         * unacceptable control byte, therefore yields {@code 09} only where the
         * record has not already yielded {@code 06}.</p>
         *
         * @return the record without its delimiter, the empty string for a blank
         *         line, or {@code null} at end of file with nothing consumed -
         *         the {@code io-status} {@code 10} that ends the read loop
         *         [file_editor.cob:190]
         * @throws FileStatusException status {@code 06} when the record is
         *                             longer than {@link #RECORD_SIZE} bytes or
         *                             the file's final byte is a bare
         *                             {@code CR}; status {@code 09} when a
         *                             record within that limit retains an
         *                             unacceptable control byte
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
     *
     * <p>The instance owns the single open handle on the temporary file that
     * {@link LineSequentialFile#createTemp(Path)} obtained when it created the
     * file, and every record is written through that handle. The temporary is
     * never opened a second time by name, so the name cannot be substituted
     * between the create and the writes; {@link #path()} is used only for the
     * rename that completes the save and the removal that cleans up after a
     * failure.</p>
     */
    static final class Output implements Closeable {

        /** The temporary file this writes to. */
        private final Path path;

        /**
         * The handle the file was created with, held until {@link #close()}
         * releases it: what keeps the writes on the file that was created.
         */
        private final FileChannel channel;

        /** Buffered record writer over {@link #channel}; closing it closes the channel. */
        private final OutputStream out;

        private Output(Path path, FileChannel channel) {
            this.path = path;
            this.channel = channel;
            this.out = new BufferedOutputStream(Channels.newOutputStream(channel));
        }

        /**
         * @return the temporary file's path, for the rename that completes the
         *         save [file_editor.cob:500-503] and the removal that cleans up
         *         after a failure [file_editor.cob:512-514]
         */
        Path path() {
            return path;
        }

        /**
         * {@code OPEN OUTPUT text-file} on the temporary file
         * [file_editor.cob:479-482], performed on the retained handle rather
         * than by reopening the name.
         *
         * <p>{@code OPEN OUTPUT} presents an empty file positioned at its
         * start; the exclusive create already produced one, so the truncation
         * has nothing to remove and is instead the check that the handle is
         * open and writable - the one way this step can still fail. Such a
         * failure releases the handle before it propagates, so a save that
         * never got a writer leaks nothing.</p>
         *
         * @throws IOException the retained handle cannot be written to; the
         *                     caller maps this to
         *                     {@code Cannot open temporary file. File status: 30}
         */
        void open() throws IOException {
            try {
                channel.truncate(0);
            } catch (IOException | RuntimeException e) {
                try {
                    out.close();
                } catch (IOException | RuntimeException ignored) {
                    // The open has already failed; the caller reports that and
                    // removes the temporary file.
                }
                throw e;
            }
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
         * it closes the retained handle <em>even when that flush fails</em>.
         * Flushing in a separate statement beforehand would skip the close on
         * exactly that failure and leak the handle of every save that
         * failed.</p>
         *
         * @throws IOException the flush performed by the close, or the close
         *                     itself, failed; the handle has been released
         *                     either way
         */
        @Override
        public void close() throws IOException {
            out.close();
        }
    }

    /**
     * Creates the unique, private temporary file the save writes into and
     * returns the writer that owns its one open handle, replacing
     * {@code call static "mkstemp"} over the template
     * {@code <path>.tmp.XXXXXX} together with the {@code close} of the
     * descriptor it returned [file_editor.cob:464-477].
     *
     * <p>The file is always a sibling of the destination in the destination's
     * own directory, named {@code <name>.tmp.<digits>} after 64 random bits,
     * so the rename that completes the save is never across filesystems and
     * the name has the {@code *.tmp.*} shape the acceptance suite looks for.
     * The mode is {@code 0600}, as {@code mkstemp} gave it.</p>
     *
     * <p>Creating the file and opening it are one operation, and the handle it
     * yields is the one every record is written through. The original instead
     * closed {@code mkstemp}'s descriptor and reopened the name
     * [file_editor.cob:474,479], which left a window: anyone else able to
     * write to the destination directory could unlink the temporary and leave
     * a symbolic link under that name, and the reopen would follow it and
     * truncate whatever it pointed at. {@link #TEMP_OPEN_OPTIONS} refuses both
     * a name that already exists and a link that appears at that instant, and
     * {@link #TEMP_ATTRIBUTES} makes {@code 0600} part of the create. A name
     * already taken is not an error: another is tried, up to
     * {@link #TEMP_NAME_ATTEMPTS} of them.</p>
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
     * @return a writer owning the newly created private temporary sibling of
     *         {@code target}; the caller closes it and reads
     *         {@link Output#path()} for the rename and the cleanup
     * @throws IOException no temporary file could be created; the caller
     *                     reports {@code Cannot create a temporary file. Check
     *                     the path and directory permissions.}, leaves the
     *                     document unsaved and touches nothing on disk
     */
    static Output createTemp(Path target) throws IOException {
        Path abs = target.toAbsolutePath();
        Path parent = abs.getParent();
        Path name = abs.getFileName();
        if (parent == null || name == null) {
            throw new IOException(
                    "no sibling temporary file can be created for " + abs);
        }
        String prefix = name.toString() + TEMP_INFIX;
        FileAlreadyExistsException taken = null;
        for (int attempt = 0; attempt < TEMP_NAME_ATTEMPTS; attempt++) {
            Path temp = parent.resolve(
                    prefix + Long.toUnsignedString(TEMP_NAMES.nextLong()));
            try {
                return new Output(temp,
                        FileChannel.open(temp, TEMP_OPEN_OPTIONS, TEMP_ATTRIBUTES));
            } catch (FileAlreadyExistsException occupied) {
                taken = occupied;
            }
        }
        throw new IOException(TEMP_NAME_ATTEMPTS
                + " temporary file names were already taken in " + parent, taken);
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
     * <p>The temporary is closed by the time this runs, and this is the one
     * step of the save that names it rather than using its handle, because
     * renaming is offered on paths only - here as in the C library the original
     * called. Nothing is written through the name, so the worst a substitution
     * under it could do is fail this call or publish the substitute; it cannot
     * write through a link into a file of the attacker's choosing.</p>
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
