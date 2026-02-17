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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 *
 * Batch validation strategy
 * -------------------------
 * When validating a set of files together (e.g. an entire directory), importing
 * dependencies must already be in the ResourceSet before the importing file is
 * loaded, otherwise cross-resource references will be unresolved at link time.
 *
 * Depth-based sorting is insufficient because a dependency can live in a
 * shallower or peer directory relative to its consumer. The only reliable
 * ordering is one derived from the actual 'import' declarations in each file.
 *
 * We therefore use a three-pass approach:
 *
 *   Sort  -- topologically order files by their 'import' declarations so that
 *            every dependency is guaranteed to precede its consumers in the
 *            load list, regardless of filesystem layout.
 *
 *   Pass 1 -- load all files in topological order via loadResourceIntoRS(Path),
 *             which assigns each resource a real file:// URI anchor. EMF resolves
 *             cross-resource references at load time; the referenced namespace
 *             must already be present in the RS at that point.
 *             Parse-level diagnostics are harvested from each Resource.
 *
 *   Pass 2 -- for each file, call process(String) + validate() so that
 *             SysMLInteractive registers it as "current" and runs the full
 *             semantic checker suite against it.
 */
public class SysMLEngineHelper {

    private final SysMLInteractive sysml;

    /**
     * Bundles the result of a validate() call so that callers can inspect both
     * the validator issue list and any parse-level errors from process() separately.
     */
    public record ValidationResult(List<?> issues, List<ParseError> parseErrors) {
        /** True if there are any error-severity issues or parse errors. */
        public boolean hasErrors() {
            return parseErrors.stream().anyMatch(ParseError::isError)
                || issues.stream().anyMatch(SysMLEngineHelper::isErrorSeverity);
        }
    }

    /** A single error or warning line surfaced from process() or a Resource diagnostic. */
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
    // Single-file validation
    // -------------------------------------------------------------------------

    /**
     * Reads a SysML source file, parses and links it via process(String), runs
     * all validators via validate(), and returns a ValidationResult.
     *
     * For multi-file models with imports use {@link #validateAll} instead, so
     * that all files are in the RS before any individual file is validated.
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

        List<ParseError> parseErrors = extractParseErrors(result, sysmlFile.toString());
        return new ValidationResult(issues != null ? issues : List.of(), parseErrors);
    }

    // -------------------------------------------------------------------------
    // Batch / directory validation
    // -------------------------------------------------------------------------

    /**
     * Validates a set of SysML files together so that cross-file imports resolve
     * correctly.
     *
     * <p>Sort: topologically order files by import dependencies so that every
     * dependency is loaded into the RS before the files that import it.
     * Depth-based sorting is unreliable because a dependency can live in any
     * directory relative to its consumer.
     *
     * <p>Pass 1: load all files in topological order via loadResourceIntoRS(Path),
     * which gives each resource a real file:// URI anchor. EMF resolves
     * cross-resource references at load time; the referenced namespace must
     * already be present in the RS. Parse diagnostics are harvested from each
     * Resource's own error/warning lists.
     *
     * <p>Pass 2: for each file call process(String) + validate() so that
     * SysMLInteractive registers it as "current" and runs the full semantic
     * checker suite.
     *
     * @param files ordered list of .sysml files to validate together
     * @return map from each Path to its ValidationResult, in input order
     */
    public Map<Path, ValidationResult> validateAll(List<Path> files) {

        // --- Topological sort by import declarations ---
        List<Path> ordered = topologicalSort(files);

        // --- Pass 1: load all files into the RS with real file:// URIs ---
        Map<Path, List<ParseError>> parseErrorsByFile = new LinkedHashMap<>();
        for (Path file : files) {
            parseErrorsByFile.put(file, new ArrayList<>());
        }

        for (Path file : ordered) {
            Resource resource = loadResourceIntoRS(file);
            if (resource == null) continue;
            List<ParseError> errs = extractResourceDiagnostics(resource, file.toString());
            parseErrorsByFile.get(file).addAll(errs);
        }

        // --- Pass 2: re-process each file via process() for semantic validation ---
        Map<Path, ValidationResult> tempResults = new LinkedHashMap<>();
        for (Path file : ordered) { // Process in topological order
            String source;
            try {
                source = Files.readString(file);
            } catch (IOException e) {
                tempResults.put(file, new ValidationResult(List.of(), parseErrorsByFile.get(file)));
                continue;
            }

            SysMLInteractiveResult processResult = sysml.process(source);
            List<?> issues = sysml.validate();

            // Merge Pass 1 resource diagnostics with any new errors from process().
            List<ParseError> parseErrors = new ArrayList<>(parseErrorsByFile.get(file));
            for (ParseError pe : extractParseErrors(processResult, file.toString())) {
                if (parseErrors.stream().noneMatch(e -> e.message().equals(pe.message()))) {
                    parseErrors.add(pe);
                }
            }

            tempResults.put(file, new ValidationResult(
                issues != null ? issues : List.of(),
                parseErrors
            ));
        }

        // Reorder results to match input order
        Map<Path, ValidationResult> results = new LinkedHashMap<>();
        for (Path file : files) {
            results.put(file, tempResults.get(file));
        }

        return results;
    }

