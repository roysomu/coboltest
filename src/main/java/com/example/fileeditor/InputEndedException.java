package com.example.fileeditor;

/**
 * Signals that the operator's input stream ended, reproducing the
 * {@code ON EXCEPTION} arm of the {@code ACCEPT} statement in the
 * {@code read-answer} paragraph of the original program
 * ({@code file_editor.cob} lines 131-138; the {@code stop run} that arm
 * executes is line 137).
 *
 * <p>The exception is unchecked so that the unwind from a nested
 * {@code readAnswer()} call reaches {@code FileEditor.main} the way COBOL's
 * {@code STOP RUN} terminated the run from wherever it was reached: no
 * {@code throws} clause is forced onto the paragraph methods and no
 * end-of-input flag has to be threaded back through them.
 *
 * <p>{@code Console.readLine()} throws it when standard input reaches
 * end-of-file before a terminating line feed (including the case where no
 * bytes at all were read) and when the underlying read fails; only
 * {@code FileEditor.main} catches it, where it prints the two lifecycle lines
 * and returns normally with exit status 0. The type carries no message, no
 * cause and no other state, because the diagnostic text belongs to the
 * catching method, exactly as the two {@code display} statements of the
 * paragraph did.
 */
final class InputEndedException extends RuntimeException {

    /** Fixed identifier for the inherited {@link java.io.Serializable} contract. */
    private static final long serialVersionUID = 1L;

    /**
     * Creates the signal. There is deliberately no message-taking or
     * cause-taking form: the paragraph's {@code ON EXCEPTION} arm conveyed no
     * data, only the fact that input ended.
     */
    InputEndedException() {
        super();
    }
}
