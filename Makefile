MVN ?= mvn
JAVA ?= java
JAR := target/file-editor.jar
SOURCES := $(wildcard src/main/java/com/example/fileeditor/*.java)

.PHONY: all run test clean
all: $(JAR)

$(JAR): pom.xml $(SOURCES)
	$(MVN) -q -B -DskipTests package

run: $(JAR)
	$(JAVA) -jar $(JAR)

test: $(JAR)
	python3 tests/test_editor.py

clean:
	$(MVN) -q -B clean
