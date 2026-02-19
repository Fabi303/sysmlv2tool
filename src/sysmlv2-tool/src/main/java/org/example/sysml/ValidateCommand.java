package org.example.sysml;

import org.eclipse.emf.ecore.EObject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

import static org.example.sysml.FileUtils.*;

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

    @Option(
        names = {"--format", "-f"},
        defaultValue = "text",
        description = "Output format: text (default) or xml (JUnit-compatible)"
    )
    private String format;

    public ValidateCommand(SysMLTool parent) {
        this.parent = parent;
    }

    @Override
    public Integer call() {
        boolean xmlMode = "xml".equalsIgnoreCase(format);

        // Use Set to avoid duplicates
        Set<Path> uniqueFiles = new LinkedHashSet<>();
        int totalErrors = 0;

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
                    if (!xmlMode) {
                        System.out.printf("[INFO]  Found %d .sysml file(s) under: %s%n",
                            found.size(), input);
                    }
                    uniqueFiles.addAll(found);
                }
            } else {
                uniqueFiles.add(input);
            }
        }

        if (uniqueFiles.isEmpty()) {
            return totalErrors > 0 ? -1 : 0;
        }

        // Convert to list after deduplication
        List<Path> files = new ArrayList<>(uniqueFiles);

        SysMLEngineHelper engine = new SysMLEngineHelper(parent.getLibraryPath());

        // Route: single file → simple validate(); multiple files → validateAll().
        Map<Path, SysMLEngineHelper.ValidationResult> results;
        if (files.size() == 1) {
            Path file = files.get(0);
            results = Map.of(file, engine.validate(file));
        } else {
            results = engine.validateAll(files);
        }

        // Collect per-file errors and warnings (preserving insertion order)
        Map<Path, List<String>> fileErrors   = new LinkedHashMap<>();
        Map<Path, List<String>> fileWarnings = new LinkedHashMap<>();

        for (Map.Entry<Path, SysMLEngineHelper.ValidationResult> entry : results.entrySet()) {
            Path file = entry.getKey();
            SysMLEngineHelper.ValidationResult result = entry.getValue();

            List<String> errors   = new ArrayList<>();
            List<String> warnings = new ArrayList<>();
            // pairs of [severity-tag, message] for text output
            List<String[]> messages = new ArrayList<>();

            for (SysMLEngineHelper.ParseError pe : result.parseErrors()) {
                String msg = "[parse] " + pe.message();
                if (pe.isError()) {
                    errors.add(msg);
                    messages.add(new String[]{"[x]", msg});
                } else {
                    warnings.add(msg);
                    messages.add(new String[]{"[!]", msg});
                }
            }

            for (Object issue : result.issues()) {
                boolean isError = SysMLEngineHelper.isErrorSeverity(issue);
                String msg = getMessage(issue);
                if (isError) {
                    errors.add(msg);
                    messages.add(new String[]{"[x]", msg});
                } else {
                    warnings.add(msg);
                    messages.add(new String[]{"[!]", msg});
                }
            }

            fileErrors.put(file, errors);
            fileWarnings.put(file, warnings);

            if (!xmlMode) {
                System.out.printf("%n%s%n  Validating: %s%n%s%n",
                    "-".repeat(60), file, "-".repeat(60));

                for (String[] m : messages) {
                    System.out.printf("  %s  %s%n", m[0], m[1]);
                }

                if (!errors.isEmpty()) {
                    System.out.printf("%n  [x]  FAILED: %d error(s), %d warning(s)%n",
                        errors.size(), warnings.size());
                } else {
                    if (!warnings.isEmpty())
                        System.out.printf("  [!]  %d warning(s)%n", warnings.size());
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

            if (!errors.isEmpty()) totalErrors++;
        }

        if (xmlMode) {
            try {
                writeJUnitXml(fileErrors, fileWarnings);
            } catch (Exception e) {
                System.err.println("Failed to write JUnit XML: " + e.getMessage());
            }
        }

        return totalErrors > 0 ? -1 : 0;
    }

    /**
     * Writes JUnit-compatible XML to stdout.
     *
     * Format:
     * <pre>
     * {@code
     * <testsuites>
     *   <testsuite name="path/to/file.sysml" tests="1" failures="N" errors="0" skipped="0">
     *     <testcase name="validate" classname="path/to/file.sysml" time="0">
     *       <!-- only present when N > 0 -->
     *       <failure message="N Validierungsfehler gefunden">
     *         line X col Y - Error text
     *         ...
     *       </failure>
     *     </testcase>
     *   </testsuite>
     * </testsuites>
     * }
     * </pre>
     */
    private void writeJUnitXml(Map<Path, List<String>> fileErrors,
                               Map<Path, List<String>> fileWarnings) throws Exception {
        XMLOutputFactory factory = XMLOutputFactory.newInstance();
        XMLStreamWriter xml = factory.createXMLStreamWriter(System.out, "UTF-8");

        xml.writeStartDocument("UTF-8", "1.0");
        xml.writeCharacters("\n");
        xml.writeStartElement("testsuites");
        xml.writeCharacters("\n");

        for (Map.Entry<Path, List<String>> entry : fileErrors.entrySet()) {
            Path file      = entry.getKey();
            List<String> errors   = entry.getValue();
            List<String> warnings = fileWarnings.getOrDefault(file, List.of());

            xml.writeCharacters("  ");
            xml.writeStartElement("testsuite");
            xml.writeAttribute("name",     file.toString());
            xml.writeAttribute("tests",    "1");
            xml.writeAttribute("failures", errors.isEmpty() ? "0" : "1");
            xml.writeAttribute("errors",   "0");
            xml.writeAttribute("skipped",  "0");
            xml.writeCharacters("\n    ");

            xml.writeStartElement("testcase");
            xml.writeAttribute("name",      "validate");
            xml.writeAttribute("classname", file.toString());
            xml.writeAttribute("time",      "0");

            if (!errors.isEmpty()) {
                xml.writeCharacters("\n      ");
                xml.writeStartElement("failure");
                xml.writeAttribute("message", errors.size() + " Validierungsfehler gefunden");
                xml.writeCharacters("\n" + String.join("\n", errors) + "\n      ");
                xml.writeEndElement(); // failure
                xml.writeCharacters("\n    ");
            }

            xml.writeEndElement(); // testcase
            xml.writeCharacters("\n  ");
            xml.writeEndElement(); // testsuite
            xml.writeCharacters("\n");
        }

        xml.writeEndElement(); // testsuites
        xml.writeEndDocument();
        xml.flush();
        System.out.println();
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
