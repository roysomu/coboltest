COBC ?= cobc
COBFLAGS ?= -Wall -debug

.PHONY: all run test clean
all: file_editor

file_editor: file_editor.cob
	$(COBC) -x -free $(COBFLAGS) -o $@ $<

run: file_editor
	./file_editor

test: file_editor
	python3 tests/test_editor.py

clean:
	$(RM) file_editor