    // -------------------------------------------------------------------------
    // Topological sort
    // -------------------------------------------------------------------------

    // Matches import statements, capturing the root namespace (first segment).
    // Handles optional public/private visibility prefix.
    // Examples matched:
    //   import Foo::*;
    //   public import Foo::Bar::*;
    //   private import Foo;
    private static final Pattern IMPORT_PATTERN =
        Pattern.compile(
            "(?:^|\\s)(?:public\\s+|private\\s+)?import\\s+([A-Za-z_][\\w]*)(?:::|[\\s;])",
            Pattern.MULTILINE);

    // Matches the top-level package or namespace declaration name.
    // Examples matched:
    //   package ProjectRequirements {
    //   namespace Foo {
    private static final Pattern PACKAGE_PATTERN =
        Pattern.compile(
            "(?:^|\\s)(?:package|namespace)\\s+([A-Za-z_][\\w]*)",
            Pattern.MULTILINE);

    /**
     * Returns a topologically sorted copy of {@code files} such that every file
     * that declares a package imported by another file appears before that file.
     *
     * Algorithm: Kahn's BFS on a dependency graph built by scanning each file
     * for its top-level package name and its import statements.
     *
     * Files whose imports cannot be resolved within the set (e.g. standard
     * library references) are treated as having no in-set dependency and are
     * placed first. Cycles are broken by appending remaining nodes at the end
     * with a warning.
     */
    private List<Path> topologicalSort(List<Path> files) {
        // Step 1: scan each file for its declared package name and imported roots.
        Map<Path, String>       declaredPackage = new LinkedHashMap<>();
        Map<Path, List<String>> importedRoots   = new LinkedHashMap<>();

        for (Path file : files) {
            String src;
            try { src = Files.readString(file); }
            catch (IOException e) { src = ""; }

            Matcher pm = PACKAGE_PATTERN.matcher(src);
            declaredPackage.put(file, pm.find() ? pm.group(1) : null);

            List<String> imports = new ArrayList<>();
            Matcher im = IMPORT_PATTERN.matcher(src);
            while (im.find()) imports.add(im.group(1));
            importedRoots.put(file, imports);
        }

        // Step 2: build reverse index: packageName -> Path that declares it.
        Map<String, Path> packageToFile = new LinkedHashMap<>();
        for (Map.Entry<Path, String> e : declaredPackage.entrySet()) {
            if (e.getValue() != null) packageToFile.put(e.getValue(), e.getKey());
        }

        // Step 3: build in-degree map and dependency sets.
        // deps.get(consumer) = set of files that consumer depends on (must load first).
        Map<Path, LinkedHashSet<Path>> deps     = new LinkedHashMap<>();
        Map<Path, Integer>             inDegree = new LinkedHashMap<>();
        for (Path f : files) {
            deps.put(f, new LinkedHashSet<>());
            inDegree.put(f, 0);
        }
        for (Path consumer : files) {
            for (String imp : importedRoots.get(consumer)) {
                Path provider = packageToFile.get(imp);
                if (provider == null || provider.equals(consumer)) continue;
                if (deps.get(consumer).add(provider)) {
                    // consumer gains one more prerequisite
                    inDegree.merge(consumer, 1, Integer::sum);
                }
            }
        }

        // Step 4: Kahn's BFS — start with nodes that have no in-set dependencies.
        List<Path> queue  = new ArrayList<>();
        List<Path> result = new ArrayList<>();
        for (Path f : files) {
            if (inDegree.getOrDefault(f, 0) == 0) queue.add(f);
        }

        while (!queue.isEmpty()) {
            Path node = queue.remove(0);
            result.add(node);
            // Reduce in-degree of every consumer that depends on this node.
            for (Path consumer : files) {
                if (deps.get(consumer).contains(node)) {
                    int newDeg = inDegree.merge(consumer, -1, Integer::sum);
                    if (newDeg == 0) queue.add(consumer);
                }
            }
        }

        // Step 5: handle cycles or missed nodes.
        if (result.size() < files.size()) {
            System.err.println("[WARN]  Cycle or unresolved dependency in import graph; "
                + "appending remaining files in original order.");
            for (Path f : files) {
                if (!result.contains(f)) result.add(f);
            }
        }

        if (Boolean.getBoolean("sysml.debug")) {
            System.out.println("[DEBUG] Topological load order:");
            for (int i = 0; i < result.size(); i++)
                System.out.printf("  %d. %s%n", i + 1, result.get(i));
        }

        return result;
    }

