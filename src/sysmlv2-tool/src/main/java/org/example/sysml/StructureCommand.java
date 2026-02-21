package org.example.sysml;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.json.JSONArray;
import org.json.JSONObject;
import org.omg.sysml.lang.sysml.Dependency;
import org.omg.sysml.lang.sysml.Namespace;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;

import static org.example.sysml.FileUtils.collectSysmlFiles;

/**
 * Displays the structural outline of one or more SysML v2 files.
 *
 * The tree shows element names and their metatype in square brackets.
 * Pure content nodes (Documentation, Comment, FeatureValue, …) are omitted.
 * Relation information (dependency, satisfy, verify, derive, allocate,
 * subset, redefine) is printed/encoded separately so the output also
 * serves as a quick dependency/traceability overview.
 *
 * Usage:
 *   structure <path>                    -- ASCII tree + relations (default)
 *   structure <path> -f json            -- JSON output
 *   structure <path> --relations        -- relations section only (text)
 *   structure <path> --relations -f json -- relations only (JSON)
 */
@Command(
    name = "structure",
    mixinStandardHelpOptions = true,
    description = "Display SysML v2 document structure as ASCII tree or JSON, " +
                  "including named elements and relation information"
)
public class StructureCommand implements Callable<Integer> {

    private final SysMLTool parent;

    @Parameters(
        paramLabel = "<path>",
        description = "One or more .sysml files or directories to scan recursively",
        arity = "1..*"
    )
    private List<Path> inputs;

    @Option(
        names = {"-f", "--format"},
        defaultValue = "text",
        description = "Output format: text (default) or json",
        paramLabel = "<fmt>"
    )
    private String format;

    @Option(
        names = {"--relations"},
        description = "Print only the relations section; omit the element tree"
    )
    private boolean relationsOnly;

    public StructureCommand(SysMLTool parent) {
        this.parent = parent;
    }

    // ── Element types that carry content/metadata, not structure ─────────────

    /**
     * EClass names for nodes that contain only content (documentation, literal
     * values, annotations) and must not appear in the structural tree.
     */
    private static final Set<String> CONTENT_TYPES = Set.of(
        "Documentation",
        "Comment",
        "TextualRepresentation",
        "Annotation",
        "FeatureValue"
    );

    // ── Relation kind registry ────────────────────────────────────────────────

    /**
     * Maps EClass names of relationship elements to a human-readable relation
     * kind label used in the output.
     *
     * FeatureTyping is intentionally excluded — it would produce one entry per
     * typed feature (": Type" syntax) and make the output very verbose.
     */
    private static final Map<String, String> RELATION_KINDS = Map.of(
        "Dependency",              "dependency",
        "SatisfyRequirementUsage", "satisfy",
        "VerifyRequirementUsage",  "verify",
        "DeriveReqtUsage",         "derive",
        "AllocationUsage",         "allocate",
        "Subsetting",              "subset",
        "Redefinition",            "redefine"
    );

    // ── Entry point ──────────────────────────────────────────────────────────

