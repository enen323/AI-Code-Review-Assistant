package com.ai.code.review.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MVP-quality static analysis tool for detecting architecture issues.
 *
 * Uses regex-based pattern matching for quick static analysis of common
 * architecture violations. Full ArchUnit process execution will be
 * integrated in a later phase.
 */
@Component
public class ArchUnitTool {

    private static final Logger log = LoggerFactory.getLogger(ArchUnitTool.class);

    /**
     * A detected architecture issue within source code.
     *
     * @param description human-readable description of the issue
     * @param severity     the severity level (LAYER_VIOLATION, CYCLIC_DEPENDENCY, etc.)
     * @param type         the architecture rule type identifier
     */
    public record ArchIssue(
            String description,
            String severity,
            String type
    ) {}

    // Pattern: Controller class annotation
    private static final Pattern CONTROLLER_ANNOTATION = Pattern.compile(
            "@(Controller|RestController)\\b"
    );

    // Pattern: Repository annotation or usage pattern in non-repository classes
    private static final Pattern REPOSITORY_INJECTION = Pattern.compile(
            "@Autowired\\s+(private\\s+)?\\w*Repository\\s+\\w+",
            Pattern.DOTALL
    );

    // Pattern: Repository injected via constructor parameter
    private static final Pattern REPOSITORY_CONSTRUCTOR_PARAM = Pattern.compile(
            "@Autowired\\s+\\w+\\s*\\([^)]*\\w*Repository\\s+\\w+",
            Pattern.DOTALL
    );

    // Pattern: Import statements to detect package dependencies
    private static final Pattern IMPORT_PATTERN = Pattern.compile(
            "^import\\s+([a-zA-Z0-9_.]+);\\s*$",
            Pattern.MULTILINE
    );

    // Pattern: Package declaration
    private static final Pattern PACKAGE_PATTERN = Pattern.compile(
            "^package\\s+([a-zA-Z0-9_.]+);\\s*$",
            Pattern.MULTILINE
    );

    /**
     * Analyzes the given code content for architecture issues.
     *
     * @param codeContent the source code content to analyze
     * @return list of detected architecture issues
     */
    public List<ArchIssue> analyze(String codeContent) {
        List<ArchIssue> findings = new ArrayList<>();

        if (codeContent == null || codeContent.isBlank()) {
            return findings;
        }

        String[] lines = codeContent.split("\n", -1);

        detectLayerViolations(codeContent, lines, findings);
        detectCyclicDependencies(codeContent, lines, findings);

        log.debug("ArchUnit analysis: {} findings", findings.size());
        return findings;
    }

    /**
     * Detects layer violations such as Controller directly calling Repository.
     */
    private void detectLayerViolations(String codeContent, String[] lines, List<ArchIssue> findings) {
        // Check if this is a Controller class
        boolean isController = false;
        boolean hasRepositoryInjection = false;

        for (String line : lines) {
            if (CONTROLLER_ANNOTATION.matcher(line).find()) {
                isController = true;
                break;
            }
        }

        // Detect Repository dependency injection in the class (field or constructor injection)
        if (isController && (REPOSITORY_INJECTION.matcher(codeContent).find()
                || REPOSITORY_CONSTRUCTOR_PARAM.matcher(codeContent).find())) {
            hasRepositoryInjection = true;
        }

        if (hasRepositoryInjection) {
            findings.add(new ArchIssue(
                    "Layer violation detected: Controller class should not directly depend on Repository. "
                            + "Use a Service layer between Controller and Repository.",
                    "HIGH",
                    "LAYER_VIOLATION"
            ));
        }

        // Detect direct Repository usage without Service layer
        boolean hasDirectRepositoryUse = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            // Check for Repository method calls (CRUD operations) in non-Service classes
            if (isController && line.matches(".*\\w*Repository\\.\\w+\\s*\\(.*")) {
                hasDirectRepositoryUse = true;
            }
        }

