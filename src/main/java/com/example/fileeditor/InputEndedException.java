package com.example.fileeditor;

/**
 * Signals that operator input has ended, reproducing the
 * {@code ON EXCEPTION} arm of {@code accept answer} in paragraph
 * {@code read-answer} [file_editor.cob:131-138], whose {@code stop run}
 * [file_editor.cob:137] ended the run from wherever it was reached.
 *
 * <p>It is unchecked so that the unwind reaches
 * {@link FileEditor#main(String[])} from any depth of the paragraph call
 * graph: no {@code throws} clause is forced onto the paragraph methods and no
 * end-of-input flag has to be threaded back through them.
 */
final class InputEndedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    InputEndedException() {
        super();
    }
}
