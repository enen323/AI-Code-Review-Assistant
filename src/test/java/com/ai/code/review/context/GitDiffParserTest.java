package com.ai.code.review.context;

import com.ai.code.review.model.DiffBlock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for GitDiffParser with real unified diff format.
 */
class GitDiffParserTest {

    private GitDiffParser parser;

    @BeforeEach
    void setUp() {
        parser = new GitDiffParser();
    }

    /**
     * Tests parsing a simple diff with a single hunk adding new lines.
     */
    @Test
    void testParseSimpleAdditionDiff() {
        String diff = """
                @@ -1,3 +1,6 @@
                 line1
                +new line
                +another new line
                 line2
                 line3
                """;

        List<DiffBlock> blocks = parser.parseDiff("Test.java", diff);

        assertEquals(1, blocks.size());
        DiffBlock block = blocks.get(0);
        assertEquals("Test.java", block.filePath());

        // Note: oldStart may default because the header says old start is line 1
        assertEquals(1, block.oldStartLine());
        assertEquals(1, block.newStartLine());

        assertEquals(3, block.oldLines());
        assertEquals(6, block.newLines());

        assertEquals(5, block.lines().size());
        assertEquals(" line1", block.lines().get(0));
        assertEquals("+new line", block.lines().get(1));
        assertEquals("+another new line", block.lines().get(2));
        assertEquals(" line2", block.lines().get(3));
        assertEquals(" line3", block.lines().get(4));
    }

    /**
     * Tests parsing a diff with deletions.
     */
    @Test
    void testParseDeletionDiff() {
        String diff = """
                @@ -10,7 +10,5 @@
                 context
                -deleted line 1
                -deleted line 2
                 keep
                 still here
                -removed
                 end
                """;

        List<DiffBlock> blocks = parser.parseDiff("Test.java", diff);

        assertEquals(1, blocks.size());
        DiffBlock block = blocks.get(0);

        assertEquals(10, block.oldStartLine());
        assertEquals(10, block.newStartLine());
        assertEquals(7, block.oldLines());
        assertEquals(5, block.newLines());

        assertEquals(7, block.lines().size());
    }

    /**
     * Tests parsing a diff with multiple hunks.
     */
    @Test
    void testParseMultipleHunks() {
        String diff = """
                @@ -1,4 +1,5 @@
                 first
                +inserted
                 second
                 third
                 fourth
                @@ -20,3 +21,6 @@
                 old code
                +new code block
                +more new code
                +even more
                 unchanged
                """;

        List<DiffBlock> blocks = parser.parseDiff("MultiHunk.java", diff);

        assertEquals(2, blocks.size());

        // First hunk
        DiffBlock hunk1 = blocks.get(0);
        assertEquals(1, hunk1.oldStartLine());
        assertEquals(1, hunk1.newStartLine());
        assertEquals(4, hunk1.oldLines());
        assertEquals(5, hunk1.newLines());
        assertEquals("@@ -1,4 +1,5 @@", hunk1.header());

        // Second hunk
        DiffBlock hunk2 = blocks.get(1);
        assertEquals(20, hunk2.oldStartLine());
        assertEquals(21, hunk2.newStartLine());
        assertEquals(3, hunk2.oldLines());
        assertEquals(6, hunk2.newLines());
        assertEquals("@@ -20,3 +21,6 @@", hunk2.header());
    }

    /**
     * Tests parsing a diff with a single-line context (no comma in counts).
     */
    @Test
    void testParseSingleLineHunk() {
        String diff = """
                @@ -1 +1,2 @@
                 single line
                +added line
                """;

        List<DiffBlock> blocks = parser.parseDiff("Single.java", diff);

        assertEquals(1, blocks.size());
        DiffBlock block = blocks.get(0);
        assertEquals(1, block.oldStartLine());
        assertEquals(1, block.newStartLine());
        assertEquals(1, block.oldLines());
        assertEquals(2, block.newLines());
    }

    /**
     * Tests parsing a diff where new file starts from line 0.
     */
    @Test
    void testParseNewFileDiff() {
        String diff = """
                @@ -0,0 +1,4 @@
                +new file line 1
                +new file line 2
                +new file line 3
                +new file line 4
                """;

        List<DiffBlock> blocks = parser.parseDiff("NewFile.java", diff);

        assertEquals(1, blocks.size());
        DiffBlock block = blocks.get(0);
        assertEquals(0, block.oldStartLine());
        assertEquals(1, block.newStartLine());
        assertEquals(0, block.oldLines());
        assertEquals(4, block.newLines());
        assertEquals(4, block.lines().size());
    }

    /**
     * Tests parsing an empty diff returns empty list.
     */
    @Test
    void testParseEmptyDiff() {
        List<DiffBlock> blocks = parser.parseDiff("Empty.java", "");
        assertTrue(blocks.isEmpty());

        blocks = parser.parseDiff("Empty.java", null);
        assertTrue(blocks.isEmpty());
    }

    /**
     * Tests parsing a real-world git diff with file header lines.
     */
    @Test
    void testParseRealWorldDiff() {
        String diff = """
                diff --git a/src/main/java/com/example/Service.java b/src/main/java/com/example/Service.java
                index 1234567..abcdef0 100644
                --- a/src/main/java/com/example/Service.java
                +++ b/src/main/java/com/example/Service.java
                @@ -15,6 +15,8 @@ public class Service {
                     private final Repository repository;

                     public Result process(Input input) {
                +        log.debug("Processing input: {}", input);
                +
                         ValidationResult validation = validate(input);
                         if (!validation.isValid()) {
                             return Result.invalid(validation);
                @@ -45,8 +47,9 @@ public class Service {
                     private ValidationResult validate(Input input) {
                         if (input == null) {
                -            throw new IllegalArgumentException("Input must not be null");
                +            log.warn("Null input received");
                +            return ValidationResult.invalid("Input must not be null");
                         }
                -        return ValidationResult.valid();
                +        return ValidationResult.valid(input.sanitize());
                     }
                 }
                """;

        List<DiffBlock> blocks = parser.parseDiff("src/main/java/com/example/Service.java", diff);

        assertEquals(2, blocks.size());

        // First hunk
        DiffBlock hunk1 = blocks.get(0);
        assertEquals(15, hunk1.oldStartLine());
        assertEquals(15, hunk1.newStartLine());
        assertEquals(6, hunk1.oldLines());
        assertEquals(8, hunk1.newLines());

        // Second hunk
        DiffBlock hunk2 = blocks.get(1);
        assertEquals(45, hunk2.oldStartLine());
        assertEquals(47, hunk2.newStartLine());
        assertEquals(8, hunk2.oldLines());
        assertEquals(9, hunk2.newLines());
    }
}
