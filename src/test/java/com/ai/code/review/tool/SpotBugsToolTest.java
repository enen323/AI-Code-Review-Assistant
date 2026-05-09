package com.ai.code.review.tool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SpotBugsTool verifying pattern detection capabilities.
 */
class SpotBugsToolTest {

    private SpotBugsTool tool;

    @BeforeEach
    void setUp() {
        tool = new SpotBugsTool();
    }

    /**
     * Tests detection of empty catch blocks.
     */
    @Test
    void testDetectEmptyCatchBlock() {
        String code = """
                public void doSomething() {
                    try {
                        riskyOperation();
                    } catch (Exception e) {
                    }
                }
                """;
        List<SpotBugsTool.BugPattern> results = tool.analyze("Test.java", code);
        assertTrue(results.stream().anyMatch(p -> p.pattern().equals("EMPTY_CATCH_BLOCK")),
                "Should detect empty catch block");
    }

    /**
     * Tests detection of string comparison using == instead of .equals().
     */
    @Test
    void testDetectStringEqComparison() {
        String code = """
                public void check(String input) {
                    if (input == "test") {
                        System.out.println("matched");
                    }
                }
                """;
        List<SpotBugsTool.BugPattern> results = tool.analyze("Test.java", code);
        assertTrue(results.stream().anyMatch(p -> p.pattern().equals("STRING_COMPARISON_USING_EQ")),
                "Should detect string == comparison");
    }

    /**
     * Tests detection of printStackTrace() usage.
     */
    @Test
    void testDetectPrintStackTrace() {
        String code = """
                try {
                    riskyOperation();
                } catch (Exception e) {
                    e.printStackTrace();
                }
                """;
        List<SpotBugsTool.BugPattern> results = tool.analyze("Test.java", code);
        assertTrue(results.stream().anyMatch(p -> p.pattern().equals("PRINT_STACK_TRACE")),
                "Should detect printStackTrace usage");
    }

    /**
     * Tests detection of System.out usage.
     */
    @Test
    void testDetectSystemOutUsage() {
        String code = """
                public void logMessage(String msg) {
                    System.out.println("Message: " + msg);
                }
                """;
        List<SpotBugsTool.BugPattern> results = tool.analyze("Test.java", code);
        assertTrue(results.stream().anyMatch(p -> p.pattern().equals("SYSTEM_OUT_USAGE")),
                "Should detect System.out usage");
    }

    /**
     * Tests detection of TODO comments.
     */
    @Test
    void testDetectTodoComment() {
        String code = """
                // TODO: implement error handling
                public void process() {
                    // FIXME: this is a temporary fix
                }
                """;
        List<SpotBugsTool.BugPattern> results = tool.analyze("Test.java", code);
        assertTrue(results.stream().anyMatch(p -> p.pattern().equals("TODO_COMMENT")),
                "Should detect TODO/FIXME comments");
    }

    /**
     * Tests detection of resource leaks (FileInputStream without try-with-resources).
     */
    @Test
    void testDetectResourceLeak() {
        String code = """
                public void readFile(String path) throws IOException {
                    FileInputStream fis = new FileInputStream(path);
                    int data = fis.read();
                    fis.close();
                }
                """;
        List<SpotBugsTool.BugPattern> results = tool.analyze("Test.java", code);
        assertTrue(results.stream().anyMatch(p -> p.pattern().equals("RESOURCE_LEAK")),
                "Should detect resource leak");
    }

    /**
     * Tests that resource opening inside try-with-resources is NOT flagged.
     */
    @Test
    void testTryWithResourcesNotFlagged() {
        String code = """
                public void readFile(String path) throws IOException {
                    try (FileInputStream fis = new FileInputStream(path)) {
                        int data = fis.read();
                    }
                }
                """;
        List<SpotBugsTool.BugPattern> results = tool.analyze("Test.java", code);
        assertTrue(results.stream().noneMatch(p -> p.pattern().equals("RESOURCE_LEAK")),
                "Should not flag try-with-resources");
    }

    /**
     * Tests multiple pattern types in the same code block.
     */
    @Test
    void testMultiplePatternsInSameCode() {
        String code = """
                public void processData(String input) {
                    try {
                        if (input == "test") {
                            // TODO: handle this properly
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
                """;
        List<SpotBugsTool.BugPattern> results = tool.analyze("Test.java", code);

        assertTrue(results.stream().anyMatch(p -> p.pattern().equals("STRING_COMPARISON_USING_EQ")));
        assertTrue(results.stream().anyMatch(p -> p.pattern().equals("TODO_COMMENT")));
        assertTrue(results.stream().anyMatch(p -> p.pattern().equals("PRINT_STACK_TRACE")));
    }