    @Override
    public Integer call() {
        String fmt = format.toLowerCase();
        if (!fmt.equals("text") && !fmt.equals("json")) {
            System.err.println("[ERROR] --format must be: text or json");
            return 2;
        }

        // ── Collect files ────────────────────────────────────────────────────
        Set<Path> uniqueFiles = new LinkedHashSet<>();
        int errors = 0;

        for (Path input : inputs) {
            if (!Files.exists(input)) {
                System.err.printf("[ERROR] Path not found: %s%n", input);
                errors++;
                continue;
            }
            if (Files.isDirectory(input)) {
                List<Path> found = collectSysmlFiles(input);
                if (found.isEmpty()) {
                    System.err.printf("[WARN] No .sysml files found under: %s%n", input);
                } else {
                    uniqueFiles.addAll(found);
                }
            } else {
                uniqueFiles.add(input);
            }
        }

        if (uniqueFiles.isEmpty()) {
            return errors > 0 ? -1 : 0;
        }

        // ── Load and validate ────────────────────────────────────────────────
        List<Path> files = new ArrayList<>(uniqueFiles);
        SysMLEngineHelper engine = new SysMLEngineHelper(parent.getLibraryPath());

        Map<Path, SysMLEngineHelper.ValidationResult> results =
            files.size() == 1
                ? Map.of(files.get(0), engine.validate(files.get(0)))
                : engine.validateAll(files);

        for (Map.Entry<Path, SysMLEngineHelper.ValidationResult> entry : results.entrySet()) {
            long errorCount = entry.getValue().parseErrors().stream()
                    .filter(SysMLEngineHelper.ParseError::isError).count()
                + entry.getValue().issues().stream()
                    .filter(SysMLEngineHelper::isErrorSeverity).count();
            if (errorCount > 0) {
                System.err.printf("[ERROR] %d validation error(s) in %s — fix before displaying structure.%n",
                    errorCount, entry.getKey().getFileName());
                errors++;
            }
        }

        if (errors > 0) return 1;

        // ── Collect document roots (skip standard library resources) ─────────
        ResourceSet rs = engine.getBatchResourceSet();
        if (rs == null) rs = engine.getResourceSet();

        List<EObject> roots = new ArrayList<>();
        for (Resource resource : new ArrayList<>(rs.getResources())) {
            if (resource.getURI().toString().contains("sysml.library")) continue;
            if (!resource.getContents().isEmpty()) {
                roots.add(resource.getContents().get(0));
            }
        }

        if (roots.isEmpty()) {
            System.err.println("[ERROR] No root elements found in loaded files.");
            return 1;
        }

        // ── Collect all relations across every loaded resource ───────────────
        List<RelationInfo> relations = new ArrayList<>();
        Set<EObject> visited = new HashSet<>();
        for (EObject root : roots) {
            collectRelations(root, relations, visited);
        }

        // ── Render ───────────────────────────────────────────────────────────
        if ("json".equals(fmt)) {
            printJson(roots, relations);
        } else { // "text"
            printAscii(roots, relations);
        }

        return 0;
    }

    // ── ASCII rendering ──────────────────────────────────────────────────────

    private void printAscii(List<EObject> roots, List<RelationInfo> relations) {
        if (!relationsOnly) {
            for (EObject root : roots) {
                // Unnamed synthetic file-level namespaces are transparent wrappers;
                // render their children directly so no "[Namespace]" stub appears.
                if (root instanceof Namespace && getElementName(root) == null) {
                    List<EObject> topLevel = structuralChildren(root);
                    for (int i = 0; i < topLevel.size(); i++) {
                        renderAscii(topLevel.get(i), "", i == topLevel.size() - 1, true);
                    }
                } else {
                    renderAscii(root, "", true, true);
                }
            }
        }

        if (!relations.isEmpty()) {
            if (!relationsOnly) System.out.println(); // blank separator after tree
            System.out.println("Relations:");
            for (RelationInfo rel : relations) {
                System.out.printf("  %-40s -[%-11s]-> %s%n",
                    rel.from(), rel.kind(), rel.to());
            }
        }
    }

    /**
     * Recursively renders one element as an ASCII tree node.
     *
     * @param node      element to render
     * @param prefix    indentation prefix built by the caller
     * @param isLast    whether this is the last sibling (selects └── vs ├──)
     * @param isRoot    whether this is a top-level root (no connector printed)
     */
    private void renderAscii(EObject node, String prefix, boolean isLast, boolean isRoot) {
        if (isContentOnly(node)) return;

        if (!isRoot) {
            String connector = isLast ? "└── " : "├── ";
            System.out.println(prefix + connector + elementLabel(node));
        } else {
            System.out.println(elementLabel(node));
        }

        String childPrefix = isRoot ? "" : (isLast ? "    " : "│   ");
        List<EObject> children = structuralChildren(node);
        for (int i = 0; i < children.size(); i++) {
            renderAscii(children.get(i), prefix + childPrefix, i == children.size() - 1, false);
        }
    }

