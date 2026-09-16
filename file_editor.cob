       >>source format free
identification division.
program-id. file-editor.

environment division.
input-output section.
file-control.
    select text-file assign to io-path
        organization is line sequential
        file status is io-status.

data division.
file section.
fd text-file.
01 file-line pic x(1025).

working-storage section.
78 max-lines value 1000.
78 max-width value 1024.
01 document-buffer.
   02 document-line pic x(1024) occurs 1000 times.
01 pending-buffer.
   02 pending-line pic x(1024) occurs 1000 times.
01 line-count binary-long value 0.
01 pending-count binary-long value 0.
01 file-path pic x(512) value spaces.
01 io-path pic x(560).
01 temp-path pic x(560).
01 c-temp-path pic x(561).
01 c-file-path pic x(513).
01 io-status pic xx.
01 opened-flag pic 9 value 0.
01 dirty-flag pic 9 value 0.
01 done-flag pic 9 value 0.
01 eof-flag pic 9.
01 failed-flag pic 9.
01 cancel-flag pic 9.
01 valid-flag pic 9.
01 answer pic x(4096).
01 menu-choice pic x(4).
01 operation-choice pic x(4).
01 new-text pic x(1024).
01 result-text pic x(1024).
01 number-value binary-long.
01 line-number binary-long.
01 word-number binary-long.
01 word-count binary-long.
01 start-pos binary-long.
01 end-pos binary-long.
01 scan-pos binary-long.
01 old-length binary-long.
01 new-length binary-long.
01 result-length binary-long.
01 tail-length binary-long.
01 write-pos binary-long.
01 idx binary-long.
01 random-index binary-long.
01 random-count binary-long.
01 c-result usage binary-long.
01 seed-value pic 9(8).
01 unused-random usage computational-2.
01 display-number pic ZZZ9.
01 vocabulary.
   02 filler pic x(12) value 'apple'.
   02 filler pic x(12) value 'river'.
   02 filler pic x(12) value 'mountain'.
   02 filler pic x(12) value 'sunshine'.
   02 filler pic x(12) value 'forest'.
   02 filler pic x(12) value 'ocean'.
   02 filler pic x(12) value 'garden'.
   02 filler pic x(12) value 'cloud'.
   02 filler pic x(12) value 'journey'.
   02 filler pic x(12) value 'coffee'.
   02 filler pic x(12) value 'window'.
   02 filler pic x(12) value 'music'.
   02 filler pic x(12) value 'planet'.
   02 filler pic x(12) value 'silver'.
   02 filler pic x(12) value 'meadow'.
   02 filler pic x(12) value 'breeze'.
   02 filler pic x(12) value 'cobalt'.
   02 filler pic x(12) value 'story'.
   02 filler pic x(12) value 'lantern'.
   02 filler pic x(12) value 'dream'.
01 word-table redefines vocabulary.
   02 random-word pic x(12) occurs 20 times.

procedure division.
main.
    accept seed-value from time
    compute unused-random = function random(seed-value)
    display 'COBOL Text File Editor'
    perform until done-flag = 1
        display ' '
        display '1 Open / create file   2 View file'
        display '3 Append a line        4 Append words to a line'
        display '5 Generate random words (new line)'
        display '6 Replace a line       7 Replace a word'
        display '8 Delete a word        9 Delete a line'
        display 'S Save                 Q Quit'
        display 'Choice: ' with no advancing
        perform read-answer
        move function upper-case(function trim(answer))
            to menu-choice
        evaluate menu-choice
            when '1' perform open-document
            when 'Q'
                perform check-unsaved
                if cancel-flag = 0 move 1 to done-flag end-if
            when '2' when '3' when '4' when '5'
            when '6' when '7' when '8' when '9' when 'S'
                if opened-flag = 0
                    display 'Open or create a file first (option 1).'
                else
                    evaluate menu-choice
                        when '2' perform view-document
                        when '3' perform append-line
                        when '4' perform append-words
                        when '5' perform generate-words
                        when '6' perform replace-line
                        when '7' when '8' perform edit-word
                        when '9' perform delete-line
                        when 'S' perform save-document
                    end-evaluate
                end-if
            when other display 'Invalid choice.'
        end-evaluate
    end-perform
    display 'Goodbye.'
    stop run.

