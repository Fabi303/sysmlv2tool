package org.example.sysml;

import org.eclipse.emf.ecore.EObject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Validates SysML v2 files using the Pilot Implementation's native API.
 *
 * Accepts one or more files or directories on the command line:
 *
 *   - Files are validated individually (existing behaviour).
 *   - Directories are scanned recursively for all *.sysml files, which are
 *     then validated together in a single batch so that cross-file imports
 *     resolve correctly. Results are still printed per-file.
 *
 * When a mix of files and directories is given, each directory argument is
 * expanded into its contained files and appended to the file list; all
 * resulting files are validated together as one batch.
 *
 * Exit codes: 0 = no errors, -1 = any file not found or errors present.
 */
@Command(
    name = "validate",
    mixinStandardHelpOptions = true,
    description = "Validate SysML v2 file(s) or directory (recursive) and report diagnostics"
)
public class ValidateCommand implements Callable<Integer> {

    private final SysMLTool parent;

    @Parameters(
        paramLabel = "<path>",
        description = "One or more .sysml files or directories to scan recursively",
        arity = "1..*"
    )
    private List<Path> inputs;

    @Option(names = {"--verbose", "-v"}, description = "Print the full element tree on success")
    private boolean verbose;

    public ValidateCommand(SysMLTool parent) {
        this.parent = parent;
    }

    @Override
    public Integer call() {
        // Expand each input into an ordered list of .sysml files.
        List<Path> files = new ArrayList<>();
        int totalErrors  = 0;

        for (Path input : inputs) {
            if (!Files.exists(input)) {
                System.err.printf("  [x]  Path not found: %s%n", input);
                totalErrors++;
                continue;
            }
            if (Files.isDirectory(input)) {
                List<Path> found = collectSysmlFiles(input);
                if (found.isEmpty()) {
                    System.err.printf("  [!]  No .sysml files found under: %s%n", input);
                } else {
                    System.out.printf("[INFO]  Found %d .sysml file(s) under: %s%n",
                        found.size(), input);
                    files.addAll(found);
                }
            } else {
                files.add(input);
            }
        }

        if (files.isEmpty()) {
            return totalErrors > 0 ? -1 : 0;
        }

        SysMLEngineHelper engine = new SysMLEngineHelper(parent.getLibraryPath());

        // Route: single file → simple validate(); multiple files → validateAll().
        Map<Path, SysMLEngineHelper.ValidationResult> results;
        if (files.size() == 1) {
            Path file = files.get(0);
            results = Map.of(file, engine.validate(file));
        } else {
            results = engine.validateAll(files);
        }

        for (Map.Entry<Path, SysMLEngineHelper.ValidationResult> entry : results.entrySet()) {
            Path file = entry.getKey();
            SysMLEngineHelper.ValidationResult result = entry.getValue();

            System.out.printf("%n%s%n  Validating: %s%n%s%n",
                "-".repeat(60), file, "-".repeat(60));

            long errorCount   = 0;
            long warningCount = 0;

            for (SysMLEngineHelper.ParseError pe : result.parseErrors()) {
                System.out.printf("  %s  [parse] %s%n", pe.isError() ? "[x]" : "[!]", pe.message());
                if (pe.isError()) errorCount++;
                else              warningCount++;
            }

            for (Object issue : result.issues()) {
                boolean isError = SysMLEngineHelper.isErrorSeverity(issue);
                if (isError) errorCount++;
                else          warningCount++;
                System.out.printf("  %s  %s%n", isError ? "[x]" : "[!]", getMessage(issue));
            }

            if (errorCount > 0) {
                System.out.printf("%n  [x]  FAILED: %d error(s), %d warning(s)%n",
                    errorCount, warningCount);
                totalErrors++;
            } else {
                if (warningCount > 0)
                    System.out.printf("  [!]  %d warning(s)%n", warningCount);
                System.out.println("  [ok] OK - no errors.");

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
     * Recursively collects all *.sysml files under {@code dir}, sorted by path
     * so that files in parent directories are processed before subdirectories
     * (a reasonable heuristic for dependency ordering).
     */
    private List<Path> collectSysmlFiles(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk
                .filter(p -> Files.isRegularFile(p)
                          && p.getFileName().toString().endsWith(".sysml"))
                .sorted(Comparator.comparingInt(Path::getNameCount)
                                  .thenComparing(Comparator.naturalOrder()))
                .collect(Collectors.toList());
        } catch (IOException e) {
            System.err.println("[ERROR] Failed to scan directory '" + dir + "': " + e.getMessage());
            return List.of();
        }
    }

    private String getMessage(Object issue) {
        String text = null;
        try {
            Object v = issue.getClass().getMethod("getMessage").invoke(issue);
            if (v != null) text = v.toString();
        } catch (Exception ignored) {}

        if (text == null) text = issue.toString();

        for (String lineMethod : new String[]{"getLineNumber", "getLine"}) {
            try {
                Object line = issue.getClass().getMethod(lineMethod).invoke(issue);
                Object col  = issue.getClass().getMethod("getColumn").invoke(issue);
                return String.format("line %s col %s - %s", line, col, text);
            } catch (Exception ignored) {}
        }

        return text;
    }

    private void printTree(EObject obj, int indent) {
        String pad  = " ".repeat(indent);
        String type = obj.eClass().getName();
        String name = DiagramCommand.getEObjectName(obj);
        System.out.printf("%s* [%s] %s%n", pad, type, name);
        for (EObject child : obj.eContents()) printTree(child, indent + 2);
    }
}