    // ── JSON rendering ───────────────────────────────────────────────────────

    private void printJson(List<EObject> roots, List<RelationInfo> relations) {
        JSONObject output = new JSONObject();

        if (!relationsOnly) {
            JSONArray structure = new JSONArray();
            for (EObject root : roots) {
                if (root instanceof Namespace && getElementName(root) == null) {
                    // Transparent unnamed wrapper: promote children to the top level
                    for (EObject child : structuralChildren(root)) {
                        JSONObject node = buildJsonNode(child);
                        if (node != null) structure.put(node);
                    }
                } else {
                    JSONObject node = buildJsonNode(root);
                    if (node != null) structure.put(node);
                }
            }
            output.put("structure", structure);
        }

        JSONArray rels = new JSONArray();
        for (RelationInfo rel : relations) {
            JSONObject r = new JSONObject();
            r.put("kind", rel.kind());
            r.put("from", rel.from());
            r.put("to",   rel.to());
            rels.put(r);
        }
        output.put("relations", rels);

        System.out.println(output.toString(2));
    }

    private JSONObject buildJsonNode(EObject node) {
        if (isContentOnly(node)) return null;

        JSONObject obj = new JSONObject();
        obj.put("type", node.eClass().getName());

        String name = getElementName(node);
        if (name != null) obj.put("name", name);

        JSONArray children = new JSONArray();
        for (EObject child : structuralChildren(node)) {
            JSONObject childObj = buildJsonNode(child);
            if (childObj != null) children.put(childObj);
        }
        if (children.length() > 0) obj.put("children", children);

        return obj;
    }

    // ── Relation extraction ──────────────────────────────────────────────────

    /** Value object for a resolved relation triple. */
    record RelationInfo(String kind, String from, String to) {}

    /**
     * Walks the entire EObject containment tree and collects relation triples.
     * Uses a visited set to avoid revisiting cross-referenced elements.
     */
    private void collectRelations(EObject node, List<RelationInfo> result, Set<EObject> visited) {
        if (!visited.add(node)) return;

        String typeName = node.eClass().getName();
        String kind = RELATION_KINDS.get(typeName);
        if (kind != null) {
            String from = resolveRelationSource(node, kind);
            String to   = resolveRelationTarget(node, kind);
            if (from != null && to != null) {
                result.add(new RelationInfo(kind, from, to));
            }
        }

        for (EObject child : node.eContents()) {
            collectRelations(child, result, visited);
        }
    }

    /** Returns the source element name for a given relation node. */
    private String resolveRelationSource(EObject node, String kind) {
        // Dependency carries explicit client/supplier lists
        if ("dependency".equals(kind) && node instanceof Dependency dep) {
            return dep.getClient().stream()
                .map(this::getElementName)
                .filter(Objects::nonNull)
                .findFirst().orElse(null);
        }
        // Subsetting/Redefinition: the subsetting/redefining feature IS the source
        if ("subset".equals(kind))   return reflectName(node, "getSubsettingFeature");
        if ("redefine".equals(kind)) return reflectName(node, "getRedefiningFeature");

        // For usage-based relations (satisfy, verify, derive, allocate):
        // the source is the element that owns this usage (skip membership wrappers)
        return semanticOwnerName(node);
    }

    /** Returns the target element name for a given relation node. */
    private String resolveRelationTarget(EObject node, String kind) {
        // Dependency carries explicit client/supplier lists
        if ("dependency".equals(kind) && node instanceof Dependency dep) {
            return dep.getSupplier().stream()
                .map(this::getElementName)
                .filter(Objects::nonNull)
                .findFirst().orElse(null);
        }

        // Try kind-specific semantic getters first
        String target = switch (kind) {
            case "satisfy"  -> reflectName(node, "getSatisfiedRequirement");
            case "verify"   -> reflectName(node, "getVerifiedRequirement");
            case "derive"   -> reflectName(node, "getDerivedRequirement");
            case "allocate" -> reflectName(node, "getAllocatedTo");
            case "subset"   -> reflectName(node, "getSubsettedFeature");
            case "redefine" -> reflectName(node, "getRedefinedFeature");
            default         -> null;
        };

        if (target != null) return target;

        // Fallback: first non-containment, non-derived cross-reference to a named element
        return firstNamedCrossRef(node);
    }