read-answer.
    move spaces to answer
    accept answer
        on exception
            display ' '
            display 'Input ended; unsaved changes were not saved.'
            stop run
    end-accept.

check-unsaved.
    move 0 to cancel-flag
    if dirty-flag = 1
        display 'Unsaved changes: S = save, D = discard, C = cancel: '
            with no advancing
        perform read-answer
        evaluate function upper-case(function trim(answer))
            when 'S'
                perform save-document
                if dirty-flag = 1 move 1 to cancel-flag end-if
            when 'D' continue
            when other move 1 to cancel-flag
        end-evaluate
    end-if.

open-document.
    perform check-unsaved
    if cancel-flag = 1 exit paragraph end-if
    display 'Text file path (up to 512 characters): ' with no advancing
    perform read-answer
    if function trim(answer) = spaces or
        function length(function trim(answer)) > 512
        display 'Invalid file path.'
        exit paragraph
    end-if
    move function trim(answer) to io-path
    open input text-file
    evaluate io-status
        when '35'
            display 'File does not exist. Create it? (Y/N): '
                with no advancing
            perform read-answer
            if function upper-case(function trim(answer)) = 'Y'
                initialize document-buffer
                move 0 to line-count
                move io-path to file-path
                move 1 to opened-flag dirty-flag
                display 'New document ready. Use S to save it.'
            end-if
            exit paragraph
        when '00' continue
        when other
            display 'Cannot open file. File status: ' io-status
            exit paragraph
    end-evaluate
    initialize pending-buffer
    move 0 to pending-count eof-flag failed-flag
    perform until eof-flag = 1 or failed-flag = 1
        read text-file
        evaluate io-status
            when '10' move 1 to eof-flag
            when '00'
                if pending-count >= max-lines or
                    function length(
                        function trim(file-line trailing)) > max-width
                    display 'File exceeds 1000 lines or 1024 '
                        'characters per line.'
                    move 1 to failed-flag
                else
                    add 1 to pending-count
                    move file-line to pending-line(pending-count)
                end-if
            when other
                display 'Cannot read file safely. File status: '
                    io-status
                move 1 to failed-flag
        end-evaluate
    end-perform
    close text-file
    if io-status not = '00'
        display 'Cannot close file. File status: ' io-status
        move 1 to failed-flag
    end-if
    if failed-flag = 0
        move pending-buffer to document-buffer
        move pending-count to line-count
        move io-path to file-path
        move 1 to opened-flag
        move 0 to dirty-flag
        display 'Opened: ' function trim(file-path)
    else
        display 'The current document has been kept.'
    end-if.

view-document.
    display 'File: ' function trim(file-path)
    if dirty-flag = 1 display '(unsaved changes)' end-if
    if line-count = 0 display '(empty file)' end-if
    perform varying idx from 1 by 1 until idx > line-count
        move idx to display-number
        display function trim(display-number) ': '
            function trim(document-line(idx) trailing)
    end-perform.

read-number.
    perform read-answer
    move 0 to valid-flag
    if function length(function trim(answer)) > 0 and <= 4
        if function trim(answer) is numeric
            compute number-value = function numval(answer)
            if number-value > 0 move 1 to valid-flag end-if
        end-if
    end-if
    if valid-flag = 0 display 'Enter a positive whole number.' end-if.

choose-line.
    move 0 to valid-flag
    if line-count = 0
        display 'The file has no lines.'
        exit paragraph
    end-if
    display 'Line number: ' with no advancing
    perform read-number
    if valid-flag = 1
        if number-value > line-count
            display 'That line does not exist.'
            move 0 to valid-flag
        else
            move number-value to line-number
        end-if
    end-if.

