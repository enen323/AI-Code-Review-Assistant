package com.ai.code.review.tool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ArchUnitTool verifying pattern detection capabilities.
 */
class ArchUnitToolTest {

    private ArchUnitTool tool;

    @BeforeEach
    void setUp() {
        tool = new ArchUnitTool();
    }

    /**
     * Tests detection of controller depending directly on repository (layer violation).
     */
    @Test
    void testLayerViolationControllerToRepository() {
        String code = """
                package com.example.controller;

                import com.example.repository.UserRepository;
                import org.springframework.beans.factory.annotation.Autowired;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                public class UserController {
                    @Autowired
                    private UserRepository userRepository;
                }
                """;
        List<ArchUnitTool.ArchIssue> results = tool.analyze(code);
        assertTrue(results.stream().anyMatch(p -> p.type().equals("LAYER_VIOLATION")),
                "Should detect layer violation: controller depends directly on repository");
    }

    /**
     * Tests that clean layered architecture produces no layer violations.
     */
    @Test
    void testCleanLayeredArchitectureNoViolations() {
        String code = """
                package com.example.controller;

                import com.example.service.UserService;
                import org.springframework.beans.factory.annotation.Autowired;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                public class UserController {
                    @Autowired
                    private UserService userService;
                }
                """;
        List<ArchUnitTool.ArchIssue> results = tool.analyze(code);
        assertTrue(results.stream().noneMatch(p -> p.type().equals("LAYER_VIOLATION")),
                "Should not flag controller depending on service");
    }

    /**
     * Tests detection of cyclic dependencies between packages.
     */
    @Test
    void testCyclicDependencyDetection() {
        // Simulates a repository package importing from a controller package
        String code = """
                package com.example.repository;

                import com.example.controller.UserController;

                public class UserRepositoryImpl {
                    private UserController userController;
                }
                """;
        List<ArchUnitTool.ArchIssue> results = tool.analyze(code);
        assertTrue(results.stream().anyMatch(p -> p.type().equals("CYCLIC_DEPENDENCY")),
                "Should detect cyclic dependency: repository importing from controller");
    }

    /**
     * Tests detection of controller directly calling repository method.
     */
    @Test
    void testControllerDirectlyCallsRepositoryMethod() {
        String code = """
                package com.example.controller;

                import com.example.repository.UserRepository;
                import org.springframework.beans.factory.annotation.Autowired;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                public class UserController {
                    @Autowired
                    private UserRepository userRepository;

                    public void doSomething() {
                        userRepository.findAll();
                    }
                }
                """;
        List<ArchUnitTool.ArchIssue> results = tool.analyze(code);
        assertTrue(results.stream().anyMatch(p -> p.type().equals("LAYER_VIOLATION")),
                "Should detect layer violation: controller calls repository method directly");
    }

    /**
     * Tests that null or empty input returns empty list.
     */
    @Test
    void testEmptyCodeReturnsNoFindings() {
        assertTrue(tool.analyze("").isEmpty());
        assertTrue(tool.analyze(null).isEmpty());
    }

    /**
     * Tests that ArchIssue record contains correct information.
     */
    @Test
    void testArchIssueRecordContainsCorrectData() {
        String code = """
                package com.example.controller;

                import com.example.repository.UserRepository;
                import org.springframework.beans.factory.annotation.Autowired;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                public class UserController {
                    @Autowired
                    private UserRepository userRepository;
                }
                """;
        List<ArchUnitTool.ArchIssue> results = tool.analyze(code);
        assertFalse(results.isEmpty());
        ArchUnitTool.ArchIssue issue = results.getFirst();

        assertNotNull(issue.description());
        assertEquals("LAYER_VIOLATION", issue.type());
        assertEquals("HIGH", issue.severity());
    }

    /**
     * Tests that clean code with proper layering produces no findings.
     */
    @Test
    void testCleanArchitectureReturnsNoFindings() {
        String code = """
                package com.example.service;

                import com.example.repository.UserRepository;
                import org.springframework.beans.factory.annotation.Autowired;
                import org.springframework.stereotype.Service;

                @Service
                public class UserService {
                    @Autowired
                    private UserRepository userRepository;
                }
                """;
        List<ArchUnitTool.ArchIssue> results = tool.analyze(code);
        assertTrue(results.isEmpty(), "Clean layered architecture should produce no findings");
    }
}
