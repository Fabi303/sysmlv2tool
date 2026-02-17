package org.example.sysml;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.omg.sysml.interactive.SysMLInteractive;
import org.omg.sysml.interactive.SysMLInteractiveResult;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Wraps SysMLInteractive for parsing, linking, and validation of SysML v2 files.
 *
 * SysMLInteractive exposes these relevant public methods:
 *
 *   process(String source)   -> SysMLInteractiveResult  (parse + link into internal RS)
 *   validate()               -> List<?>                 (run validators on last processed model)
 *   getResourceSet()         -> ResourceSet             (the shared RS holding library + model)
 *   getRootElement()         -> Element                 (root of the last processed model)
 *   loadLibrary(String path) -> void                    (load the standard library)
 *
 * There is NO link(Resource) or validate(Resource) overload on SysMLInteractive.
 */
public class SysMLEngineHelper {

    private final SysMLInteractive sysml;

    /**
     * Bundles the result of a validate() call so that callers can inspect both
     * the validator issue list and any parse-level errors from process() separately,
     * giving them full control over output and exit-code logic.
     */
    public record ValidationResult(List<?> issues, List<ParseError> parseErrors) {
        /** True if there are any error-severity issues or parse errors. */
        public boolean hasErrors() {
            return parseErrors.stream().anyMatch(ParseError::isError)
                || issues.stream().anyMatch(SysMLEngineHelper::isErrorSeverity);
        }
    }

    /** A single error or warning line surfaced from process(). */
    public record ParseError(String message, boolean isError) {}

    /** Shared severity check used by ValidationResult.hasErrors() and ValidateCommand. */
    static boolean isErrorSeverity(Object issue) {
        try {
            Object sev = issue.getClass().getMethod("getSeverity").invoke(issue);
            if (sev != null) return sev.toString().toUpperCase().contains("ERROR");
        } catch (Exception ignored) {}
        if (issue instanceof Resource.Diagnostic) return true;
        return !issue.toString().toLowerCase().contains("warning");
    }

    public SysMLEngineHelper(Path libraryPath) {
        // Initialize Xtext standalone setup if available (required outside Eclipse).
        try {
            Class<?> setupClass = Class.forName("org.omg.sysml.xtext.SysMLStandaloneSetup");
            Method doSetupMethod = setupClass.getMethod("doSetup");
            doSetupMethod.invoke(null);
        } catch (Exception e) {
            System.err.println("[DEBUG] Could not initialize Xtext standalone setup: " + e.getMessage());
        }

        sysml = SysMLInteractive.getInstance();

        Path lib = libraryPath != null ? libraryPath : autoDetectLibrary();
        if (lib != null) {
            lib = lib.toAbsolutePath().normalize();
            String libPath = lib.toString();
            if (!libPath.endsWith("/") && !libPath.endsWith("\\")) {
                libPath += System.getProperty("file.separator");
            }
            String loglevel = System.getProperty("sysmlv2.tool.loglevel", "info").toLowerCase();
            if (loglevel.equals("info")) {
                System.out.printf("[INFO]  Loading library: %s%n", libPath);
            }
            PrintStream originalOut = System.out;
            if (!loglevel.equals("verbose")) {
                PrintStream devNull = new PrintStream(new OutputStream() {
                    @Override public void write(int b) {}
                });
                System.setOut(devNull);
            }
            try {
                sysml.loadLibrary(libPath);
            } finally {
                System.setOut(originalOut);
            }
        } else {
            System.err.println("[WARN]  No standard library found. Use --libdir or set $SYSML_LIBRARY.");
        }
    }

    public SysMLInteractive getSysML() { return sysml; }

    // -------------------------------------------------------------------------
    // Primary validation API  (process -> validate)
    // -------------------------------------------------------------------------

    /**
     * Reads a SysML source file, parses and links it via process(String), runs
     * all validators via validate(), and returns a ValidationResult containing
     * both the validator issue list and any parse-level errors, with the known
     * spurious "Couldn't resolve reference to Element 'In[N]'" linker noise
     * already filtered out.
     *
     * All output decisions (printing, exit codes) are left to the caller.
     */
    public ValidationResult validate(Path sysmlFile) {
        String source;
        try {
            source = Files.readString(sysmlFile);
        } catch (IOException e) {
            System.err.println("[ERROR] Cannot read '" + sysmlFile + "': " + e.getMessage());
            return new ValidationResult(List.of(), List.of());
        }

        SysMLInteractiveResult result = sysml.process(source);
        List<?> issues = sysml.validate();

        // Collect parse-level errors/warnings from process(), filtering spurious noise.
        List<ParseError> parseErrors = new ArrayList<>();
        if (result != null) {
            String resultStr = result.toString();
            if (resultStr != null && !resultStr.isBlank() && !resultStr.equals("null")) {
                // SysMLInteractive assigns internal numeric resource names like "1.sysml",
                // "2.sysml", etc. Replace any occurrence with the actual file path so that
                // error messages are meaningful to the user.
                String displayName = sysmlFile.toString();
                for (String line : resultStr.split("\\r?\\n")) {
                    if (line.isBlank()) continue;
                    if (line.contains("Couldn't resolve reference to Element")) continue;
                    String low = line.toLowerCase();
                    if (low.contains("error") || low.contains("warning")) {
                        String msg = line.trim().replaceAll("\\b\\d+\\.sysml\\b",
                                java.util.regex.Matcher.quoteReplacement(displayName));
                        parseErrors.add(new ParseError(msg, low.contains("error")));
                    }
                }
            }
        }

        return new ValidationResult(issues != null ? issues : List.of(), parseErrors);
    }

