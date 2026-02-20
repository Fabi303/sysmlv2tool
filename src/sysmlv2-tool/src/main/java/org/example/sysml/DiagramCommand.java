package org.example.sysml;

import net.sourceforge.plantuml.FileFormat;
import net.sourceforge.plantuml.FileFormatOption;
import net.sourceforge.plantuml.SourceStringReader;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import static org.example.sysml.FileUtils.*;

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
 *   diagram <path> --element <name>        -- diagram a specific named element
 *   diagram <path> --view <name>           -- diagram a specific view (ViewDefinition or ViewUsage)
 *   diagram <path> --all-elements          -- one diagram per top-level element
 *   diagram <path> --single               -- one diagram per element, packages become subfolders
 *
 * Note: --element and --view are mutually exclusive.
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

    @Option(names = {"--element", "-e"}, description = "Name of element to diagram", paramLabel = "<name>")
    private String elementName;

    @Option(names = {"--view", "-v"}, description = "Name of view (ViewDefinition/ViewUsage) to diagram", paramLabel = "<name>")
    private String viewName;

    @Option(names = {"--all-elements"}, description = "One diagram per top-level owned member")
    private boolean allElements;

    @Option(names = {"--single", "-s"}, description = "One diagram per element; packages become subfolders under the output directory")
    private boolean single;

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
        // Validate mutually exclusive options
        if (elementName != null && viewName != null) {
            System.err.println("[ERROR] --element and --view are mutually exclusive.");
            return 2;
        }
        if (single && allElements) {
            System.err.println("[ERROR] --single and --all-elements are mutually exclusive.");
            return 2;
        }

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
            org.eclipse.emf.ecore.resource.ResourceSet activeRs = engine.getBatchResourceSet();
            if (activeRs == null) activeRs = engine.getResourceSet();
            for (Resource resource : new ArrayList<>(activeRs.getResources())) {
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
            if (viewName != null) {
                return generateView(allRoots, viewName, fmt);
            } else if (allElements) {
                return generateAllElements(allRoots, fmt);
            } else if (single) {
                return generateSingleFiles(allRoots, fmt);
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
                System.out.printf("%n  Generated diagram for element '%s'%n", name);
                return 0;
            }
        }
        System.err.printf("[ERROR] Element '%s' not found in any loaded file.%n", name);
        return 1;
    }

