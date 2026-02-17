package org.example.sysml;

import net.sourceforge.plantuml.FileFormat;
import net.sourceforge.plantuml.FileFormatOption;
import net.sourceforge.plantuml.SourceStringReader;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.omg.sysml.plantuml.SysML2PlantUMLText;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Generates diagrams using SysML2PlantUMLText from the Pilot Implementation.
 *
 * Accepts one or more files or directories on the command line:
 *   - Files are diagrammed individually or together based on options
 *   - Directories are scanned recursively for all *.sysml files, which are
 *     loaded together into the ResourceSet so cross-file references work
 *
 * Usage modes:
 *   diagram <path>                         -- diagram the root element(s)
 *   diagram <path> --element MyPart        -- diagram a specific named element
 *   diagram <path> --all-elements          -- one diagram per top-level element
 *
 * SysML2PlantUMLText API:
 *   The actual API is sysML2PUML(List<Element>) which takes a list of elements
 *   and returns PlantUML source. We wrap single elements in a list for this.
 */
@Command(
    name = "diagram",
    mixinStandardHelpOptions = true,
    description = "Generate PlantUML diagrams from SysML v2 file(s) or directory"
)
public class DiagramCommand implements Callable<Integer> {
    private final SysMLTool parent;

    @Parameters(
        paramLabel = "<path>",
        description = "One or more .sysml files or directories to scan recursively",
        arity = "1..*"
    )
    private List<Path> inputs;

    @Option(names = {"--element", "-e"}, description = "Name of element to diagram (default: root)", paramLabel = "<n>")
    private String elementName;

    @Option(names = {"--all-elements"}, description = "One diagram per top-level owned member")
    private boolean allElements;

    @Option(names = {"--format", "-f"}, description = "Output format: png, svg, puml (default: puml)",
        paramLabel = "<fmt>", defaultValue = "puml")
    private String format;

    @Option(names = {"--output", "-o"}, description = "Output directory (default: .)",
        paramLabel = "<dir>", defaultValue = ".")
    private Path outputDir;

    public DiagramCommand(SysMLTool parent) {
        this.parent = parent;
    }

