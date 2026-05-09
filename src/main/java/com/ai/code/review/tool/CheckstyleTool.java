package com.ai.code.review.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MVP-quality static analysis tool for detecting code style issues.
 *
 * Uses regex-based pattern matching for quick static analysis of common
 * code style violations. Full Checkstyle process execution will be
 * integrated in a later phase.
 */
@Component
public class CheckstyleTool {

    private static final Logger log = LoggerFactory.getLogger(CheckstyleTool.class);

    /**
     * A detected code style issue within source code.
     *
     * @param filePath    the source file path
     * @param lineNumber  the line number where the issue was detected
     * @param rule        the Checkstyle rule identifier
     * @param description human-readable description of the issue
     */
    public record CodeStyleIssue(
            String filePath,
            int lineNumber,
            String rule,
            String description
    ) {}

    // Pattern: public method declaration
    private static final Pattern PUBLIC_METHOD = Pattern.compile(
            "public\\s+\\w+\\s+\\w+\\s*\\("
    );

    // Pattern: long method detection (method with more than ~50 lines inside)
    // We'll use line counting instead

    // Pattern: method declaration with parameters
    private static final Pattern METHOD_DECLARATION = Pattern.compile(
            "\\bpublic\\s+\\w+\\s+\\w+\\s*\\([^)]*\\)"
    );

    // Pattern: magic numbers (numeric literals other than 0, 1, -1, common constants)
    private static final Pattern MAGIC_NUMBER = Pattern.compile(
            "[\\s(=,]+(\\d{2,}|\\d+\\.\\d+)(?!\\s*[\\.\\w])"
    );

    // Pattern: empty if block: if (...) { }
    private static final Pattern EMPTY_IF_BLOCK = Pattern.compile(
            "if\\s*\\([^)]*\\)\\s*\\{\\s*\\}",
            Pattern.DOTALL
    );

    // Pattern: empty else block: else { }
    private static final Pattern EMPTY_ELSE_BLOCK = Pattern.compile(
            "else\\s*\\{\\s*\\}",
            Pattern.DOTALL
    );

    // Pattern: inconsistent indentation (tabs vs spaces mixed)
    private static final Pattern MIXED_INDENT = Pattern.compile(
            "^(?=\\t+\\s| +\\t).*\\S",
            Pattern.MULTILINE
    );

    /**
     * Analyzes the given code content for code style issues.
     *
     * @param filePath    the source file path (for context in results)
     * @param codeContent the source code content to analyze
     * @return list of detected code style issues
     */
    public List<CodeStyleIssue> analyze(String filePath, String codeContent) {
        List<CodeStyleIssue> findings = new ArrayList<>();

        if (codeContent == null || codeContent.isBlank()) {
            return findings;
        }

        String[] lines = codeContent.split("\n", -1);

        detectMissingJavadoc(filePath, lines, findings);
        detectLongMethods(filePath, lines, findings);
        detectTooManyParameters(filePath, lines, findings);
        detectMagicNumbers(filePath, lines, findings);
        detectEmptyIfElseBlocks(filePath, lines, findings);
        detectInconsistentIndentation(filePath, lines, findings);

        log.debug("Checkstyle analysis for {}: {} findings", filePath, findings.size());
        return findings;
    }

    /**
     * Detects public methods that lack Javadoc comments.
     */
    private void detectMissingJavadoc(String filePath, String[] lines, List<CodeStyleIssue> findings) {
        boolean insideJavadoc = false;

        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();

            // Track whether we're inside a Javadoc comment
            if (trimmed.startsWith("/**")) {
                insideJavadoc = true;
                continue;
            }
            if (insideJavadoc && trimmed.contains("*/")) {
                insideJavadoc = false;
                continue;
            }
            if (insideJavadoc) {
                continue;
            }

            // Skip comments and annotations
            if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("@Override")
                    || trimmed.isEmpty() || trimmed.startsWith("package") || trimmed.startsWith("import")
                    || trimmed.startsWith("{")) {
                continue;
            }