        if (hasDirectRepositoryUse && !findings.stream().anyMatch(f -> f.type().equals("LAYER_VIOLATION"))) {
            findings.add(new ArchIssue(
                    "Layer violation detected: Controller directly calls Repository methods. "
                            + "Business logic should be encapsulated in a Service layer.",
                    "HIGH",
                    "LAYER_VIOLATION"
            ));
        }
    }

    /**
     * Detects potential cyclic dependencies between packages.
     */
    private void detectCyclicDependencies(String codeContent, String[] lines, List<ArchIssue> findings) {
        // Extract the current package
        String currentPackage = extractPackage(codeContent);
        if (currentPackage == null || currentPackage.isBlank()) {
            return;
        }

        // Extract all imports
        Set<String> importedPackages = extractImports(codeContent);

        // Check for bidirectional/circular package dependencies
        List<String[]> detectedCycles = detectPackageCycles(currentPackage, importedPackages);

        for (String[] cycle : detectedCycles) {
            String description = String.format(
                    "Same-layer coupling detected between packages '%s' and '%s'. "
                            + "Cross-dependency within same layer may indicate tight coupling.",
                    cycle[0], cycle[1]
            );
            findings.add(new ArchIssue(
                    description,
                    "MEDIUM",
                    "SAME_LAYER_COUPLING"
            ));
        }

        // Check for common architecture pattern violations
        if (currentPackage.contains(".controller")) {
            checkControllerPackageImports(currentPackage, importedPackages, findings);
        } else if (currentPackage.contains(".repository")) {
            checkRepositoryPackageImports(currentPackage, importedPackages, findings);
        }
    }

    /**
     * Extracts the package name from code content.
     */
    private String extractPackage(String codeContent) {
        Matcher matcher = PACKAGE_PATTERN.matcher(codeContent);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "";
    }

    /**
     * Extracts all imported packages from code content.
     */
    private Set<String> extractImports(String codeContent) {
        Set<String> imports = new HashSet<>();
        Matcher matcher = IMPORT_PATTERN.matcher(codeContent);
        while (matcher.find()) {
            String fullImport = matcher.group(1);
            // Extract the package portion (up to the last two segments)
            String pkg = extractPackageFromImport(fullImport);
            if (pkg != null) {
                imports.add(pkg);
            }
        }
        return imports;
    }

    /**
     * Extracts the package name from a fully qualified import.
     */
    private String extractPackageFromImport(String fullImport) {
        // e.g., "com.example.user.controller.UserController" -> "com.example.user.controller"
        int lastDot = fullImport.lastIndexOf('.');
        if (lastDot > 0) {
            return fullImport.substring(0, lastDot);
        }
        return null;
    }

    /**
     * Detects potential cyclic dependencies between current package and imported packages.
     */
    private List<String[]> detectPackageCycles(String currentPackage, Set<String> importedPackages) {
        List<String[]> cycles = new ArrayList<>();
        String currentBase = getBasePackage(currentPackage);

        for (String imported : importedPackages) {
            String importedBase = getBasePackage(imported);

            // If the current package and imported package share the same base
            // but represent different layers, check for potential cycles
            if (!imported.equals(currentPackage) && currentBase.equals(importedBase)) {
                String currentLayer = extractLayer(currentPackage);
                String importedLayer = extractLayer(imported);

                // Detect if a lower layer imports from a higher layer (potential cycle)
                if (currentLayer != null && importedLayer != null) {
                    int currentOrder = getLayerOrder(currentLayer);
                    int importedOrder = getLayerOrder(importedLayer);
                    if (currentOrder > importedOrder) {
                        // Higher layer imports from lower layer - this is expected
                        // But if we find a reverse pattern in another file, it's cyclic
                    }
                }

                // Check for same-layer sibling dependencies (tight coupling indicator)
                if (currentLayer != null && currentLayer.equals(importedLayer)
                        && !currentPackage.equals(imported)) {
                    cycles.add(new String[]{currentPackage, imported});
                }
            }
        }

        return cycles;
    }

    /**
     * Checks if the controller package imports from inappropriate layers.
     */
    private void checkControllerPackageImports(String currentPackage, Set<String> importedPackages,
                                                List<ArchIssue> findings) {
        for (String imported : importedPackages) {
            if (imported.contains(".repository.")) {
                // Already handled by layer violation detection, check if not already added
                boolean alreadyReported = findings.stream()
                        .anyMatch(f -> f.type().equals("LAYER_VIOLATION")
                                && f.description().contains("Controller"));
                if (!alreadyReported) {
                    findings.add(new ArchIssue(
                            "Controller package '" + currentPackage
                                    + "' imports from repository package '" + imported
                                    + "'. Controllers should only depend on Service layer.",
                            "HIGH",
                            "LAYER_VIOLATION"
                    ));
                }
            }
        }
    }

    /**
     * Checks if the repository package imports from inappropriate layers.
     */
    private void checkRepositoryPackageImports(String currentPackage, Set<String> importedPackages,
                                                List<ArchIssue> findings) {
        for (String imported : importedPackages) {
            String importedLayer = extractLayer(imported);
            if (importedLayer != null && "controller".equals(importedLayer)) {
                findings.add(new ArchIssue(
                        "Repository package '" + currentPackage
                                + "' imports from controller package '" + imported
                                + "'. Repositories should not depend on controller layer.",
                        "MEDIUM",
                        "CYCLIC_DEPENDENCY"
                ));
            }
        }
    }

    /**
     * Extracts the base package (first two segments).
     */
    private String getBasePackage(String pkg) {
        String[] parts = pkg.split("\\.");
        if (parts.length >= 3) {
            return parts[0] + "." + parts[1] + "." + parts[2];
        }
        return pkg;
    }

    /**
     * Extracts the layer name from a package path.
     */
    private String extractLayer(String pkg) {
        String[] parts = pkg.split("\\.");
        for (String part : parts) {
            if (part.equals("controller") || part.equals("service")
                    || part.equals("repository") || part.equals("model")
                    || part.equals("config") || part.equals("tool")
                    || part.equals("agent") || part.equals("orchestration")) {
                return part;
            }
        }
        return null;
    }

    /**
     * Returns the architectural layer order (lower = lower layer).
     */
    private int getLayerOrder(String layer) {
        return switch (layer) {
            case "model" -> 0;
            case "repository" -> 1;
            case "tool" -> 1;
            case "service" -> 2;
            case "agent" -> 2;
            case "orchestration" -> 3;
            case "controller" -> 4;
            case "config" -> 5;
            default -> 99;
        };
    }
}
