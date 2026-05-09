package com.ai.code.review.tool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for CheckstyleTool verifying pattern detection capabilities.
 */
class CheckstyleToolTest {

    private CheckstyleTool tool;

    @BeforeEach
    void setUp() {
        tool = new CheckstyleTool();
    }

    /**
     * Tests detection of public methods missing Javadoc.
     */
    @Test
    void testMissingJavadocOnPublicMethod() {
        String code = """
                public class TestClass {
                    public void doSomething() {
                    }
                }
                """;
        List<CheckstyleTool.CodeStyleIssue> results = tool.analyze("Test.java", code);
        assertTrue(results.stream().anyMatch(p -> p.rule().equals("JavadocStyle")),
                "Should detect missing Javadoc on public method");
    }

    /**
     * Tests that public methods with Javadoc are not flagged.
     */
    @Test
    void testPublicMethodWithJavadocNotFlagged() {
        String code = """
                public class TestClass {
                    /**
                     * Does something.
                     */
                    public void doSomething() {
                    }
                }
                """;
        List<CheckstyleTool.CodeStyleIssue> results = tool.analyze("Test.java", code);
        assertTrue(results.stream().noneMatch(p -> p.rule().equals("JavadocStyle")),
                "Should not flag methods with Javadoc");
    }

    /**
     * Tests detection of overly long methods (>50 lines).
     */
    @Test
    void testLongMethodDetection() {
        StringBuilder code = new StringBuilder();
        code.append("public class TestClass {\n");
        code.append("    public void longMethod() {\n");
        for (int i = 0; i < 55; i++) {
            code.append("        System.out.println(\"line ").append(i).append("\");\n");
        }
        code.append("    }\n");
        code.append("}\n");

        List<CheckstyleTool.CodeStyleIssue> results = tool.analyze("Test.java", code.toString());
        assertTrue(results.stream().anyMatch(p -> p.rule().equals("MethodLength")),
                "Should detect long methods (>50 lines)");
    }

    /**
     * Tests that short methods are not flagged.
     */
    @Test
    void testShortMethodNotFlagged() {
        String code = """
                public class TestClass {
                    public void shortMethod() {
                        System.out.println("Hello");
                    }
                }
                """;
        List<CheckstyleTool.CodeStyleIssue> results = tool.analyze("Test.java", code);
        assertTrue(results.stream().noneMatch(p -> p.rule().equals("MethodLength")),
                "Should not flag short methods");
    }

    /**
     * Tests detection of too many parameters (>5).
     */
    @Test
    void testTooManyParameters() {
        String code = """
                public class TestClass {
                    public void process(String a, String b, String c, String d, String e, String f) {
                    }
                }
                """;
        List<CheckstyleTool.CodeStyleIssue> results = tool.analyze("Test.java", code);
        assertTrue(results.stream().anyMatch(p -> p.rule().equals("ParameterNumber")),
                "Should detect methods with too many parameters");
    }

    /**
     * Tests that methods with few parameters are not flagged.
     */
    @Test
    void testFewParametersNotFlagged() {
        String code = """
                public class TestClass {
                    public void process(String a, String b) {
                    }
                }
                """;
        List<CheckstyleTool.CodeStyleIssue> results = tool.analyze("Test.java", code);
        assertTrue(results.stream().noneMatch(p -> p.rule().equals("ParameterNumber")),
                "Should not flag methods with few parameters");
    }

    /**
     * Tests detection of magic numbers.
     */
    @Test
    void testMagicNumberDetection() {
        String code = """
                public class TestClass {
                    public void calculate() {
                        int result = 42 * 100;
                    }
                }
                """;
        List<CheckstyleTool.CodeStyleIssue> results = tool.analyze("Test.java", code);
        assertTrue(results.stream().anyMatch(p -> p.rule().equals("MagicNumber")),
                "Should detect magic numbers");
    }

