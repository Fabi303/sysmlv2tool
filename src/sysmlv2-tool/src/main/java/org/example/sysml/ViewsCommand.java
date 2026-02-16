package org.example.sysml;

import org.eclipse.emf.ecore.EObject;
import org.omg.sysml.interactive.SysMLInteractiveResult;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Lists and renders View/Viewpoint elements from a SysML v2 file.
 * Uses eClass().getName() for type matching — stable across all versions.
 */
@Command(
    name = "views",
    mixinStandardHelpOptions = true,
    description = "List and render View/Viewpoint elements from a SysML v2 file"
)

public class ViewsCommand implements Callable<Integer> {
    private final SysMLTool parent;

    @Parameters(paramLabel = "<file>", description = ".sysml file to process", arity = "1")
    private Path sysmlFile;

    @Option(names = {"--render", "-r"}, description = "Render each view to a diagram file")
    private boolean render;

    @Option(names = {"--view"}, description = "Render only this view (by name)", paramLabel = "<n>")
    private String viewName;

    @Option(names = {"--format", "-f"}, description = "Output format: png, svg, puml (default: png)",
        paramLabel = "<fmt>", defaultValue = "png")
    private String format;

    @Option(names = {"--output", "-o"}, description = "Output directory (default: .)",
        paramLabel = "<dir>", defaultValue = ".")
    private Path outputDir;

    public ViewsCommand(SysMLTool parent) {
    this.parent = parent;
    }

    @Override
    public Integer call() {
        String fmt = format.toLowerCase();
        if (!fmt.equals("png") && !fmt.equals("svg") && !fmt.equals("puml")) {
            System.err.println("[ERROR] --format must be: png, svg, or puml");
            return 2;
        }

        SysMLEngineHelper engine = new SysMLEngineHelper(parent.getLibraryPath());

        System.out.printf("%n%s%n  Views in: %s%n%s%n",
            "─".repeat(60), sysmlFile, "─".repeat(60));

        try {
            engine.process(sysmlFile);
            long errorCount = engine.validate().stream()
                .filter(i -> { try { Object s = i.getClass().getMethod("getSeverity").invoke(i);
                    return s != null && s.toString().toUpperCase().contains("ERROR"); }
                    catch (Exception ex) { return true; } })
                .count();
            if (errorCount > 0) {
                System.err.printf("[ERROR] %d validation error(s) — fix before rendering views.%n", errorCount);
                return 1;
            }
        } catch (Exception e) {
            System.err.printf("[ERROR] Parse failed: %s%n", e.getMessage());
            return 2;
        }

        EObject root = engine.getRootElement();
        if (root == null) {
            System.err.println("[ERROR] No root element.");
            return 1;
        }

        List<EObject> viewDefs   = new ArrayList<>();
        List<EObject> viewUsages = new ArrayList<>();
        collectViews(root, viewDefs, viewUsages);

        System.out.printf("  Found %d ViewDefinition(s), %d ViewUsage(s)%n%n",
            viewDefs.size(), viewUsages.size());

        if (viewDefs.isEmpty() && viewUsages.isEmpty()) {
            System.out.println("  No views defined in this file.");
            System.out.println("  Define views like:");
            System.out.println("    view def MyView { ... }");
            System.out.println("    view myView : MyView { expose ... }");
            return 0;
        }

        if (!viewDefs.isEmpty()) {
            System.out.println("  ViewDefinitions:");
            for (EObject vd : viewDefs) {
                System.out.printf("    • %s%n", DiagramCommand.getEObjectName(vd));
                listChildren(vd, "        ");
            }
            System.out.println();
        }

        if (!viewUsages.isEmpty()) {
            System.out.println("  ViewUsages:");
            for (EObject vu : viewUsages) {
                System.out.printf("    • %s%s%n",
                    DiagramCommand.getEObjectName(vu), getTypeAnnotation(vu));
                listChildren(vu, "        ");
            }
            System.out.println();
        }

        if (!render && viewName == null) {
            System.out.println("  Use --render to generate diagrams, or --view <n> --render for one view.");
            return 0;
        }

        try { Files.createDirectories(outputDir); }
        catch (Exception e) {
            System.err.printf("[ERROR] Cannot create %s%n", outputDir);
            return 2;
        }

        int rendered = 0, errors = 0;
        List<EObject> targets = viewUsages.isEmpty() ? viewDefs : viewUsages;
        for (EObject target : targets) {
            String name = DiagramCommand.getEObjectName(target);
            if (viewName != null && !viewName.equals(name)) continue;
            System.out.printf("  Rendering: %s ...%n", name);
            try {
                renderElement(target, name, fmt);
                rendered++;
            } catch (Exception e) {
                System.err.printf("  [ERROR] %s: %s%n", name, e.getMessage());
                errors++;
            }
        }

        if (rendered > 0)
            System.out.printf("%n  Rendered %d view(s) → %s%n", rendered, outputDir.toAbsolutePath());
        else if (viewName != null)
            System.err.printf("%n[WARN]  View '%s' not found.%n", viewName);

        return errors > 0 ? 1 : 0;
    }

    private void collectViews(EObject obj, List<EObject> defs, List<EObject> usages) {
        String typeName = obj.eClass().getName();
        if (typeName.equals("ViewDefinition"))     defs.add(obj);
        else if (typeName.equals("ViewUsage"))     usages.add(obj);
        for (EObject child : obj.eContents()) collectViews(child, defs, usages);
    }

    private void listChildren(EObject el, String indent) {
        for (EObject child : el.eContents()) {
            String typeName = child.eClass().getName();
            String name     = DiagramCommand.getEObjectName(child);
            switch (typeName) {
                case "ExposureUsage", "RenderingUsage" ->
                    System.out.printf("%sexpose: %s%n", indent, name);
                case "PartUsage" ->
                    System.out.printf("%spart:   %s%s%n", indent, name, getTypeAnnotation(child));
                case "RequirementUsage" ->
                    System.out.printf("%sreq:    %s%n", indent, name);
                default -> {}
            }
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

    private void renderElement(EObject element, String name, String fmt) throws Exception {
        String puml = DiagramCommand.generatePlantUML(element);
        if (puml == null || puml.isBlank()) {
            System.out.printf("[WARN]  Empty visualization for '%s'.%n", name);
            return;
        }
        if (!puml.contains("@startuml")) puml = "@startuml\n" + puml + "\n@enduml\n";

        String safe = name.replaceAll("[^A-Za-z0-9_\\-]", "_");
        if (fmt.equals("puml")) {
            Path out = outputDir.resolve(safe + ".puml");
            Files.writeString(out, puml, StandardCharsets.UTF_8);
            System.out.printf("[OK]    %s%n", out);
        } else {
            net.sourceforge.plantuml.FileFormat ff =
                fmt.equals("svg") ? net.sourceforge.plantuml.FileFormat.SVG
                                  : net.sourceforge.plantuml.FileFormat.PNG;
            Path out = outputDir.resolve(safe + "." + fmt);
            try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                new net.sourceforge.plantuml.SourceStringReader(puml)
                    .outputImage(baos, new net.sourceforge.plantuml.FileFormatOption(ff));
                Files.write(out, baos.toByteArray());
            }
            System.out.printf("[OK]    %s%n", out);
        }
    }
}
