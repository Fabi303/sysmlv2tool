package org.example.sysml;

import com.google.inject.Injector;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.xtext.resource.XtextResourceSet;
import org.eclipse.xtext.validation.CheckMode;
import org.eclipse.xtext.validation.IResourceValidator;
import org.eclipse.xtext.validation.Issue;
import org.omg.sysml.interactive.SysMLInteractive;
import org.omg.sysml.interactive.SysMLInteractiveResult;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.example.sysml.Logger.*;

/**
 * Wraps SysMLInteractive for parsing, linking, and validation of SysML v2 files.
 *
 * BATCH VALIDATION STRATEGY
 * ==========================
 *
 * SysMLInteractive.loadLibrary() populates two things:
 *   1. A plain ResourceSetImpl with parsed library Resource objects
 *   2. A ResourceDescriptionsData index (SysMLUtil.index) used by the Xtext
 *      scope provider to resolve cross-references by qualified name
 *
 * For batch validation we build a fresh XtextResourceSet from the injector and:
 *   Phase 1 — Mirror the already-parsed library Resource objects from
 *             SysMLInteractive's ResourceSetImpl directly into our XtextResourceSet.
 *             This avoids re-parsing the library and gives EcoreUtil.resolveAll()
 *             the EObjects it needs to follow cross-references.
 *             We also share the library index so the Xtext scope provider resolves
 *             qualified names like ScalarValues::Boolean.
 *   Phase 2 — Load user files into the same XtextResourceSet via file:// URIs.
 *   Phase 3 — EcoreUtil.resolveAll() on user resources only.
 *   Phase 4 — IResourceValidator.validate() per user resource.
 */
public class SysMLEngineHelper {

    private final SysMLInteractive sysml;
    private final Injector injector;
    private final Path resolvedLibraryPath;
    private XtextResourceSet batchResourceSet;

    // -------------------------------------------------------------------------
    // Records
    // -------------------------------------------------------------------------

    public record ValidationResult(List<?> issues, List<ParseError> parseErrors) {
        public boolean hasErrors() {
            return parseErrors.stream().anyMatch(ParseError::isError)
                || issues.stream().anyMatch(SysMLEngineHelper::isErrorSeverity);
        }
    }

    public record ParseError(String message, boolean isError) {}

    static boolean isErrorSeverity(Object issue) {
        try {
            Object sev = issue.getClass().getMethod("getSeverity").invoke(issue);
            if (sev != null) return sev.toString().toUpperCase().contains("ERROR");
        } catch (Exception ignored) {}
        if (issue instanceof Resource.Diagnostic) return true;
        return !issue.toString().toLowerCase().contains("warning");
    }

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    public SysMLEngineHelper(Path libraryPath) {
        // createInstance() handles all Xtext/EMF standalone setup internally
        // (KerMLStandaloneSetup, SysMLxStandaloneSetup, SysMLStandaloneSetup) and
        // returns a fully Guice-injected SysMLInteractive instance.
        sysml = SysMLInteractive.createInstance();

        // The Guice injector is stored as a private static field on SysMLInteractive
        // by createInstance(). There is no public getInjector() method, so reflection
        // is still required — but we target the known static field directly rather than
        // searching the instance hierarchy.
        Injector inj = null;
        try {
            Field f = SysMLInteractive.class.getDeclaredField("injector");
            f.setAccessible(true);
            inj = (Injector) f.get(null); // null receiver = static field
        } catch (Exception e) {
            Logger.warn("Could not retrieve injector from SysMLInteractive: " + e.getMessage());
        }
        injector = inj;
        Logger.debug("Injector: %s", injector != null ? injector.getClass().getName() : "NULL");

        Path lib = libraryPath != null ? libraryPath : autoDetectLibrary();
        resolvedLibraryPath = lib != null ? lib.toAbsolutePath().normalize() : null;

        if (resolvedLibraryPath != null) {
            String libPath = resolvedLibraryPath.toString();
            if (!libPath.endsWith("/") && !libPath.endsWith("\\"))
                libPath += System.getProperty("file.separator");

                Logger.info("Loading library: %s", libPath.toString().trim());

            PrintStream originalOut = System.out;
            if (!Logger.isDebugEnabled() || Logger.isInfoEnabled())
                System.setOut(new PrintStream(new OutputStream() { @Override public void write(int b) {} }));
            try {
                sysml.loadLibrary(libPath);
            } finally {
                System.setOut(originalOut);
            }
        } else {
            Logger.warn("No standard library found. Use --libdir or set $SYSML_LIBRARY.");
        }
    }

    public SysMLInteractive getSysML() { return sysml; }

    // -------------------------------------------------------------------------
    // Single-file validation
    // -------------------------------------------------------------------------

    public ValidationResult validate(Path sysmlFile) {
        String source;
        try {
            source = Files.readString(sysmlFile);
        } catch (IOException e) {
            Logger.error("Cannot read '" + sysmlFile + "': " + e.getMessage());
            return new ValidationResult(List.of(), List.of());
        }
        SysMLInteractiveResult result = sysml.process(source);
        List<?> issues = sysml.validate();
        List<ParseError> parseErrors = extractParseErrors(result, sysmlFile.toString());
        return new ValidationResult(issues != null ? issues : List.of(), parseErrors);
    }