    // -------------------------------------------------------------------------
    // Parse error extraction
    // -------------------------------------------------------------------------

    /**
     * Converts the string representation of a SysMLInteractiveResult into a list
     * of ParseError objects, filtering known spurious linker noise.
     */
    private List<ParseError> extractParseErrors(SysMLInteractiveResult result, String displayName) {
        List<ParseError> parseErrors = new ArrayList<>();
        if (result == null) return parseErrors;

        String resultStr = result.toString();
        if (resultStr == null || resultStr.isBlank() || resultStr.equals("null")) return parseErrors;

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
        return parseErrors;
    }

    /**
     * Harvests parse-level diagnostics directly from a loaded EMF Resource.
     * Used for Pass 1 errors where files are loaded via loadResourceIntoRS().
     */
    private List<ParseError> extractResourceDiagnostics(Resource resource, String displayName) {
        List<ParseError> result = new ArrayList<>();
        for (Resource.Diagnostic d : resource.getErrors()) {
            if (d.getMessage().contains("Couldn't resolve reference to Element")) continue;
            result.add(new ParseError(
                String.format("line %d - %s", d.getLine(), d.getMessage()), true));
        }
        for (Resource.Diagnostic d : resource.getWarnings()) {
            result.add(new ParseError(
                String.format("line %d - %s [warning]", d.getLine(), d.getMessage()), false));
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // ResourceSet access
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
     * Loads a SysML file directly into the shared ResourceSet using
     * URI.createFileURI() so each resource has a real filesystem anchor and
     * cross-file 'import' statements can be resolved by the linker.
     *
     * This is the preferred loading method for batch validation (Pass 1 of
     * validateAll). Unlike process(String), it preserves the file's URI so
     * the EMF resource factory registry can resolve relative imports.
     *
     * @return the loaded Resource, or null on failure.
     */
    public Resource loadResourceIntoRS(Path sysmlFile) {
        ResourceSet rs = getResourceSet();
        if (rs == null) return null;

        URI emfUri = URI.createFileURI(sysmlFile.toAbsolutePath().normalize().toString());

        // Return existing resource if already loaded (idempotent).
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

    /** Returns the root element of the model last processed via process(). */
    public EObject getRootElement() {
        try {
            Object el = sysml.getRootElement();
            if (el instanceof EObject eo) return eo;
        } catch (Exception e) {
            System.err.println("[WARN]  getRootElement() failed: " + e.getMessage());
        }
        return null;
    }

    /** Returns the first top-level EObject from the given Resource's content list. */
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
