package org.example.sysml;

import net.sourceforge.plantuml.FileFormat;
import net.sourceforge.plantuml.FileFormatOption;
import net.sourceforge.plantuml.SourceStringReader;
import org.eclipse.emf.ecore.EObject;
import org.omg.sysml.interactive.SysMLInteractiveResult;
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
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Generates diagrams using SysML2PlantUMLText from the Pilot Implementation.
 *
 * SysML2PlantUMLText API in 0.55.x:
 *   - Constructor requires SysML2PlantUMLLinkProvider (we pass null via reflection)
 *   - The visualization method is discovered at runtime — it is NOT doSwitch(EObject)
 *     in this version. We search for any method that takes an EObject and returns String.
 */
@Command(
    name = "diagram",
    mixinStandardHelpOptions = true,
    description = "Generate PlantUML diagrams from a SysML v2 file"
)

public class DiagramCommand implements Callable<Integer> {
    private final SysMLTool parent;

    @Parameters(paramLabel = "<file>", description = ".sysml file to process", arity = "1")
    private Path sysmlFile;

    @Option(names = {"--element", "-e"}, description = "Name of element to diagram (default: root)", paramLabel = "<n>")
    private String elementName;

    @Option(names = {"--all-elements"}, description = "One diagram per top-level owned member")
    private boolean allElements;

    @Option(names = {"--format", "-f"}, description = "Output format: png, svg, puml (default: png)",
        paramLabel = "<fmt>", defaultValue = "png")
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
        if (!fmt.equals("puml")) {
            System.err.println("[ERROR] --format must be: puml");
            return 2;
        }

        SysMLEngineHelper engine = new SysMLEngineHelper(parent.getLibraryPath());

        System.out.printf("%n%s%n  Generating PlantUML: %s%n%s%n",
            "─".repeat(60), sysmlFile, "─".repeat(60));

        // try {
        //     engine.process(sysmlFile);
        //     long errorCount = engine.validate().stream()
        //         .filter(i -> {
        //             try {
        //                 Object s = i.getClass().getMethod("getSeverity").invoke(i);
        //                 return s != null && s.toString().toUpperCase().contains("ERROR");
        //             } catch (Exception ex) {
        //                 return true;
        //             }
        //         })
        //         .count();
        //     if (errorCount > 0) {
        //         System.err.printf("[ERROR] %d validation error(s) — fix before generating diagrams.%n", errorCount);
        //         return 1;
        //     }
        // } catch (Exception e) {
        //     System.err.printf("[ERROR] Parse failed: %s%n", e.getMessage());
        //     return 2;
        // }

        // EObject root = engine.getRootElement();
        // if (root == null) {
        //     System.err.println("[ERROR] No root element — is the file empty?");
        //     return 1;
        // }

        // try {
        //     Files.createDirectories(outputDir);
        // } catch (IOException e) {
        //     System.err.printf("[ERROR] Cannot create %s: %s%n", outputDir, e);
        //     return 2;
        // }

        // try {
        //     String puml = generatePlantUML(root);
        //     if (puml == null || puml.isBlank()) {
        //         System.out.println("[WARN] Empty output — skipping.");
        //         return 1;
        //     }
        //     if (!puml.contains("@startuml")) puml = "@startuml\n" + puml + "\n@enduml\n";

        //     Path out = outputDir.resolve(sysmlFile.getFileName().toString().replace(".sysml", ".puml"));
        //     Files.writeString(out, puml, StandardCharsets.UTF_8);
        //     System.out.printf("[OK] Generated: %s%n", out);
        // } catch (Exception e) {
        //     System.err.printf("[ERROR] Failed to generate PlantUML: %s%n", e.getMessage());
        //     return 1;
        // }

        return 0;
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
            System.out.printf("[OK]    %s%n", out);
        } else {
            FileFormat ff = fmt.equals("svg") ? FileFormat.SVG : FileFormat.PNG;
            Path out = outputDir.resolve(safe + "." + fmt);
            try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                new SourceStringReader(puml).outputImage(baos, new FileFormatOption(ff));
                Files.write(out, baos.toByteArray());
            }
            System.out.printf("[OK]    %s%n", out);
        }
    }

    /**
     * Instantiates SysML2PlantUMLText and finds the right visualization method
     * via reflection, since the method name changed across versions.
     *
     * Known method signatures to try (in order):
     *   String doSwitch(EObject)          -- older versions (EMF Switch pattern)
     *   String getPlantUML(EObject)       -- possible name
     *   String visualize(EObject)         -- possible name
     *   String getText(EObject)           -- possible name
     *   String caseNamespace(Namespace)   -- direct EMF switch case (unlikely externally useful)
     * If none match, we dump available methods to stderr.
     */
    static String generatePlantUML(EObject element) throws Exception {
        SysML2PlantUMLText viz = createViz();

        // Log available methods once in debug mode
        if (Boolean.getBoolean("sysml.debug")) {
            System.out.println("[DEBUG] SysML2PlantUMLText methods:");
            Arrays.stream(viz.getClass().getMethods())
                .filter(m -> m.getParameterCount() == 1)
                .filter(m -> String.class.equals(m.getReturnType()))
                .forEach(m -> System.out.println("  " + m));
        }

        // Try all known single-EObject-arg String-returning methods
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

        // Broader search: any method taking exactly one arg assignable from element's type
        for (Method m : viz.getClass().getMethods()) {
            if (m.getParameterCount() != 1) continue;
            if (!String.class.equals(m.getReturnType())) continue;
            Class<?> paramType = m.getParameterTypes()[0];
            if (paramType.isAssignableFrom(element.getClass())) {
                System.out.printf("[INFO]  Using visualization method: %s(%s)%n",
                    m.getName(), paramType.getSimpleName());
                Object result = m.invoke(viz, element);
                return result != null ? result.toString() : null;
            }
        }

        // Last resort: print all available methods so user can report back
        System.err.println("[ERROR] No suitable visualization method found on SysML2PlantUMLText.");
        System.err.println("        Available single-arg methods:");
        Arrays.stream(viz.getClass().getMethods())
            .filter(m -> m.getParameterCount() == 1)
            .forEach(m -> System.err.printf("          %s %s(%s)%n",
                m.getReturnType().getSimpleName(),
                m.getName(),
                m.getParameterTypes()[0].getSimpleName()));
        throw new UnsupportedOperationException(
            "Cannot find visualization method on SysML2PlantUMLText. " +
            "Run with -Dsysml.debug=true for full method listing.");
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