read-text.
    perform read-answer
    move 1 to valid-flag
    if function length(function trim(answer trailing)) > max-width
        display 'Text is too long (maximum 1024 characters).'
        move 0 to valid-flag
    else
        move answer to new-text
    end-if.

append-line.
    if line-count >= max-lines
        display 'The file already has 1000 lines.'
        exit paragraph
    end-if
    display 'New line (blank is allowed): ' with no advancing
    perform read-text
    if valid-flag = 1
        add 1 to line-count
        move new-text to document-line(line-count)
        move 1 to dirty-flag
        display 'Line appended.'
    end-if.

replace-line.
    perform choose-line
    if valid-flag = 0 exit paragraph end-if
    display 'Replacement line (blank is allowed): ' with no advancing
    perform read-text
    if valid-flag = 1
        move new-text to document-line(line-number)
        move 1 to dirty-flag
        display 'Line replaced.'
    end-if.

append-words.
    perform choose-line
    if valid-flag = 0 exit paragraph end-if
    display 'Words to append: ' with no advancing
    perform read-text
    if valid-flag = 0 exit paragraph end-if
    move function trim(new-text) to new-text
    if new-text = spaces
        display 'No words supplied.'
        exit paragraph
    end-if
    compute old-length = function length(
        function trim(document-line(line-number) trailing))
    compute new-length = function length(function trim(new-text))
    compute result-length = old-length + new-length
    if old-length > 0 add 1 to result-length end-if
    if result-length > max-width
        display 'Result is too long (maximum 1024 characters).'
        exit paragraph
    end-if
    move spaces to result-text
    move 1 to write-pos
    if old-length > 0
        string document-line(line-number)(1:old-length) ' '
            into result-text with pointer write-pos end-string
    end-if
    string new-text(1:new-length)
        into result-text with pointer write-pos end-string
    move result-text to document-line(line-number)
    move 1 to dirty-flag
    display 'Words appended.'.

generate-words.
    if line-count >= max-lines
        display 'The file already has 1000 lines.'
        exit paragraph
    end-if
    display 'How many random words? (1-50): ' with no advancing
    perform read-number
    if valid-flag = 0 exit paragraph end-if
    if number-value > 50
        display 'Choose between 1 and 50 words.'
        exit paragraph
    end-if
    move number-value to random-count
    move spaces to result-text
    move 1 to write-pos
    perform random-count times
        compute random-index =
            function integer(function random * 20) + 1
        string function trim(random-word(random-index)) ' '
            into result-text with pointer write-pos end-string
    end-perform
    add 1 to line-count
    move result-text to document-line(line-count)
    move 1 to dirty-flag
    display 'Generated: ' function trim(result-text).

