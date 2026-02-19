package org.example.sysml;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.resource.Resource;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

import static org.example.sysml.FileUtils.*;

/**
 * Lists View/Viewpoint elements from a SysML v2 file.
 *
 * This command only discovers and displays views defined in the model.
 * To render views as diagrams, use: diagram <file> --view <viewName>
 */
@Command(
    name = "views",
    mixinStandardHelpOptions = true,
    description = "List View/Viewpoint elements from a SysML v2 file"
)
public class ViewsCommand implements Callable<Integer> {
    private final SysMLTool parent;

    @Parameters(paramLabel = "<file>", description = ".sysml file to process", arity = "1..*")
    private List<Path> inputs;

    public ViewsCommand(SysMLTool parent) {
        this.parent = parent;
    }

    @Override
    public Integer call() {
        SysMLEngineHelper engine = new SysMLEngineHelper(parent.getLibraryPath());

        // Collect all input files (expand directories)
        Set<Path> uniqueFiles = new LinkedHashSet<>();
        int scanErrors = 0;

        for (Path input : inputs) {
            if (!Files.exists(input)) {
                System.err.printf("  [x] Path not found: %s%n", input);
                scanErrors++;
                continue;
            }
            if (Files.isDirectory(input)) {
                List<Path> found = collectSysmlFiles(input);
                if (found.isEmpty()) {
                    System.err.printf("  [!] No .sysml files found under: %s%n", input);
                } else {
                    System.out.printf("[INFO] Found %d .sysml file(s) under: %s%n", found.size(), input);
                    uniqueFiles.addAll(found);
                }
            } else {
                uniqueFiles.add(input);
            }
        }

        if (uniqueFiles.isEmpty()) {
            return scanErrors > 0 ? 1 : 0;
        }

        System.out.printf("%n%s%n  Loading %d files%n%s%n",
            "─".repeat(60), uniqueFiles.size(), "─".repeat(60));


        // Convert to list after deduplication
        List<Path> files = new ArrayList<>(uniqueFiles);

        // Load and validate all files (supports cross-file references)
        Map<Path, SysMLEngineHelper.ValidationResult> results = engine.validateAll(files);

        // Check for validation errors
        for (Map.Entry<Path, SysMLEngineHelper.ValidationResult> entry : results.entrySet()) {
            Path file = entry.getKey();
            SysMLEngineHelper.ValidationResult result = entry.getValue();

            long errorCount = result.parseErrors().stream()
                .filter(SysMLEngineHelper.ParseError::isError).count()
                + result.issues().stream()
                .filter(SysMLEngineHelper::isErrorSeverity).count();

            if (errorCount > 0) {
                System.err.printf("[ERROR] %d validation error(s) in %s — views may be incomplete%n",
                    errorCount, file.getFileName());
                for (SysMLEngineHelper.ParseError pe : result.parseErrors()) {
                    if (pe.isError()) System.err.printf("  [x] %s%n", pe.message());
                }
                scanErrors++;
            }
        }

        // Collect views from all loaded roots, using a Map to deduplicate by qualified name
        // This is necessary because the model loader appears to be creating duplicate
        // EObject models for the same logical content.
        Map<String, EObject> viewDefs   = new LinkedHashMap<>();
        Map<String, EObject> viewUsages = new LinkedHashMap<>();

        org.eclipse.emf.ecore.resource.ResourceSet activeRs = engine.getBatchResourceSet();
        if (activeRs == null) activeRs = engine.getResourceSet();

        for (Resource resource : new ArrayList<>(activeRs.getResources())) {
            if (resource.getURI().toString().contains("sysml.library")) {
                continue;
            }
            if (!resource.getURI().isFile()) {
                continue;
            }
            if (resource.getContents().isEmpty()) {
                continue;
            }

            EObject root = resource.getContents().get(0);
            collectViews(root, viewDefs, viewUsages);
        }

        System.out.printf("%n  Found %d ViewDefinition(s), %d ViewUsage(s)%n%n",
            viewDefs.size(), viewUsages.size());

        if (viewDefs.isEmpty() && viewUsages.isEmpty()) {
            System.out.println("  No views defined in any loaded file.");
            System.out.println();
            System.out.println("  Define views like:");
            System.out.println("    view def MyView { ... }");
            System.out.println("    view myView : MyView { expose ... }");
            System.out.println();
            System.out.println("  To render views as diagrams, use:");
            System.out.println("    diagram <file> --view <viewName>");
            return scanErrors > 0 ? 1 : 0;
        }

        if (!viewDefs.isEmpty()) {
            System.out.println("  ViewDefinitions:");
            for (EObject vd : viewDefs.values()) {
                System.out.printf("    %s%n", DiagramCommand.getEObjectName(vd));
                listChildren(vd, "        ");
            }
            System.out.println();
        }

        if (!viewUsages.isEmpty()) {
            System.out.println("  ViewUsages:");
            for (EObject vu : viewUsages.values()) {
                System.out.printf("    %s%s%n",
                    DiagramCommand.getEObjectName(vu), getTypeAnnotation(vu));
                listChildren(vu, "        ");
            }
            System.out.println();
        }

        System.out.println("  To render a view as diagram:");
        System.out.println("    diagram <path> --view <viewName> -o <output-dir>");

        return scanErrors > 0 ? 1 : 0;
    }