/**
     * Findet und diagrammiert eine spezifische View (ViewDefinition oder ViewUsage) nach Namen.
     * Anstatt die View selbst darzustellen, werden die von der View exponierten Elemente diagrammiert.
     */
    private int generateView(List<EObject> roots, String name, String fmt) throws Exception {
        for (EObject root : roots) {
            EObject view = findView(root, name);
            if (view != null) {
                // Exposed Elements aus der View extrahieren
                List<EObject> exposedElements = getExposedElements(view);
                
                if (exposedElements.isEmpty()) {
                    System.err.printf("[WARN] View '%s' has no exposed elements%n", name);
                    return 1;
                }
                
                // Diagramm für alle exponierten Elemente generieren
                writeDiagram(exposedElements, name, fmt);
                System.out.printf("[INFO] Generated diagram for view '%s' with %d exposed elements%n", 
                                  name, exposedElements.size());
                return 0;
            }
        }
        System.err.printf("[ERROR] View '%s' not found in any loaded file%n", name);
        System.err.println("        Use 'views <path>' to list available views.");
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

    /**
     * Generate one diagram per named element, mirroring the package hierarchy as subfolders.
     * Package/LibraryPackage/Namespace elements become directories; all other named elements
     * become individual diagram files named after the element.
     */
    private int generateSingleFiles(List<EObject> roots, String fmt) throws Exception {
        int[] counts = {0, 0}; // [generated, skipped]
        // Tracks how many times each base name has been used per directory so that
        // duplicate contextual names get a numeric suffix (_2, _3, …).
        Map<Path, Map<String, Integer>> nameCounters = new LinkedHashMap<>();
        System.out.println("  Generating diagrams (one per element, packages as subfolders)...");
        for (EObject root : roots) {
            generateSingleRecursive(root, outputDir, fmt, counts, nameCounters);
        }
        System.out.printf("%n  Generated %d diagram(s), skipped %d%n", counts[0], counts[1]);
        return counts[1] > 0 ? 1 : 0;
    }

    /**
     * Recursively walks an element tree.
     * Named package-like elements (Package, LibraryPackage, Namespace) create a
     * subdirectory and the walk continues inside it.
     * Unnamed package-like elements (e.g. the synthetic root Namespace that the
     * SysML v2 Xtext parser wraps every file in) are treated as transparent: the
     * walk continues at the same directory level so no hash-named folder appears.
     * All other named elements produce one diagram file.
     */
    private void generateSingleRecursive(EObject element, Path currentDir, String fmt,
                                         int[] counts, Map<Path, Map<String, Integer>> nameCounters) {
        String typeName = element.eClass().getName();

        if (isPackageLike(typeName)) {
            String realName = getRealName(element);
            if (realName == null) {
                // Anonymous/synthetic container (e.g. file-root Namespace): recurse
                // transparently so no hash-named directory appears in the output.
                for (EObject member : getTopLevelMembers(element)) {
                    generateSingleRecursive(member, currentDir, fmt, counts, nameCounters);
                }
            } else {
                String safeName = realName.replaceAll("[^A-Za-z0-9_\\-]", "_");
                Path subDir = currentDir.resolve(safeName);
                try {
                    Files.createDirectories(subDir);
                } catch (IOException e) {
                    System.err.printf("  [!] Cannot create directory '%s': %s%n", subDir, e.getMessage());
                    counts[1]++;
                    return;
                }
                for (EObject member : getTopLevelMembers(element)) {
                    generateSingleRecursive(member, subDir, fmt, counts, nameCounters);
                }
            }
        } else {
            String baseName  = getContextualName(element);
            String elementName = uniqueName(baseName, currentDir, nameCounters);
            try {
                writeDiagram(element, elementName, fmt, currentDir);
                counts[0]++;
            } catch (Exception e) {
                System.err.printf("  [!] Skipped '%s': %s%n", elementName, e.getMessage());
                counts[1]++;
            }
        }
    }

    /**
     * Returns {@code baseName} on its first use within {@code dir}, and
     * {@code baseName_2}, {@code baseName_3}, … on subsequent uses.
     * This prevents sibling elements of the same unnamed type (e.g. two Comments
     * inside the same requirement) from colliding on the same output file.
     */
    private static String uniqueName(String baseName, Path dir,
                                     Map<Path, Map<String, Integer>> nameCounters) {
        Map<String, Integer> dirMap = nameCounters.computeIfAbsent(dir, k -> new LinkedHashMap<>());
        int count = dirMap.merge(baseName, 1, Integer::sum);
        return count == 1 ? baseName : baseName + "_" + count;
    }

    /** Returns true for element types that should map to a directory rather than a file. */
    private static boolean isPackageLike(String typeName) {
        return typeName.equals("Package")
            || typeName.equals("LibraryPackage")
            || typeName.equals("Namespace");
    }

    private List<EObject> getExposedElements(EObject view) {
    List<EObject> elements = new ArrayList<>();

    // Iterate through all direct children of the view
    for (EObject child : view.eContents()) {
        // We are looking for 'Expose' relationships (a type of Membership)
        if (!child.eClass().getName().contains("Expose")) {
            continue;
        }

        try {
            EObject exposedTarget = null;

            // The target of an Expose relationship is held in the 'importedElement' reference
            // for this version of the SysML pilot implementation.
            for (EReference ref : child.eClass().getEAllReferences()) {
                if ("importedElement".equals(ref.getName())) {
                    Object target = child.eGet(ref);
                    if (target instanceof EObject) {
                        exposedTarget = (EObject) target;
                        break; // Found it
                    }
                }
            }

            if (exposedTarget != null) {
                // Add the actual target element (e.g., a Requirement or Part),
                // not the Expose membership itself.
                elements.add(exposedTarget);
                if (Boolean.getBoolean("sysml.debug")) {
                    System.out.printf("[DEBUG] View '%s' exposes element: %s%n",
                        getEObjectName(view), getEObjectName(exposedTarget));
                }
            } else if (Boolean.getBoolean("sysml.debug")) {
                System.err.printf("[DEBUG] Could not find target of '%s' in View '%s'%n",
                    getEObjectName(child), getEObjectName(view));
            }

        } catch (Exception e) {
            System.err.printf("[WARN] Could not resolve exposure in view for element %s: %s%n",
                getEObjectName(child), e.getMessage());
        }
    }
    return elements;
}

    // ── PlantUML generation ──────────────────────────────────────────────────

    /**
     * Walks the EObject tree and collects every FeatureValue's Java identity hash
     * mapped to the string extracted from its owned Expression child.
     * This is used to replace the garbage FeatureValueImpl@hash toString() output
     * that SysML2PlantUMLText emits instead of the actual literal value.
     */
    private static Map<String, String> buildFeatureValueMap(Object target) {
        Map<String, String> map = new LinkedHashMap<>();
        if (target instanceof List<?> list) {
            for (Object el : list)
                if (el instanceof EObject eo) collectFeatureValues(eo, map);
        } else if (target instanceof EObject eo) {
            collectFeatureValues(eo, map);
        }
        return map;
    }

    private static void collectFeatureValues(EObject el, Map<String, String> map) {
        if ("FeatureValue".equals(el.eClass().getName())) {
            String hash = Integer.toHexString(el.hashCode());
            String val = extractValueFromFeatureValue(el);
            if (val != null) map.put(hash, val);
        }
        for (EObject child : el.eContents())
            collectFeatureValues(child, map);
    }

    /**
     * Extracts the literal value string from a FeatureValue EObject.
     * FeatureValue owns its Expression child(ren) as containment; eContents()
     * returns them. Each expression (LiteralString, LiteralBoolean, etc.) has
     * a getValue() method returning the actual Java primitive/String.
     */
    private static String extractValueFromFeatureValue(EObject fv) {
        // Primary path: FeatureValue owns the Expression via containment
        for (EObject child : fv.eContents()) {
            try {
                Object val = child.getClass().getMethod("getValue").invoke(child);
                if (val != null) return String.valueOf(val);
            } catch (Exception ignored) {}
        }
        // Fallback: look for EReference "value" or "ownedRelatedElement"
        for (EReference ref : fv.eClass().getEAllReferences()) {
            String rn = ref.getName();
            if (!"value".equals(rn) && !"ownedRelatedElement".equals(rn)) continue;
            try {
                Object obj = fv.eGet(ref);
                if (obj instanceof java.util.List<?> list) {
                    for (Object item : list) {
                        if (!(item instanceof EObject expr)) continue;
                        try {
                            Object val = expr.getClass().getMethod("getValue").invoke(expr);
                            if (val != null) return String.valueOf(val);
                        } catch (Exception ignored) {}
                    }
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    /**
     * Replaces the garbage FeatureValueImpl@hash toString() emitted by SysML2PlantUMLText
     * with the actual literal value extracted from the same live EObject.
     *
     * SysML2PlantUMLText encodes '=' as {@code <U+003D>} to avoid PlantUML parser
     * conflicts, so the pattern to match is:
     *   attrName <U+003D> org.omg.sysml…FeatureValueImpl@1f8d6aa (aliasIds: …)
     *
     * The hash in the string is the Java identity hash of the FeatureValue object,
     * which is the same object we walked in buildFeatureValueMap(), so we can look
     * up the extracted value by hash.
     */
    private static String sanitizePlantUML(String puml, Map<String, String> fvMap) {
        if (puml == null) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
            "(?:<U\\+003D>|=)\\s*org\\.omg\\.\\S+@([0-9a-fA-F]+)[^\\n]*"
        ).matcher(puml);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String val = fvMap.getOrDefault(m.group(1), "unknown value");
            // Empty string values stay as "" so it's clear the field is present but empty
            String display = val.isEmpty() ? "\"\"" : val;
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(
                "<U+003D> " + display));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private void writeDiagram(Object target, String name, String fmt) throws Exception {
        writeDiagram(target, name, fmt, outputDir);
    }

    private void writeDiagram(Object target, String name, String fmt, Path dir) throws Exception {

        String puml = "";
        if (target instanceof List<?> list && !list.isEmpty()) {
            puml = generatePlantUML(list);
        } else if (target instanceof EObject element) {
            puml = generatePlantUML(element);
        } else {
            throw new IllegalArgumentException("Invalid target type: " + target.getClass());
        }
        puml = sanitizePlantUML(puml, buildFeatureValueMap(target));
        if (!puml.contains("@startuml")) puml = "@startuml\n" + puml + "\n@enduml\n";

        String safe = name.replaceAll("[^A-Za-z0-9_\\-]", "_");
        if (fmt.equals("puml")) {
            Path out = dir.resolve(safe + ".puml");
            Files.writeString(out, puml, StandardCharsets.UTF_8);
            System.out.printf("  [OK]  %s%n", out);
        } else {
            FileFormat ff = fmt.equals("svg") ? FileFormat.SVG : FileFormat.PNG;
            Path out = dir.resolve(safe + "." + fmt);
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

    /**
     * Generiert PlantUML für mehrere Elemente.
     */
    static String generatePlantUML(List<?> elements) throws Exception {
        SysML2PlantUMLText viz = createViz();
        List<EObject> elementList = new ArrayList<>();
        for (Object el : elements) {
            if (el instanceof EObject eo) {
                elementList.add(eo);
            }
        }
        if (elementList.isEmpty()) return "";

        try {
            // The primary method for batch processing is sysML2PUML(List)
            Method m = viz.getClass().getMethod("sysML2PUML", List.class);
            if (String.class.equals(m.getReturnType())) {
                Object result = m.invoke(viz, elementList);
                return result != null ? result.toString() : null;
            }
            throw new NoSuchMethodException("Method sysML2PUML(List) found, but returns "
                + m.getReturnType().getSimpleName() + " instead of String");

        } catch (NoSuchMethodException e) {
            System.err.println("[ERROR] No suitable visualization method found on SysML2PlantUMLText for multiple elements.");
            System.err.println("        A method 'String sysML2PUML(List)' is required for generating diagrams from views.");
            System.err.println("        Available methods on " + viz.getClass().getName() + ":");
            for (Method m : viz.getClass().getMethods()) {
                if (m.getDeclaringClass() == Object.class) continue;
                System.err.printf("          - %s %s(%s)%n",
                    m.getReturnType().getSimpleName(),
                    m.getName(),
                    Arrays.stream(m.getParameterTypes())
                        .map(Class::getSimpleName)
                        .collect(Collectors.joining(", ")));
            }
            throw new UnsupportedOperationException(
                "Cannot find required 'sysML2PUML(List)' method on SysML2PlantUMLText. " +
                "Please check pilot implementation version compatibility.", e);
        }
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

    private EObject findByName(EObject root, String name) {
        if (name.equals(getEObjectName(root))) return root;
        for (EObject child : root.eContents()) {
            EObject found = findByName(child, name);
            if (found != null) return found;
        }
        return null;
    }

    /**
     * Find a view (ViewDefinition or ViewUsage) by name.
     */
    private EObject findView(EObject root, String name) {
        String typeName = root.eClass().getName();
        if ((typeName.equals("ViewDefinition") || typeName.equals("ViewUsage"))
                && name.equals(getEObjectName(root))) {
            return root;
        }
        for (EObject child : root.eContents()) {
            EObject found = findView(child, name);
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

    /**
     * Returns the user-defined name of an EObject, or null if it has none.
     * Used to distinguish real names from synthetic fallback identifiers.
     */
    private static String getRealName(EObject obj) {
        for (String m : new String[]{"getName", "getDeclaredName"}) {
            try {
                Object v = obj.getClass().getMethod(m).invoke(obj);
                if (v instanceof String s && !s.isBlank()) return s;
            } catch (Exception ignored) {}
        }
        return null;
    }

    static String getEObjectName(EObject obj) {
        String name = getRealName(obj);
        return name != null ? name : obj.eClass().getName() + "_" + Integer.toHexString(obj.hashCode());
    }

    /**
     * Returns a human-readable name for an element in the context of --single output.
     * If the element has a user-defined name it is returned as-is.
     * Otherwise the nearest named ancestor (via eContainer chain) is used as a prefix:
     *   e.g. a Comment inside TSC001 → "TSC001_Comment"
     *        a Documentation inside TSC001 → "TSC001_Documentation"
     * If no named ancestor exists the final fallback is "TypeName_hash".
     */
    private static String getContextualName(EObject obj) {
        String realName = getRealName(obj);
        if (realName != null) return realName;

        String typeName = obj.eClass().getName();
        EObject container = obj.eContainer();
        while (container != null) {
            String containerName = getRealName(container);
            if (containerName != null) {
                return containerName + "_" + typeName;
            }
            container = container.eContainer();
        }
        return typeName + "_" + Integer.toHexString(obj.hashCode());
    }
}