    // -------------------------------------------------------------------------
    // Batch validation
    // -------------------------------------------------------------------------

    public Map<Path, ValidationResult> validateAll(List<Path> files) {

        if (injector == null) {
            Logger.error("No Guice injector — cannot perform batch validation.");
            Map<Path, ValidationResult> err = new LinkedHashMap<>();
            for (Path f : files)
                err.put(f, new ValidationResult(List.of(),
                    List.of(new ParseError("Injector unavailable.", true))));
            return err;
        }

        try {
            XtextResourceSet resourceSet = injector.getInstance(XtextResourceSet.class);
            this.batchResourceSet = resourceSet;
            IResourceValidator validator = injector.getInstance(IResourceValidator.class);
            Logger.debug("XtextResourceSet: %s", resourceSet.getClass().getName());
            Logger.debug("IResourceValidator: %s", validator.getClass().getName());

            // ===== PHASE 1: Populate XtextResourceSet with library resources =====
            //
            // SysMLInteractive.loadLibrary() already parsed all library files into a
            // plain ResourceSetImpl (SysMLUtil.resourceSet) and built a scope index
            // (SysMLUtil.index). We reuse both:
            //
            // a) Mirror all parsed library Resource objects into our XtextResourceSet.
            //    This makes their EObjects reachable by EcoreUtil.resolveAll() without
            //    re-parsing any files.
            //
            // b) Share the populated scope index with our XtextResourceSet by installing
            //    it via setResourceDescriptions() (if available) so that the Xtext scope
            //    provider resolves qualified names like ScalarValues::Boolean correctly.

            Logger.info("Phase 1: Populating XtextResourceSet from library...");
            ResourceSet sysmlRs = getResourceSet();
            if (sysmlRs != null) {
                int mirrored = 0;
                for (Resource r : new ArrayList<>(sysmlRs.getResources())) {
                    if (r != null && r.getURI() != null) {
                        resourceSet.getResources().add(r);
                        mirrored++;
                    }
                }
                Logger.debug("Mirrored %d library resource(s) into XtextResourceSet", mirrored);
            }

            // Share the scope index so qualified name lookups resolve correctly.
            Object index = null;
            try {
                Field f = findField(sysml, "index");
                if (f != null) index = f.get(sysml);
            } catch (Exception e) {
                Logger.error("Could not retrieve index field: %s", e.getMessage());
            }
            if (index != null) {
                try {
                    Method m = findMethod(resourceSet, "setResourceDescriptions", 1);
                    if (m != null) {
                        m.invoke(resourceSet, index);
                        Logger.debug("Library scope index installed via setResourceDescriptions()");
                    } else {
                        // XtextResourceSet may expose the index differently — try the
                        // Guice-injected IResourceDescriptions key instead.
                        Logger.debug("setResourceDescriptions() not found; scope resolution via injected index only");
                    }
                } catch (Exception e) {
                    Logger.error("setResourceDescriptions() failed: %s", e.getMessage());
                }
            }
            Logger.debug("ResourceSet size after Phase 1: %d", resourceSet.getResources().size());

            // ===== PHASE 2: Load user files =====
            Logger.info("Phase 2: Loading %d user file(s)...", files.size());

            Map<Path, Resource>   fileToResource = new LinkedHashMap<>();
            Map<Path, ParseError> ioErrors       = new LinkedHashMap<>();

            for (Path file : files) {
                URI uri = URI.createFileURI(file.toAbsolutePath().normalize().toString());
                Logger.info(" %s", file.toString().trim());
                try {
                    Resource resource = resourceSet.getResource(uri, true);
                    fileToResource.put(file, resource);
                    Logger.debug("  Loaded: %s  errors=%d  warnings=%d  contents=%d",
                        file.getFileName(),
                        resource.getErrors().size(),
                        resource.getWarnings().size(),
                        resource.getContents().size());
                } catch (Exception e) {
                    Logger.error("Cannot load '%s': %s%n", file, e.getMessage());
                    ioErrors.put(file, new ParseError("Cannot load file: " + e.getMessage(), true));
                }
            }

            // ===== PHASE 3: Resolve cross-references (user resources only) =====
            Logger.info("Phase 3: Resolving cross-references...");
            
            for (Resource r : fileToResource.values()) {
                if (r == null) continue;

                Logger.debug(" Resolving: %s", r.getURI());
                
                for (EObject root : r.getContents())
                    EcoreUtil.resolveAll(root);
            }

            // ===== PHASE 4: Validate =====
            Logger.info("Phase 4: Validating %d file(s)...", files.size());
            
            Map<Path, ValidationResult> results = new LinkedHashMap<>();

            for (Path file : files) {
                Logger.info(" %s", file.toString().trim());
                if (ioErrors.containsKey(file)) {
                    results.put(file, new ValidationResult(List.of(),
                        List.of(ioErrors.get(file))));
                    continue;
                }

                Resource resource = fileToResource.get(file);
                List<ParseError> parseErrors = new ArrayList<>();

                if (resource == null) {
                    parseErrors.add(new ParseError("Resource could not be loaded", true));
                    results.put(file, new ValidationResult(List.of(), parseErrors));
                    continue;
                }

                // Run the semantic validator first, then deduplicate parse diagnostics.
                // Both resource.getErrors() and IResourceValidator report linker errors
                // (e.g. "Couldn't resolve reference") — we keep the validator version
                // and only promote parse diagnostics not already covered by an Issue.
                List<Issue> issues = new ArrayList<>();
                try {
                    List<Issue> raw = validator.validate(resource, CheckMode.ALL, null);
                    if (raw != null) issues = raw;
                } catch (Exception e) {
                    Logger.warn("Validation failed for %s: %s%n",
                        file.getFileName(), e.getMessage());
                    Logger.error("Stacktrace",e);
                }

                java.util.Set<String> validatorMessages = new java.util.HashSet<>();
                for (Issue issue : issues)
                    if (issue.getMessage() != null)
                        validatorMessages.add(issue.getMessage().trim());

                for (Resource.Diagnostic d : resource.getErrors()) {
                    String msg = d.getMessage() != null ? d.getMessage().trim() : "";
                    if (!validatorMessages.contains(msg))
                        parseErrors.add(new ParseError(formatDiag(d, file), true));
                }
                for (Resource.Diagnostic d : resource.getWarnings()) {
                    String msg = d.getMessage() != null ? d.getMessage().trim() : "";
                    if (!validatorMessages.contains(msg))
                        parseErrors.add(new ParseError(formatDiag(d, file), false));
                }

                Logger.debug("  %s: %d parse diag(s), %d validator issue(s)",
                    file.getFileName(), parseErrors.size(), issues.size());

                results.put(file, new ValidationResult(issues, parseErrors));
            }

            return results;

        } catch (Exception e) {
            Logger.error("Batch validation failed: %s%n", e.getMessage());
            Logger.error("Stacktrace",e);
            Map<Path, ValidationResult> fallback = new LinkedHashMap<>();
            for (Path file : files)
                fallback.put(file, new ValidationResult(List.of(),
                    List.of(new ParseError("Batch validation failed: " + e.getMessage(), true))));
            return fallback;
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    public ResourceSet getResourceSet() {
        try {
            return sysml.getResourceSet();
        } catch (Exception e) {
            Logger.error("Could not retrieve ResourceSet: " + e.getMessage());
            return null;
        }
    }

    /**
     * Returns the XtextResourceSet built during the last {@link #validateAll} call,
     * which contains both library resources and the loaded user files.
     * Returns {@code null} if {@code validateAll} has not been called yet.
     */
    public XtextResourceSet getBatchResourceSet() {
        return batchResourceSet;
    }

    public EObject getRootElement() {
        try {
            Object el = sysml.getRootElement();
            if (el instanceof EObject eo) return eo;
        } catch (Exception e) {
            Logger.warn("getRootElement() failed: " + e.getMessage());
        }
        return null;
    }

    public EObject getRootElement(Resource resource) {
        if (resource == null || resource.getContents().isEmpty()) return null;
        return resource.getContents().get(0);
    }

    private static Field findField(Object target, String name) {
        for (Class<?> cls = target.getClass(); cls != null; cls = cls.getSuperclass()) {
            try {
                Field f = cls.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {}
        }
        return null;
    }

    private static Method findMethod(Object target, String name, int paramCount) {
        for (Class<?> cls = target.getClass(); cls != null; cls = cls.getSuperclass()) {
            for (Method m : cls.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == paramCount) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        return null;
    }

    private static List<Path> collectSysmlFiles(Path dir) {
        try (Stream<Path> stream = Files.walk(dir)) {
            return stream
                .filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".sysml"))
                .sorted()
                .collect(Collectors.toList());
        } catch (IOException e) {
            Logger.warn("Could not scan directory " + dir + ": " + e.getMessage());
            return List.of();
        }
    }

    private String formatDiag(Resource.Diagnostic diag, Path file) {
        StringBuilder sb = new StringBuilder(diag.getMessage());
        try {
            sb.append(" (").append(file.getFileName())
              .append(" line:").append(diag.getLine())
              .append(" col:").append(diag.getColumn()).append(")");
        } catch (Exception ignored) {}
        return sb.toString();
    }

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

    // -------------------------------------------------------------------------
    // Library auto-detection
    // -------------------------------------------------------------------------

    private static Path autoDetectLibrary() {
        List<Path> candidates = new ArrayList<>();

        String env = System.getenv("SYSML_LIBRARY");
        if (env != null && Files.isDirectory(Path.of(env)))
            candidates.add(Path.of(env));

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

                    Logger.info("Found library at: " + path.toString().trim());
                    return path;
                }
            }
        }

        Logger.warn("SysML library not found; specify with --libdir or $SYSML_LIBRARY.");
        return null;
    }
}