    private void collectViews(EObject obj, Map<String, EObject> defs, Map<String, EObject> usages) {
        String typeName = obj.eClass().getName();
        if (typeName.equals("ViewDefinition")) {
            defs.put(getQualifiedName(obj), obj);
        } else if (typeName.equals("ViewUsage")) {
            usages.put(getQualifiedName(obj), obj);
        }
        for (EObject child : obj.eContents()) {
            collectViews(child, defs, usages);
        }
    }

    private static String getQualifiedName(EObject obj) {
        if (obj == null) return "";
        List<String> parts = new ArrayList<>();
        for (EObject o = obj; o != null; o = o.eContainer()) {
            String name = DiagramCommand.getEObjectName(o);
            // Heuristic to stop at an unnamed root model element, which often has a generated name
            if (o.eContainer() == null && name.startsWith(o.eClass().getName() + "_")) {
                break;
            }
            parts.add(name);
        }
        Collections.reverse(parts);
        return String.join(".", parts);
    }

    private void listChildren(EObject el, String indent) {
        for (EObject child : el.eContents()) {
            String typeName = child.eClass().getName();

            // Handle 'expose' relationships, which are a form of Membership
            if (typeName.contains("Expose")) {
                EObject exposedTarget = null;
                // The actual exposed element is found via the 'importedElement' reference
                for (EReference ref : child.eClass().getEAllReferences()) {
                    if ("importedElement".equals(ref.getName())) {
                        Object target = child.eGet(ref);
                        if (target instanceof EObject) {
                            exposedTarget = (EObject) target;
                            break;
                        }
                    }
                }

                if (exposedTarget != null) {
                    System.out.printf("%sexpose: %s%s%n", indent,
                        DiagramCommand.getEObjectName(exposedTarget),
                        getTypeAnnotation(exposedTarget));
                }
            }
            // Other direct children of a view could be handled here if needed,
            // but for now, we focus on correctly listing exposed elements.
        }
    }

    private String getTypeAnnotation(EObject el) {
        try {
            Object types = el.getClass().getMethod("getType").invoke(el);
            if (types instanceof Iterable<?> it) {
                List<String> names = new ArrayList<>();
                for (Object t : it) {
                    if (t instanceof EObject eo) names.add(DiagramCommand.getEObjectName(eo));
                }
                if (!names.isEmpty()) return " : " + String.join(", ", names);
            }
        } catch (Exception ignored) {}
        return "";
    }
}
