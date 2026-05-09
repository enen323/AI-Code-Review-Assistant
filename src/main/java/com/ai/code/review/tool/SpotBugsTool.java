package com.ai.code.review.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MVP-quality static analysis tool for detecting common bug patterns.
 *
 * Uses regex-based pattern matching for quick static analysis.
 * Full SpotBugs process execution will be integrated in a later phase.
 */
@Component
public class SpotBugsTool {

    private static final Logger log = LoggerFactory.getLogger(SpotBugsTool.class);

    /**
     * A detected bug pattern within source code.
     *
     * @param filePath    the source file path
     * @param lineNumber  the line number where the pattern was detected
     * @param pattern     the bug pattern identifier
     * @param description human-readable description of the issue
     */
    public record BugPattern(
            String filePath,
            int lineNumber,
            String pattern,
            String description
    ) {}

    // Pattern: empty catch block: catch (...) { }
    private static final Pattern EMPTY_CATCH_BLOCK = Pattern.compile(
            "catch\\s*\\([^)]*\\)\\s*\\{\\s*\\}",
            Pattern.DOTALL
    );

    // Pattern: catch block with only whitespace/comment
    private static final Pattern EMPTY_CATCH_BODY = Pattern.compile(
            "catch\\s*\\([^)]*\\)\\s*\\{\\s*(//[^\n]*)?\\s*\\}",
            Pattern.DOTALL
    );

    // Pattern: String comparison with ==
    private static final Pattern STRING_EQ_COMPARISON = Pattern.compile(
            "\"[^\"]*\"\\s*==\\s*\\w+|\\w+\\s*==\\s*\"[^\"]*\""
    );

    // Pattern: resource not closed (FileInputStream/FileOutputStream without try-with-resources)
    private static final Pattern RESOURCE_OPEN = Pattern.compile(
            "new\\s+(FileInputStream|FileOutputStream|FileReader|FileWriter|BufferedReader|BufferedWriter)\\s*\\("
    );

    // Pattern: print stack trace (not real error handling)
    private static final Pattern PRINT_STACK_TRACE = Pattern.compile(
            "\\.printStackTrace\\(\\)"
    );

    // Pattern: System.out or System.err usage in production code
    private static final Pattern SYSTEM_OUT = Pattern.compile(
            "System\\.(out|err)\\s*\\.\\s*(print|println|printf)"
    );

    // Pattern: TODO or FIXME comments
    private static final Pattern TODO_FIXME = Pattern.compile(
            "//\\s*(TODO|FIXME|HACK|XXX)\\b"
    );

    // Pattern: return statement with a value expression in diff content (+/-/space prefix)
    private static final Pattern RETURN_WITH_VALUE = Pattern.compile(
            "^[+\\- ]?\\s*return\\s+([^;]+)\\s*;\\s*$",
            Pattern.MULTILINE
    );

    // Pattern: void method declaration in diff content (+/-/space prefix)
    private static final Pattern VOID_METHOD_DECL = Pattern.compile(
            "[+\\- ]?(?:public|private|protected|static|\\s)\\s+void\\s+\\w+\\s*\\(",
            Pattern.MULTILINE
    );

    /**
     * Analyzes the given code content for common bug patterns.
     *
     * @param filePath    the source file path (for context in results)
     * @param codeContent the source code content to analyze
     * @return list of detected bug patterns
     */
    public List<BugPattern> analyze(String filePath, String codeContent) {
        List<BugPattern> findings = new ArrayList<>();

        if (codeContent == null || codeContent.isBlank()) {
            return findings;
        }

        String[] lines = codeContent.split("\n", -1);

        detectEmptyCatchBlocks(filePath, lines, findings);
        detectStringEqComparison(filePath, lines, findings);
        detectPrintStackTrace(filePath, lines, findings);
        detectSystemOutUsage(filePath, lines, findings);
        detectTodoFixes(filePath, lines, findings);
        detectResourceLeaks(filePath, codeContent, lines, findings);
        detectVoidMethodReturnValue(filePath, codeContent, lines, findings);

        log.debug("SpotBugs analysis for {}: {} findings", filePath, findings.size());
        return findings;
    }

    private void detectEmptyCatchBlocks(String filePath, String[] lines, List<BugPattern> findings) {
        StringBuilder chunk = new StringBuilder();
        int chunkStartLine = 0;

        for (int i = 0; i < lines.length; i++) {
            chunk.append(lines[i]).append("\n");
            String chunkStr = chunk.toString();

            Matcher catchMatcher = EMPTY_CATCH_BLOCK.matcher(chunkStr);
            while (catchMatcher.find()) {
                findings.add(new BugPattern(
                        filePath,
                        chunkStartLine + countLines(chunkStr.substring(0, catchMatcher.start())),
                        "EMPTY_CATCH_BLOCK",
                        "Empty catch block detected. Empty catch blocks silently swallow exceptions, " +
                                "making debugging difficult and potentially hiding critical failures."
                ));
            }
        }
    }

    private void detectStringEqComparison(String filePath, String[] lines, List<BugPattern> findings) {
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];

            // Skip comments
            if (line.trim().startsWith("//") || line.trim().startsWith("*") || line.trim().startsWith("/*")) {
                continue;
            }