    /**
     * Tests that allowed numeric literals are not flagged.
     */
    @Test
    void testAllowedNumbersNotFlagged() {
        String code = """
                public class TestClass {
                    public void init() {
                        int zero = 0;
                        int one = 1;
                        int negOne = -1;
                    }
                }
                """;
        List<CheckstyleTool.CodeStyleIssue> results = tool.analyze("Test.java", code);
        assertTrue(results.stream().noneMatch(p -> p.rule().equals("MagicNumber")),
                "Should not flag allowed numbers (0, 1, -1)");
    }

    /**
     * Tests detection of empty if blocks.
     */
    @Test
    void testEmptyIfBlock() {
        String code = """
                public class TestClass {
                    public void check(boolean flag) {
                        if (flag) {
                        }
                    }
                }
                """;
        List<CheckstyleTool.CodeStyleIssue> results = tool.analyze("Test.java", code);
        assertTrue(results.stream().anyMatch(p -> p.rule().equals("EmptyBlock")),
                "Should detect empty if blocks");
    }

    /**
     * Tests detection of empty else blocks.
     */
    @Test
    void testEmptyElseBlock() {
        String code = """
                public class TestClass {
                    public void check(boolean flag) {
                        if (flag) {
                            doSomething();
                        } else {
                        }
                    }
                    private void doSomething() {}
                }
                """;
        List<CheckstyleTool.CodeStyleIssue> results = tool.analyze("Test.java", code);
        assertTrue(results.stream().anyMatch(p -> p.rule().equals("EmptyBlock")),
                "Should detect empty else blocks");
    }

    /**
     * Tests detection of inconsistent indentation (mixed tabs and spaces).
     */
    @Test
    void testInconsistentIndentation() {
        String code = "public class TestClass {\n"
                + "\t    public void method() {\n"
                + "    }\n"
                + "}\n";
        List<CheckstyleTool.CodeStyleIssue> results = tool.analyze("Test.java", code);
        assertTrue(results.stream().anyMatch(p -> p.rule().equals("Indentation")),
                "Should detect inconsistent indentation");
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
     * Tests that CodeStyleIssue record contains correct information.
     */
    @Test
    void testCodeStyleIssueRecordContainsCorrectData() {
        String code = """
                public class TestClass {
                    public void doSomething() {
                    }
                }
                """;
        List<CheckstyleTool.CodeStyleIssue> results = tool.analyze("Test.java", code);

        assertFalse(results.isEmpty());
        CheckstyleTool.CodeStyleIssue issue = results.getFirst();

        assertEquals("Test.java", issue.filePath());
        assertTrue(issue.lineNumber() > 0);
        assertTrue(issue.rule().equals("JavadocStyle"));
        assertFalse(issue.description().isBlank());
    }

    /**
     * Tests multiple pattern types in the same code block.
     */
    @Test
    void testMultiplePatternsInSameCode() {
        String code = """
                public class TestClass {
                    public void process() {
                        int value = 100;
                        if (value > 50) {
                        }
                    }
                }
                """;
        List<CheckstyleTool.CodeStyleIssue> results = tool.analyze("Test.java", code);

        boolean hasMissingJavadoc = results.stream().anyMatch(p -> p.rule().equals("JavadocStyle"));
        boolean hasMagicNumber = results.stream().anyMatch(p -> p.rule().equals("MagicNumber"));
        boolean hasEmptyBlock = results.stream().anyMatch(p -> p.rule().equals("EmptyBlock"));

        assertTrue(hasMissingJavadoc, "Should detect missing Javadoc");
        assertTrue(hasMagicNumber, "Should detect magic numbers");
        assertTrue(hasEmptyBlock, "Should detect empty blocks");
    }

    /**
     * Tests that clean code produces no findings.
     */
    @Test
    void testCleanCodeReturnsNoFindings() {
        String code = """
                /**
                 * A simple calculator.
                 */
                public class Calculator {
                    /**
                     * Adds two numbers.
                     */
                    public int add(int a, int b) {
                        return a + b;
                    }
                }
                """;
        List<CheckstyleTool.CodeStyleIssue> results = tool.analyze("Test.java", code);
        assertTrue(results.isEmpty(), "Clean code should produce no findings");
    }
}
