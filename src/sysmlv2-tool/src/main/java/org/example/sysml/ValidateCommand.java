package org.example.sysml;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Validates SysML v2 files using the Pilot Implementation's native API:
 *   1. engine.validate(file) reads source, calls process(String) then validate()
 *      and returns a ValidationResult carrying both the issue list and any
 *      parse-level errors from process().
 *
 * Exit codes: 0 = no errors, -1 = file not found or any errors present.
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
                "-".repeat(60), file, "-".repeat(60));

            if (!Files.exists(file)) {
                System.err.printf("  [x]  File not found: %s%n", file);
                totalErrors++;
                continue;
            }

            SysMLEngineHelper.ValidationResult result = engine.validate(file);

            long errorCount   = 0;
            long warningCount = 0;

            // Print parse-level errors/warnings from process() and count them.
            for (SysMLEngineHelper.ParseError pe : result.parseErrors()) {
                System.out.printf("  %s  [parse] %s%n", pe.isError() ? "[x]" : "[!]", pe.message());
                if (pe.isError()) errorCount++;
                else              warningCount++;
            }

            // Print validator issues from validate() and count them.
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
     * Extracts a human-readable message from an issue object, including location
     * information (line/column) when available.
     */
    private String getMessage(Object issue) {
        String text = null;
        try {
            Object v = issue.getClass().getMethod("getMessage").invoke(issue);
            if (v != null) text = v.toString();
        } catch (Exception ignored) {}

        if (text == null) text = issue.toString();

        // Attach line/column if available. Xtext Issue uses getLineNumber();
        // EMF Resource.Diagnostic uses getLine().
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