            // Check for public method declaration
            Matcher methodMatcher = PUBLIC_METHOD.matcher(trimmed);
            if (methodMatcher.find()) {
                // Check if there's a Javadoc in the preceding lines (skip annotations)
                boolean hasJavadoc = false;
                for (int j = i - 1; j >= Math.max(0, i - 5); j--) {
                    String prevLine = lines[j].trim();
                    if (prevLine.startsWith("/**")) {
                        hasJavadoc = true;
                        break;
                    }
                    // Stop if we hit another method or class declaration
                    if (prevLine.startsWith("public") || prevLine.startsWith("private")
                            || prevLine.startsWith("protected") || prevLine.startsWith("class")
                            || prevLine.startsWith("interface") || prevLine.startsWith("@interface")
                            || prevLine.startsWith("enum")) {
                        break;
                    }
                }

                if (!hasJavadoc) {
                    findings.add(new CodeStyleIssue(
                            filePath,
                            i + 1,
                            "JavadocStyle",
                            "Public method '" + extractMethodName(trimmed)
                                    + "' at line " + (i + 1) + " is missing Javadoc comment."
                    ));
                }
            }
        }
    }

    /**
     * Detects methods with more than 50 lines of body.
     */
    private void detectLongMethods(String filePath, String[] lines, List<CodeStyleIssue> findings) {
        int braceDepth = 0;
        Integer methodStartLine = null;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();

            // Detect method start (not if/while/for/switch/catch/synchronized blocks)
            if (methodStartLine == null && trimmed.matches("^(?!(if|while|for|switch|catch|synchronized)\\b)\\w+.*\\(.*\\).*\\{$")) {
                methodStartLine = i;
                braceDepth = 0;
            }

            // Count braces for scope tracking
            for (char c : line.toCharArray()) {
                if (c == '{') braceDepth++;
                if (c == '}') braceDepth--;
            }

            // If we've tracked a method and braceDepth returns to 0, method ended
            if (methodStartLine != null && braceDepth <= 0) {
                int methodLength = i - methodStartLine;
                if (methodLength > 50) {
                    String methodLine = lines[methodStartLine].trim();
                    findings.add(new CodeStyleIssue(
                            filePath,
                            methodStartLine + 1,
                            "MethodLength",
                            "Method '" + extractMethodName(methodLine)
                                    + "' at line " + (methodStartLine + 1)
                                    + " is " + methodLength + " lines long (max allowed: 50)."
                    ));
                }
                methodStartLine = null;
            }
        }
    }

    /**
     * Detects methods with too many parameters.
     */
    private void detectTooManyParameters(String filePath, String[] lines, List<CodeStyleIssue> findings) {
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            // Find method declarations and count parameters
            Matcher methodMatcher = METHOD_DECLARATION.matcher(line);
            if (methodMatcher.find()) {
                int paramCount = countParameters(line);
                if (paramCount > 5) {
                    findings.add(new CodeStyleIssue(
                            filePath,
                            i + 1,
                            "ParameterNumber",
                            "Method at line " + (i + 1) + " has " + paramCount
                                    + " parameters (max allowed: 5). Consider refactoring."
                    ));
                }
            }
        }
    }

    /**
     * Detects magic numbers in the code.
     */
    private void detectMagicNumbers(String filePath, String[] lines, List<CodeStyleIssue> findings) {
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();

            // Skip comments and import/package statements
            if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")
                    || trimmed.startsWith("package") || trimmed.startsWith("import")
                    || trimmed.startsWith("@")) {
                continue;
            }

            Matcher matcher = MAGIC_NUMBER.matcher(line);
            if (matcher.find()) {
                String number = matcher.group(1);
                // Skip common constants like array sizes, Collection initializations
                if (isAllowedNumericLiteral(line, number)) {
                    continue;
                }
                findings.add(new CodeStyleIssue(
                        filePath,
                        i + 1,
                        "MagicNumber",
                        "Magic number '" + number + "' at line " + (i + 1)
                                + ". Define as a named constant instead."
                ));
            }
        }
    }

    /**
     * Detects empty if/else blocks.
     */
    private void detectEmptyIfElseBlocks(String filePath, String[] lines, List<CodeStyleIssue> findings) {
        StringBuilder code = new StringBuilder();
        for (String line : lines) {
            code.append(line).append("\n");
        }
        String codeStr = code.toString();

        // Empty if blocks
        Matcher ifMatcher = EMPTY_IF_BLOCK.matcher(codeStr);
        while (ifMatcher.find()) {
            int lineNum = getLineNumber(lines, ifMatcher.start());
            findings.add(new CodeStyleIssue(
                    filePath,
                    lineNum,
                    "EmptyBlock",
                    "Empty if block at line " + lineNum + ". Either implement the body or remove the block."
            ));
        }

        // Empty else blocks
        Matcher elseMatcher = EMPTY_ELSE_BLOCK.matcher(codeStr);
        while (elseMatcher.find()) {
            int lineNum = getLineNumber(lines, elseMatcher.start());
            findings.add(new CodeStyleIssue(
                    filePath,
                    lineNum,
                    "EmptyBlock",
                    "Empty else block at line " + lineNum + ". Either implement the body or remove the block."
            ));
        }
    }

    /**
     * Detects inconsistent indentation (mixing tabs and spaces).
     */
    private void detectInconsistentIndentation(String filePath, String[] lines, List<CodeStyleIssue> findings) {
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty()) {
                continue;
            }
            // Check for mixed tabs and spaces in leading whitespace
            Matcher matcher = MIXED_INDENT.matcher(line);
            if (matcher.find()) {
                findings.add(new CodeStyleIssue(
                        filePath,
                        i + 1,
                        "Indentation",
                        "Inconsistent indentation at line " + (i + 1)
                                + ": mixed tabs and spaces. Use consistent indentation style."
                ));
                // Only report first occurrence to avoid noise
                break;
            }
        }
    }

    /**
     * Extracts the method name from a method declaration line.
     */
    private String extractMethodName(String line) {
        // Pattern: [modifiers] returnType methodName(...)
        String clean = line.replaceAll("\\{", "").trim();
        String[] parts = clean.split("\\s+");
        for (int i = 0; i < parts.length; i++) {
            // Skip modifiers and return type, find name before '('
            if (parts[i].contains("(")) {
                String name = parts[i].replaceAll("\\(.*", "");
                if (!name.isEmpty()) {
                    return name;
                }
                // If the name has the parens but no content before it
                if (i > 0) {
                    return parts[i - 1];
                }
            }
        }
        return clean.substring(0, Math.min(clean.length(), 30));
    }

    /**
     * Counts the number of parameters in a method declaration line.
     */
    private int countParameters(String line) {
        // Extract content between outermost parentheses
        int startParen = line.indexOf('(');
        int endParen = line.lastIndexOf(')');
        if (startParen < 0 || endParen < 0 || endParen <= startParen) {
            return 0;
        }
        String params = line.substring(startParen + 1, endParen).trim();
        if (params.isEmpty()) {
            return 0;
        }
        // Split by comma, but be careful of generics
        String[] paramParts = params.split(",(?![^<]*>)");
        return paramParts.length;
    }

    /**
     * Checks if a numeric literal is considered acceptable (not a magic number).
     */
    private boolean isAllowedNumericLiteral(String fullLine, String number) {
        // Allow 0, 1, -1, common small constants
        if (number.equals("0") || number.equals("1") || number.equals("-1")
                || number.equals("0.0") || number.equals("1.0") || number.equals("-1.0")) {
            return true;
        }

        // Allow size arguments in array creation: new String[10]
        if (fullLine.matches(".*new\\s+\\w+\\s*\\[" + Pattern.quote(number) + "\\].*")) {
            return true;
        }

        // Allow values in annotations
        if (fullLine.trim().startsWith("@")) {
            return true;
        }

        return false;
    }

    /**
     * Determines the line number for a character position in the code.
     */
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
}
