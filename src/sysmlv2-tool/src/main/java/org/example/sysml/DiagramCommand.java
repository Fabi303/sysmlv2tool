package org.example.sysml;

import net.sourceforge.plantuml.FileFormat;
import net.sourceforge.plantuml.FileFormatOption;
import net.sourceforge.plantuml.SourceStringReader;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.omg.sysml.lang.sysml.impl.FeatureValueImpl;
import org.omg.sysml.plantuml.SysML2PlantUMLText;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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

    @Option(names = {"--nostdlib"}, description = "Skip processing standard library resources")
    private boolean skipStdlib;


    private static final int MAX_PLANTUML_SIZE = 65536; // 64KB, PlantUML's practical limit

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
            if(Files.exists(outputDir)) {
                System.err.printf("[ERROR] Cannot create '%s' %s%n, a file with the same name exists.", outputDir, e.getMessage());
            } else {
                System.err.printf("[ERROR] Cannot create %s: %s%n", outputDir, e.getMessage());
            }
            return 2;
        }

        try {
            // Collect all root elements from loaded resources
            List<EObject> allRoots = new ArrayList<>();
            for (Resource resource : engine.getResourceSet().getResources()) {

                //skip stdlib resources if --nostdlib is set
                if (skipStdlib && isStandardLibraryResource(resource)) {
                    String uriString = resource.getURI().toString();
                    String decodedUri = decodeUri(uriString);
                    System.out.printf("[INFO] Skipping standard library resource: %s%n", decodedUri );
                    continue;
                }

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

    //Helper to decode URIs that may be percent-encoded, for better readability in logs and error messages
    private static String decodeUri(String uriString) {
        try {
            URI uri = new URI(uriString);
            return URLDecoder.decode(uri.toString(), StandardCharsets.UTF_8);
        } catch (URISyntaxException e) {
            return uriString;
        }
    }

    // ── Diagram generation strategies ────────────────────────────────────────

    //Find any Resource that looks like a standard library
    private boolean isStandardLibraryResource(Resource resource) {
        String uriStr = resource.getURI().toString().toLowerCase();
        return uriStr.contains("sysml.library") || 
               uriStr.contains("/standard-library") || 
               uriStr.contains("/library/");
    }

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


    /**
    * Extracts a meaningful string representation from a FeatureValueImpl object.
    * If it's not a FeatureValueImpl, returns the original object's toString().
    */
    private static String extractFeatureValue(Object obj) {
        if (obj instanceof FeatureValueImpl) {
            FeatureValueImpl feature = (FeatureValueImpl) obj;
            // Try to get the actual value if available
            Object value = feature.getValue(); // Hypothetical method
            if (value != null) {
                return value.toString();
            }
            // Fallback to feature name if available
            String name = getFeatureName(feature);
            return name != null ? name : "unknown";
        }
        return obj != null ? obj.toString() : "null";
    }

    /**
     * Tries to get the name of a feature, handling various possible method names.
     */
    private static String getFeatureName(FeatureValueImpl feature) {
        for (String methodName : new String[]{"getName", "getDeclaredName", "getFeatureName"}) {
            try {
                Method m = feature.getClass().getMethod(methodName);
                Object result = m.invoke(feature);
                if (result instanceof String s && !s.isBlank()) {
                    return s;
                }
            } catch (Exception ignored) {}
        }
        return null;
    }


    // ── PlantUML generation ──────────────────────────────────────────────────

    private void writeDiagram(EObject element, String name, String fmt) throws Exception {
        String puml = generatePlantUML(element);
        if (puml == null || puml.isBlank()) {
            System.out.printf("[WARN]  Empty output for '%s' — skipping.%n", name);
            return;
        }
        if (puml.length() > MAX_PLANTUML_SIZE) {
            System.out.printf("[WARN]  Diagram for '%s' is too large (%d chars) — skipping.%n", name, puml.length());
            return;
        }

        if (!puml.contains("@startuml")) puml = "@startuml\n" + puml + "\n@enduml\n";

        String safe = name.replaceAll("[^A-Za-z0-9_\\-]", "_");
        Path out = null;
        int suffix = 0;

        // If the file already exists, append a numeric suffix to avoid overwriting
        while (true) {
                String filename = suffix > 0 
                    ? safe + "_" + suffix + "." + fmt
                    : safe + "." + fmt;
                
                out = outputDir.resolve(filename);
                if (Files.notExists(out)) break;
                suffix++;
        }

        //Should never happen due to suffix logic, but check just in case
        if (Files.exists(out)) {
            System.err.printf("[ERROR] File already exists and cannot be overwritten: %s%n", out);
            return;
        }

        // Write to a temp file first, then move to final location to avoid partial files on error
        Path tempFile = Files.createTempFile(outputDir, safe, ".tmp");

        if (fmt.equals("puml")) {
            Files.writeString(tempFile, puml, StandardCharsets.UTF_8);
            System.out.printf("  [OK]  %s%n", out);
        } else {
            FileFormat ff = fmt.equals("svg") ? FileFormat.SVG : FileFormat.PNG;
            try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                new SourceStringReader(puml).outputImage(baos, new FileFormatOption(ff));
                Files.write(tempFile, baos.toByteArray());
            } catch (IOException e) {
                System.err.printf("[ERROR] Failed to write diagram for '%s': %s%n", name, e.getMessage());
                return;
            }
            finally {
                Files.move(tempFile, out, StandardCopyOption.REPLACE_EXISTING);
                Files.deleteIfExists(tempFile);
                System.out.printf("  [OK]  %s%n", out);
            }
        }
    }

    /**
     * Cleans up PlantUML output by replacing FeatureValueImpl references with meaningful values.
     */
    private static String cleanFeatureReferences(String puml) {
        // Replace object references with cleaner format
        String cleaned = puml.replaceAll("org\\.omg\\.sysml\\.lang\\.sysml\\.impl\\.FeatureValueImpl@[0-9a-f]+", "unknown value");
        
        // Remove redundant null declarations
        cleaned = cleaned.replaceAll("\\(aliasIds: null, declaredShortName: null, declaredName: null, isImpliedIncluded: false\\)", "");
        cleaned = cleaned.replaceAll("\\(isImplied: false\\) \\(memberShortName: null, memberName: null, visibility: public\\) \\(isInitial: false, isDefault: false\\)", "");
        
        // Clean up boolean values
        cleaned = cleaned.replaceAll("true", "yes");
        cleaned = cleaned.replaceAll("false", "no");
        
        return cleaned;
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

                // Clean up the result if it contains object references
                if (result != null && result.toString().contains("FeatureValueImpl")) {
                    return cleanFeatureReferences(result.toString());
                }

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

                    // Clean up the result if it contains object references
                    if (result != null && result.toString().contains("FeatureValueImpl")) {
                        return cleanFeatureReferences(result.toString());
                    }
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
            if ((null != result) && (result instanceof Iterable<?> it)) {
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