    /**
     * Tests that clean code produces no findings.
     */
    @Test
    void testCleanCodeReturnsNoFindings() {
        String code = """
                public class Calculator {
                    public int add(int a, int b) {
                        return a + b;
                    }

                    public int divide(int a, int b) {
                        if (b == 0) {
                            throw new IllegalArgumentException("Division by zero");
                        }
                        return a / b;
                    }
                }
                """;
        List<SpotBugsTool.BugPattern> results = tool.analyze("Test.java", code);
        assertTrue(results.isEmpty(), "Clean code should produce no findings");
    }

    /**
     * Tests that null or empty input returns empty list.
     */
    @Test
    void testEmptyCodeReturnsNoFindings() {
        assertTrue(tool.analyze("Test.java", "").isEmpty());
        assertTrue(tool.analyze("Test.java", null).isEmpty());
    }

    /**
     * Tests that BugPattern record contains correct information.
     */
    @Test
    void testBugPatternRecordContainsCorrectData() {
        String code = """
                try {
                    doSomething();
                } catch (Exception e) {
                }
                """;
        List<SpotBugsTool.BugPattern> results = tool.analyze("Test.java", code);

        assertFalse(results.isEmpty());
        SpotBugsTool.BugPattern pattern = results.getFirst();

        assertEquals("Test.java", pattern.filePath());
        assertTrue(pattern.lineNumber() > 0);
        assertEquals("EMPTY_CATCH_BLOCK", pattern.pattern());
        assertFalse(pattern.description().isBlank());
    }

    /**
     * Tests detection of return with value in void method (raw source format).
     */
    @Test
    void testDetectReturnInVoidMethod() {
        String code = """
                public class TestClass {
                    public void doSomething() {
                        return 42;
                    }
                }
                """;
        List<SpotBugsTool.BugPattern> results = tool.analyze("Test.java", code);
        assertTrue(results.stream().anyMatch(p -> p.pattern().equals("RETURN_IN_VOID_METHOD")),
                "Should detect return with value in void method");
    }

    /**
     * Tests detection of return with value in void method (diff format with + prefix).
     */
    @Test
    void testDetectReturnInVoidMethodDiffFormat() {
        String code = """
                +public class TestClass {
                +    public void doSomething() {
                +        return 42;
                +    }
                +}
                """;
        List<SpotBugsTool.BugPattern> results = tool.analyze("Test.java", code);
        assertTrue(results.stream().anyMatch(p -> p.pattern().equals("RETURN_IN_VOID_METHOD")),
                "Should detect return with value in void method from diff content");
    }

    /**
     * Tests that return with value in non-void method is NOT flagged.
     */
    @Test
    void testReturnInNonVoidMethodNotFlagged() {
        String code = """
                public class Calculator {
                    public int add(int a, int b) {
                        return a + b;
                    }
                }
                """;
        List<SpotBugsTool.BugPattern> results = tool.analyze("Test.java", code);
        assertTrue(results.stream().noneMatch(p -> p.pattern().equals("RETURN_IN_VOID_METHOD")),
                "Should not flag return in non-void method");
    }

    /**
     * Tests that SpotBugsTool handles code with System.err usage.
     */
    @Test
    void testDetectSystemErrUsage() {
        String code = """
                public void logError(String msg) {
                    System.err.println("Error: " + msg);
                }
                """;
        List<SpotBugsTool.BugPattern> results = tool.analyze("Test.java", code);
        assertTrue(results.stream().anyMatch(p -> p.pattern().equals("SYSTEM_OUT_USAGE")),
                "Should detect System.err usage");
    }

    /**
     * Tests detection of HACK and XXX comments.
     */
    @Test
    void testDetectHackAndXxxComments() {
        String code = """
                // HACK: quick workaround for performance
                public void quickFix() {
                    // XXX: need to revisit this logic
                }
                """;
        List<SpotBugsTool.BugPattern> results = tool.analyze("Test.java", code);
        assertEquals(2, results.stream().filter(p -> p.pattern().equals("TODO_COMMENT")).count(),
                "Should detect both HACK and XXX comments");
    }
}