    @Override
    public Integer call() {
        String fmt = format.toLowerCase();
        if (!fmt.equals("png") && !fmt.equals("svg") && !fmt.equals("puml")) {
            System.err.println("[ERROR] --format must be: png, svg, or puml");
            return 2;
        }

        // Collect all input files (expand directories)
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
                    System.out.printf("[INFO]  Found %d .sysml file(s) under: %s%n",
                        found.size(), input);
                    uniqueFiles.addAll(found);
                }
            } else {
                uniqueFiles.add(input);
            }
        }

        if (uniqueFiles.isEmpty()) {
            return totalErrors > 0 ? -1 : 0;
        }

        List<Path> files = new ArrayList<>(uniqueFiles);

        System.out.printf("%n%s%n  Generating diagrams for %d file(s)%n%s%n",
            "─".repeat(60), files.size(), "─".repeat(60));

        SysMLEngineHelper engine = new SysMLEngineHelper(parent.getLibraryPath());

        // Load all files into the ResourceSet (either single validate or validateAll)
        Map<Path, SysMLEngineHelper.ValidationResult> results;
        if (files.size() == 1) {
            Path file = files.get(0);
            results = Map.of(file, engine.validate(file));
        } else {
            results = engine.validateAll(files);
        }

        // Check for validation errors
        for (Map.Entry<Path, SysMLEngineHelper.ValidationResult> entry : results.entrySet()) {
            Path file = entry.getKey();
            SysMLEngineHelper.ValidationResult result = entry.getValue();
            
            long errorCount = result.parseErrors().stream()
                .filter(SysMLEngineHelper.ParseError::isError).count()
                + result.issues().stream()
                .filter(SysMLEngineHelper::isErrorSeverity).count();

            if (errorCount > 0) {
                System.err.printf("[ERROR] %d validation error(s) in %s — fix before generating diagrams.%n",
                    errorCount, file.getFileName());
                for (SysMLEngineHelper.ParseError pe : result.parseErrors()) {
                    if (pe.isError()) System.err.printf("  [x] %s%n", pe.message());
                }
                totalErrors++;
            }
        }

        if (totalErrors > 0) {
            return 1;
        }

        // At this point all files are loaded into the ResourceSet
        // Get all resources and extract elements for diagramming
        try {
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            System.err.printf("[ERROR] Cannot create %s: %s%n", outputDir, e);
            return 2;
        }

        try {
            // Collect all root elements from loaded resources
            List<EObject> allRoots = new ArrayList<>();
            for (Resource resource : engine.getResourceSet().getResources()) {
                if (resource.getURI().toString().contains("sysml.library")) continue; // skip stdlib
                if (!resource.getContents().isEmpty()) {
                    allRoots.add(resource.getContents().get(0));
                }
            }

            if (allRoots.isEmpty()) {
                System.err.println("[ERROR] No root elements found in loaded files.");
                return 1;
            }

            // Determine what to diagram
            if (allElements) {
                return generateAllElements(allRoots, fmt);
            } else if (elementName != null) {
                return generateNamedElement(allRoots, elementName, fmt);
            } else {
                return generateRootDiagrams(allRoots, files, fmt);
            }

        } catch (Exception e) {
            System.err.printf("[ERROR] Failed to generate PlantUML: %s%n", e.getMessage());
            if (Boolean.getBoolean("sysml.debug")) {
                e.printStackTrace();
            }
            return 1;
        }
    }

    // ── Diagram generation strategies ────────────────────────────────────────

    /**
     * Generate one diagram per root element (one per input file).
     */
    private int generateRootDiagrams(List<EObject> roots, List<Path> files, String fmt) throws Exception {
        System.out.printf("  Generating %d root diagram(s)...%n", roots.size());
        int generated = 0;
        
        for (int i = 0; i < roots.size() && i < files.size(); i++) {
            EObject root = roots.get(i);
            String name = files.get(i).getFileName().toString().replace(".sysml", "");
            writeDiagram(root, name, fmt);
            generated++;
        }
        
        System.out.printf("%n  Generated %d diagram(s)%n", generated);
        return 0;
    }

    /**
     * Find and diagram a specific named element across all loaded files.
     */
    private int generateNamedElement(List<EObject> roots, String name, String fmt) throws Exception {
        for (EObject root : roots) {
            EObject target = findByName(root, name);
            if (target != null) {
                writeDiagram(target, name, fmt);
                System.out.printf("%n  Generated diagram for '%s'%n", name);
                return 0;
            }
        }
        System.err.printf("[ERROR] Element '%s' not found in any loaded file.%n", name);
        return 1;
    }

    /**
     * Generate one diagram per top-level element across all loaded files.
     */
    private int generateAllElements(List<EObject> roots, String fmt) throws Exception {
        List<EObject> allMembers = new ArrayList<>();
        for (EObject root : roots) {
            allMembers.addAll(getTopLevelMembers(root));
        }

        if (allMembers.isEmpty()) {
            System.out.println("[WARN] No top-level owned members found.");
            return 0;
        }

        System.out.printf("  Generating %d diagram(s)...%n", allMembers.size());
        int generated = 0;
        int skipped = 0;

        for (EObject member : allMembers) {
            String name = getEObjectName(member);
            try {
                writeDiagram(member, name, fmt);
                generated++;
            } catch (Exception e) {
                System.err.printf("  [!] Skipped '%s': %s%n", name, e.getMessage());
                skipped++;
            }
        }

        System.out.printf("%n  Generated %d diagram(s), skipped %d%n", generated, skipped);
        return skipped > 0 ? 1 : 0;
    }

    // ── PlantUML generation ──────────────────────────────────────────────────

    private void writeDiagram(EObject element, String name, String fmt) throws Exception {
        String puml = generatePlantUML(element);
        if (puml == null || puml.isBlank()) {
            System.out.printf("[WARN]  Empty output for '%s' — skipping.%n", name);
            return;
        }
        if (!puml.contains("@startuml")) puml = "@startuml\n" + puml + "\n@enduml\n";

        String safe = name.replaceAll("[^A-Za-z0-9_\\-]", "_");
        if (fmt.equals("puml")) {
            Path out = outputDir.resolve(safe + ".puml");
            Files.writeString(out, puml, StandardCharsets.UTF_8);
            System.out.printf("  [OK]  %s%n", out);
        } else {
            FileFormat ff = fmt.equals("svg") ? FileFormat.SVG : FileFormat.PNG;
            Path out = outputDir.resolve(safe + "." + fmt);
            try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                new SourceStringReader(puml).outputImage(baos, new FileFormatOption(ff));
                Files.write(out, baos.toByteArray());
            }
            System.out.printf("  [OK]  %s%n", out);
        }
    }

    /**
     * Generate PlantUML for a single element using the SysML2PlantUMLText API.
     * 
     * The actual API is sysML2PUML(List<Element>) which takes a list of elements.
     * We wrap single elements in a list for compatibility.
     */
    static String generatePlantUML(EObject element) throws Exception {
        SysML2PlantUMLText viz = createViz();

        // Try the known List-based method first
        try {
            Method m = viz.getClass().getMethod("sysML2PUML", List.class);
            if (String.class.equals(m.getReturnType())) {
                List<Object> elements = List.of(element);
                Object result = m.invoke(viz, elements);
                return result != null ? result.toString() : null;
            }
        } catch (NoSuchMethodException e) {
            // Fall through to legacy single-element methods
        }

        // Legacy fallback: try single-EObject methods (older pilot versions)
        for (String methodName : new String[]{
                "doSwitch", "getPlantUMLString", "getPlantUML", "visualize",
                "getText", "generate", "toPlantUML", "apply"}) {
            try {
                Method m = viz.getClass().getMethod(methodName, EObject.class);
                if (String.class.equals(m.getReturnType())) {
                    Object result = m.invoke(viz, element);
                    return result != null ? result.toString() : null;
                }
            } catch (NoSuchMethodException ignored) {}
        }

        // Last resort: error with available methods
        System.err.println("[ERROR] No suitable visualization method found on SysML2PlantUMLText.");
        System.err.println("        Expected: String sysML2PUML(List)");
        System.err.println("        Available methods:");
        for (Method m : viz.getClass().getMethods()) {
            if (m.getDeclaringClass() == Object.class) continue;
            System.err.printf("          %s %s(%s)%n",
                m.getReturnType().getSimpleName(),
                m.getName(),
                Arrays.stream(m.getParameterTypes())
                    .map(Class::getSimpleName)
                    .collect(Collectors.joining(", ")));
        }
        throw new UnsupportedOperationException(
            "Cannot find sysML2PUML(List) method on SysML2PlantUMLText. " +
            "Check pilot implementation version compatibility.");
    }

    static SysML2PlantUMLText createViz() throws Exception {
        // Try each constructor, passing null for all args (link provider etc.)
        Constructor<?>[] ctors = SysML2PlantUMLText.class.getConstructors();
        if (ctors.length == 0)
            ctors = SysML2PlantUMLText.class.getDeclaredConstructors();
        for (Constructor<?> ctor : ctors) {
            try {
                ctor.setAccessible(true);
                Object[] args = new Object[ctor.getParameterCount()]; // all null
                return (SysML2PlantUMLText) ctor.newInstance(args);
            } catch (Exception ignored) {}
        }
        throw new IllegalStateException("Cannot instantiate SysML2PlantUMLText");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Recursively collects all *.sysml files under a directory.
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

    private EObject findByName(EObject root, String name) {
        if (name.equals(getEObjectName(root))) return root;
        for (EObject child : root.eContents()) {
            EObject found = findByName(child, name);
            if (found != null) return found;
        }
        return null;
    }

    /**
     * Returns the top-level owned members of the root element.
     * These are typically the main model elements (packages, parts, requirements, etc.)
     * that are direct children of the root namespace.
     */
    private List<EObject> getTopLevelMembers(EObject root) {
        List<EObject> members = new ArrayList<>();
        
        // Try to get owned members via reflection (API may vary)
        try {
            Method getOwnedMember = root.getClass().getMethod("getOwnedMember");
            Object result = getOwnedMember.invoke(root);
            if (result instanceof Iterable<?> it) {
                for (Object obj : it) {
                    if (obj instanceof EObject eo) members.add(eo);
                }
            }
        } catch (Exception ignored) {
            // Fallback: just use direct eContents()
            members.addAll(root.eContents());
        }
        
        return members;
    }

    static String getEObjectName(EObject obj) {
        for (String m : new String[]{"getName", "getDeclaredName"}) {
            try {
                Object v = obj.getClass().getMethod(m).invoke(obj);
                if (v instanceof String s && !s.isBlank()) return s;
            } catch (Exception ignored) {}
        }
        return obj.eClass().getName() + "_" + Integer.toHexString(obj.hashCode());
    }
}
