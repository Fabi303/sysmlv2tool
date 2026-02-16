package org.example.sysml;

import org.eclipse.emf.ecore.EObject;
import org.omg.sysml.interactive.SysMLInteractiveResult;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Validates SysML v2 files using the Pilot Implementation's native API:
 *   1. sysml.process(source)  — parse + link the model
 *   2. sysml.validate()       — run validation, return List of issues
 *
 * Exit codes: 0 = correct validation, -1 = file not found or validation errors
 */
@Command(
    name = "validate",
    mixinStandardHelpOptions = true,
    description = "Validate SysML v2 file(s) and report diagnostics"
)

public class ValidateCommand implements Callable<Integer> {
    private final SysMLTool parent;

    @Parameters(paramLabel = "<file>", description = "One or more .sysml files", arity = "1..*")
    private List<Path> files;

    @Option(names = {"--verbose", "-v"}, description = "Print the full element tree on success")
    private boolean verbose;

    public ValidateCommand(SysMLTool parent) {
        this.parent = parent;
    }

    @Override
    public Integer call() {
        SysMLEngineHelper engine = new SysMLEngineHelper(parent.getLibraryPath());
        int totalErrors = 0;

        for (Path file : files) {
            System.out.printf("%n%s%n  Validating: %s%n%s%n",
                "─".repeat(60), file, "─".repeat(60));

            // Check if file exists
            if (!Files.exists(file)) {
                System.err.printf("  ❌  File not found: %s%n", file);
                totalErrors++;
                continue;
            }

            boolean hasParseErrors = false;
            // Step 1: parse + link
            try {
                SysMLInteractiveResult result = engine.process(file);
                // SysMLInteractiveResult may itself carry parse errors
                hasParseErrors = printResultIssues(result, file.getFileName().toString());
                if (hasParseErrors) totalErrors++;
            } catch (Exception e) {
                System.err.printf("  ❌  Parse failed: %s%n", e.getMessage());
                totalErrors++;
                continue;
            }

            // Step 2: validate — this is the authoritative issue list
            List<?> issues = engine.validate();

            long errorCount   = 0;
            long warningCount = 0;

            for (Object issue : issues) {
                String msg      = getMessage(issue);
                boolean isError = isError(issue);
                if (isError) errorCount++;
                else          warningCount++;
                System.out.printf("  %s  %s%n", isError ? "❌" : "⚠️ ", msg);
            }

            if (hasParseErrors || errorCount > 0) {
                if (errorCount > 0) {
                    System.out.printf("%n  ❌  FAILED: %d error(s), %d warning(s)%n",
                        errorCount, warningCount);
                }
                totalErrors++;
            } else {
                if (warningCount > 0)
                    System.out.printf("  ⚠️   %d warning(s)%n", warningCount);
                System.out.println("  ✅  OK — no errors.");

                if (verbose) {
                    EObject root = engine.getRootElement();
                    if (root != null) {
                        System.out.println("\n  Element tree:");
                        printTree(root, 2);
                    }
                }
            }
        }

        return totalErrors > 0 ? -1 : 0;
    }

    /**
     * SysMLInteractiveResult may carry its own error string from process().
     * Print it if non-empty and not the known spurious linking noise.
     * Replace generic resource names (like "1.sysml") with the actual filename.
     * Returns true if error-level issues were found.
     */
    private boolean printResultIssues(SysMLInteractiveResult result, String filename) {
        boolean hasErrors = false;
        if (result == null) return false;
        // Try to get the string representation — in 0.56.x toString() shows errors
        String s = result.toString();
        if (s == null || s.isBlank() || s.equals("null")) return false;
        for (String line : s.split("\\r?\\n")) {
            if (line.isBlank()) continue;
            if (line.contains("Couldn't resolve reference to Element")) continue;
            // Replace generic resource names (e.g., "(1.sysml ", "(2.sysml ", etc.)
            for (int i = 0; i < 100; i++) {
                String pattern = "(" + i + ".sysml ";
                if (line.contains(pattern)) {
                    line = line.replace(pattern, "(" + filename + " ");
                    break;  // Found and replaced, stop checking other numbers
                }
            }
            String low = line.toLowerCase();
            if (low.contains("error") || low.contains("warning")) {
                String icon = low.contains("error") ? "❌" : "⚠️ ";
                System.out.printf("  %s  [parse] %s%n", icon, line.trim());
                if (low.contains("error")) hasErrors = true;
            }
        }
        return hasErrors;
    }

    private boolean isError(Object issue) {
        // Try getSeverity() — Xtext Issue has this
        try {
            Object sev = issue.getClass().getMethod("getSeverity").invoke(issue);
            if (sev != null) return sev.toString().toUpperCase().contains("ERROR");
        } catch (Exception ignored) {}
        // EMF Resource.Diagnostic has no severity — all are errors
        try {
            issue.getClass().getMethod("getLine");
            return true;
        } catch (Exception ignored) {}
        return issue.toString().toLowerCase().contains("error");
    }

    private String getMessage(Object issue) {
        for (String m : new String[]{"getMessage", "toString"}) {
            try {
                Object v = issue.getClass().getMethod(m).invoke(issue);
                if (v != null) {
                    // Try to include line number
                    try {
                        Object line = issue.getClass().getMethod("getLineNumber").invoke(issue);
                        Object col  = issue.getClass().getMethod("getColumn").invoke(issue);
                        return String.format("line %s col %s — %s", line, col, v);
                    } catch (Exception ignored) {}
                    try {
                        Object line = issue.getClass().getMethod("getLine").invoke(issue);
                        Object col  = issue.getClass().getMethod("getColumn").invoke(issue);
                        return String.format("line %s col %s — %s", line, col, v);
                    } catch (Exception ignored) {}
                    return v.toString();
                }
            } catch (Exception ignored) {}
        }
        return issue.toString();
    }

    private void printTree(EObject obj, int indent) {
        String pad  = " ".repeat(indent);
        String type = obj.eClass().getName();
        String name = DiagramCommand.getEObjectName(obj);
        System.out.printf("%s• [%s] %s%n", pad, type, name);
        for (EObject child : obj.eContents()) printTree(child, indent + 2);
    }
}