            Matcher matcher = STRING_EQ_COMPARISON.matcher(line);
            if (matcher.find()) {
                findings.add(new BugPattern(
                        filePath,
                        i + 1,
                        "STRING_COMPARISON_USING_EQ",
                        "String comparison using == instead of .equals() at line " + (i + 1) +
                                ". In Java, == compares object references, not string content."
                ));
            }
        }
    }

    private void detectPrintStackTrace(String filePath, String[] lines, List<BugPattern> findings) {
        for (int i = 0; i < lines.length; i++) {
            if (PRINT_STACK_TRACE.matcher(lines[i]).find()) {
                findings.add(new BugPattern(
                        filePath,
                        i + 1,
                        "PRINT_STACK_TRACE",
                        "printStackTrace() should not be used in production code. " +
                                "Use a logging framework instead."
                ));
            }
        }
    }

    private void detectSystemOutUsage(String filePath, String[] lines, List<BugPattern> findings) {
        for (int i = 0; i < lines.length; i++) {
            if (SYSTEM_OUT.matcher(lines[i]).find()) {
                findings.add(new BugPattern(
                        filePath,
                        i + 1,
                        "SYSTEM_OUT_USAGE",
                        "System.out/err usage detected. Use a logging framework " +
                                "(SLF4J, Logback) for production code."
                ));
            }
        }
    }

    private void detectTodoFixes(String filePath, String[] lines, List<BugPattern> findings) {
        for (int i = 0; i < lines.length; i++) {
            Matcher matcher = TODO_FIXME.matcher(lines[i]);
            if (matcher.find()) {
                findings.add(new BugPattern(
                        filePath,
                        i + 1,
                        "TODO_COMMENT",
                        matcher.group(1) + " comment found at line " + (i + 1) +
                                ". Incomplete code or known issue should be addressed before merge."
                ));
            }
        }
    }

    private void detectVoidMethodReturnValue(String filePath, String codeContent, String[] lines, List<BugPattern> findings) {
        Matcher returnMatcher = RETURN_WITH_VALUE.matcher(codeContent);
        while (returnMatcher.find()) {
            int returnPos = returnMatcher.start();
            int returnLine = getLineNumber(lines, returnPos);

            // Look backwards from return to find enclosing method declaration
            String beforeReturn = codeContent.substring(0, returnPos);
            Matcher methodMatcher = VOID_METHOD_DECL.matcher(beforeReturn);
            String lastVoidMethod = null;
            int lastVoidPos = -1;
            while (methodMatcher.find()) {
                lastVoidMethod = methodMatcher.group();
                lastVoidPos = methodMatcher.start();
            }

            if (lastVoidMethod != null) {
                // Check return is not inside a nested class/lambda between lastVoidPos and returnPos
                String body = beforeReturn.substring(lastVoidPos);
                // Count brace depth from method start
                int braceDepth = 0;
                for (int i = 0; i < body.length(); i++) {
                    char c = body.charAt(i);
                    if (c == '{') braceDepth++;
                    else if (c == '}') braceDepth--;
                    // If brace depth returns to 0 before our return, we're past the method
                }
                if (braceDepth > 0) {
                    String returnValue = returnMatcher.group(1).trim();
                    // Truncate long expressions for readable message
                    if (returnValue.length() > 60) returnValue = returnValue.substring(0, 57) + "...";
                    findings.add(new BugPattern(
                            filePath,
                            returnLine,
                            "RETURN_IN_VOID_METHOD",
                            "Return with value in void method at line " + returnLine
                                    + ": 'return " + returnValue + ";'. Void methods cannot return a value."
                    ));
                }
            }
        }
    }

    private void detectResourceLeaks(String filePath, String codeContent, String[] lines, List<BugPattern> findings) {
        Matcher resourceMatcher = RESOURCE_OPEN.matcher(codeContent);
        while (resourceMatcher.find()) {
            int pos = resourceMatcher.start();
            int lineNum = getLineNumber(lines, pos);

            // Check if it's inside a try-with-resources
            if (!isInsideTryWithResources(codeContent, pos)) {
                findings.add(new BugPattern(
                        filePath,
                        lineNum,
                        "RESOURCE_LEAK",
                        "Resource opened without try-with-resources at line " + lineNum +
                                ". Resources should be managed with try-with-resources to ensure proper closure."
                ));
            }
        }
    }

    private int getLineNumber(String[] lines, int position) {
        int charCount = 0;
        for (int i = 0; i < lines.length; i++) {
            charCount += lines[i].length() + 1; // +1 for newline
            if (charCount > position) {
                return i + 1;
            }
        }
        return lines.length;
    }

    private int countLines(String str) {
        if (str.isEmpty()) return 0;
        return str.split("\n", -1).length - 1; // -1 because split always returns at least 1
    }

    private boolean isInsideTryWithResources(String content, int position) {
        // Look backwards from position for "try" keyword
        int searchStart = Math.max(0, position - 200);
        String before = content.substring(searchStart, position);

        // Check if there's a "try" followed by "(" before the resource
        int lastTry = before.lastIndexOf("try");
        if (lastTry < 0) return false;

        String afterTry = before.substring(lastTry);
        return afterTry.matches("try\\s*\\([^)]*$");
    }
}