    // -------------------------------------------------------------------------
    // ResourceSet access  (for DiagramCommand and verbose tree printing)
    // -------------------------------------------------------------------------

    /**
     * Returns the ResourceSet owned by the SysMLInteractive instance.
     */
    public ResourceSet getResourceSet() {
        try {
            Method m = sysml.getClass().getMethod("getResourceSet");
            return (ResourceSet) m.invoke(sysml);
        } catch (Exception e) {
            System.err.println("[ERROR] Could not retrieve ResourceSet: " + e.getMessage());
            return null;
        }
    }

    /**
     * Loads an additional SysML file directly into the shared ResourceSet using
     * the correct EMF URI type (URI.createFileURI) so the resource factory
     * registry resolves to the SysML parser.
     *
     * @return the loaded Resource, or null on failure.
     */
    public Resource loadResourceIntoRS(Path sysmlFile) {
        ResourceSet rs = getResourceSet();
        if (rs == null) return null;

        URI emfUri = URI.createFileURI(sysmlFile.toAbsolutePath().normalize().toString());

        Resource existing = rs.getResource(emfUri, false);
        if (existing != null) return existing;

        try (InputStream in = Files.newInputStream(sysmlFile)) {
            Resource resource = rs.createResource(emfUri);
            if (resource == null) {
                System.err.println("[ERROR] No resource factory registered for: " + emfUri);
                return null;
            }
            resource.load(in, null);
            return resource;
        } catch (IOException e) {
            System.err.println("[ERROR] Failed to load resource '" + sysmlFile + "': " + e.getMessage());
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Root-element access
    // -------------------------------------------------------------------------

    /**
     * Returns the root element of the model last processed via process().
     */
    public EObject getRootElement() {
        try {
            Object el = sysml.getRootElement();
            if (el instanceof EObject eo) return eo;
        } catch (Exception e) {
            System.err.println("[WARN]  getRootElement() failed: " + e.getMessage());
        }
        return null;
    }

    /**
     * Returns the first top-level EObject from the given Resource's content list.
     */
    public EObject getRootElement(Resource resource) {
        if (resource == null || resource.getContents().isEmpty()) return null;
        return resource.getContents().get(0);
    }

    // -------------------------------------------------------------------------
    // Misc helpers
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    public List<?> getInputResources() {
        try {
            return sysml.getInputResources();
        } catch (Exception e) {
            return List.of();
        }
    }

    // -------------------------------------------------------------------------
    // Library auto-detection
    // -------------------------------------------------------------------------

    private static Path autoDetectLibrary() {
        List<Path> candidates = new ArrayList<>();

        String env = System.getenv("SYSML_LIBRARY");
        if (env != null && Files.isDirectory(Path.of(env))) {
            candidates.add(Path.of(env));
        }

        candidates.addAll(Arrays.asList(
            Path.of("./src/submodules/SysML-v2-Release/sysml.library"),
            Path.of("./submodules/SysML-v2-Release/sysml.library"),
            Path.of("../submodules/SysML-v2-Release/sysml.library"),
            Path.of("../../submodules/SysML-v2-Release/sysml.library")
        ));

        String home = System.getProperty("user.home");
        candidates.addAll(Arrays.asList(
            Path.of(home, "../submodules/SysML-v2-Release/sysml.library"),
            Path.of(home, "../../submodules/SysML-v2-Release/sysml.library")
        ));

        for (Path path : candidates) {
            if (Files.isDirectory(path)) {
                Path marker = path.resolve("Systems Library/SysML.sysml");
                if (Files.exists(marker)) {
                    System.out.println("[INFO]  Found library at: " + path);
                    return path;
                }
            }
        }

        System.out.println("[WARN]  SysML library not found; specify with --libdir or $SYSML_LIBRARY.");
        return null;
    }
}