    /**
     * Walks up the eContainer chain, skipping synthetic membership/relationship
     * wrappers, until a semantically named element is found.
     */
    private String semanticOwnerName(EObject node) {
        EObject cursor = node.eContainer();
        while (cursor != null) {
            String cType = cursor.eClass().getName();
            if (cType.endsWith("Membership") || cType.endsWith("Relationship")) {
                cursor = cursor.eContainer();
                continue;
            }
            String name = getElementName(cursor);
            if (name != null) return name;
            cursor = cursor.eContainer();
        }
        return null;
    }

    /**
     * Invokes a no-arg getter method on {@code node} via reflection and returns
     * the name of the resulting element.  Silently returns {@code null} on any
     * error so callers can fall through to alternative strategies.
     */
    private String reflectName(EObject node, String methodName) {
        try {
            Object result = node.getClass().getMethod(methodName).invoke(node);
            if (result instanceof EObject eo) return getElementName(eo);
            if (result instanceof List<?> list && !list.isEmpty()
                    && list.get(0) instanceof EObject eo) return getElementName(eo);
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * Returns the name of the first non-containment, non-derived EReference
     * target that has a user-defined name.  Used as a last-resort fallback for
     * relation targets whose specific getter is unavailable.
     */
    private String firstNamedCrossRef(EObject node) {
        for (EReference ref : node.eClass().getEAllReferences()) {
            if (ref.isContainment() || ref.isDerived()) continue;
            try {
                Object val = node.eGet(ref);
                if (val instanceof EObject eo) {
                    String name = getElementName(eo);
                    if (name != null) return name;
                } else if (val instanceof List<?> list) {
                    for (Object item : list) {
                        if (item instanceof EObject eo) {
                            String name = getElementName(eo);
                            if (name != null) return name;
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    // ── Structural tree helpers ──────────────────────────────────────────────

    /** Returns {@code true} for element types that hold only content/metadata. */
    private boolean isContentOnly(EObject node) {
        return CONTENT_TYPES.contains(node.eClass().getName());
    }

    /**
     * Returns the meaningful structural children of a node.
     *
     * For Namespace elements {@code getOwnedMember()} is used; it returns the
     * actual model elements directly, bypassing membership wrapper objects.
     * For non-Namespace elements {@code eContents()} is filtered instead.
     */
    private List<EObject> structuralChildren(EObject node) {
        List<EObject> children = new ArrayList<>();
        if (node instanceof Namespace ns) {
            for (EObject member : ns.getOwnedMember()) {
                if (!isContentOnly(member)) children.add(member);
            }
        } else {
            for (EObject child : node.eContents()) {
                if (!isContentOnly(child)) children.add(child);
            }
        }
        return children;
    }

    /** Returns {@code "name [TypeName]"} or {@code "[TypeName]"} when unnamed. */
    private String elementLabel(EObject node) {
        String typeName = node.eClass().getName();
        String name = getElementName(node);
        return name != null ? name + " [" + typeName + "]" : "[" + typeName + "]";
    }

    /**
     * Returns the user-defined name of an EObject, or {@code null} if none is
     * present.  Tries {@code getName()} then {@code getDeclaredName()}.
     */
    private String getElementName(EObject node) {
        if (node == null) return null;
        for (String method : List.of("getName", "getDeclaredName")) {
            try {
                Object val = node.getClass().getMethod(method).invoke(node);
                if (val instanceof String s && !s.isBlank()) return s;
            } catch (Exception ignored) {}
        }
        return null;
    }
}