edit-word.
    move menu-choice to operation-choice
    perform choose-line
    if valid-flag = 0 exit paragraph end-if
    display function trim(document-line(line-number) trailing)
    display 'Word number (words are separated by spaces): '
        with no advancing
    perform read-number
    if valid-flag = 0 exit paragraph end-if
    move number-value to word-number
    compute old-length = function length(
        function trim(document-line(line-number) trailing))
    move 1 to scan-pos
    move 0 to word-count start-pos end-pos
    perform until scan-pos > old-length or word-count = word-number
        if document-line(line-number)(scan-pos:1) = space
            add 1 to scan-pos
        else
            add 1 to word-count
            move scan-pos to start-pos
            perform until scan-pos > old-length
                if document-line(line-number)(scan-pos:1) = space
                    exit perform
                end-if
                add 1 to scan-pos
            end-perform
            move scan-pos to end-pos
        end-if
    end-perform
    if word-count not = word-number
        display 'That word does not exist.'
        exit paragraph
    end-if
    move spaces to new-text
    move 0 to new-length
    if operation-choice = '7'
        display 'Replacement word: ' with no advancing
        perform read-text
        if valid-flag = 0 exit paragraph end-if
        move function trim(new-text) to new-text
        compute new-length = function length(function trim(new-text))
        if new-length = 0
            display 'Use option 8 to delete a word.'
            exit paragraph
        end-if
        perform varying idx from 1 by 1 until idx > new-length
            if new-text(idx:1) = space or x'09'
                display 'Enter one word, without spaces or tabs.'
                exit paragraph
            end-if
        end-perform
    else
        *> Delete adjoining spaces so the remaining words join cleanly.
        if end-pos <= old-length
            perform until end-pos > old-length
                if document-line(line-number)(end-pos:1) not = space
                    exit perform
                end-if
                add 1 to end-pos
            end-perform
        else
            perform until start-pos <= 1
                compute idx = start-pos - 1
                if document-line(line-number)(idx:1) not = space
                    exit perform
                end-if
                subtract 1 from start-pos
            end-perform
        end-if
    end-if
    compute tail-length = old-length - end-pos + 1
    compute result-length = start-pos - 1 + new-length + tail-length
    if result-length > max-width
        display 'Result is too long (maximum 1024 characters).'
        exit paragraph
    end-if
    move spaces to result-text
    move 1 to write-pos
    if start-pos > 1
        compute idx = start-pos - 1
        string document-line(line-number)(1:idx)
            into result-text with pointer write-pos end-string
    end-if
    if new-length > 0
        string new-text(1:new-length)
            into result-text with pointer write-pos end-string
    end-if
    if tail-length > 0
        string document-line(line-number)(end-pos:tail-length)
            into result-text with pointer write-pos end-string
    end-if
    move result-text to document-line(line-number)
    move 1 to dirty-flag
    display 'Word updated.'.

delete-line.
    perform choose-line
    if valid-flag = 0 exit paragraph end-if
    perform varying idx from line-number by 1 until idx >= line-count
        move document-line(idx + 1) to document-line(idx)
    end-perform
    move spaces to document-line(line-count)
    subtract 1 from line-count
    move 1 to dirty-flag
    display 'Line deleted.'.

save-document.
    *> POSIX mkstemp creates a unique sibling file. Only replace the
    *> destination after all writes and CLOSE succeed.
    move low-values to c-temp-path c-file-path
    string function trim(file-path) '.tmp.XXXXXX' x'00'
        into c-temp-path end-string
    call static "mkstemp" using by reference c-temp-path
        returning c-result
    if c-result < 0
        display 'Cannot create a temporary file. Check the path '
            'and directory permissions.'
        exit paragraph
    end-if
    call static "close" using by value c-result returning c-result
    move spaces to temp-path
    unstring c-temp-path delimited by x'00' into temp-path end-unstring
    move temp-path to io-path
    move 0 to failed-flag
    open output text-file
    if io-status not = '00'
        display 'Cannot open temporary file. File status: ' io-status
        move 1 to failed-flag
    else
        perform varying idx from 1 by 1
            until idx > line-count or failed-flag = 1
            move document-line(idx) to file-line
            write file-line
            if io-status not = '00'
                display 'Cannot write file. File status: ' io-status
                move 1 to failed-flag
            end-if
        end-perform
        close text-file
        if io-status not = '00'
            display 'Cannot close saved file. File status: ' io-status
            move 1 to failed-flag
        end-if
    end-if
    if failed-flag = 0
        string function trim(file-path) x'00'
            into c-file-path end-string
        call static "rename" using
            by reference c-temp-path c-file-path returning c-result
        if c-result = 0
            move 0 to dirty-flag
            display 'Saved: ' function trim(file-path)
        else
            display 'Cannot replace destination. Changes remain unsaved.'
            move 1 to failed-flag
        end-if
    end-if
    if failed-flag = 1
        call static "unlink" using by reference c-temp-path
            returning c-result
    end-if.
