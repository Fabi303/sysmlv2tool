package org.example.sysml;

import org.eclipse.emf.ecore.EObject;
import org.omg.sysml.interactive.SysMLInteractive;
import org.omg.sysml.interactive.SysMLInteractiveResult;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.ArrayList;

import java.util.List;
import java.io.PrintStream;
import java.io.OutputStream;

/**
 * Wraps SysMLInteractive using the correct API discovered via reflection:
 *
 *   process(String source)  -> SysMLInteractiveResult   (parse + link)
 *   validate()              -> List<?>                  (validation issues)
 *   getRootElement()        -> Element                  (parsed model root)
 *
 * eval() is intentionally NOT used for validation — it always emits a
 * spurious "Couldn't resolve reference to Element 'In[N]'" error regardless
 * of whether the model is valid, making it useless as an error signal.
 */
public class SysMLEngineHelper {

    private final SysMLInteractive sysml;

    public SysMLEngineHelper(Path libraryPath) {
        // Initialize Xtext standalone setup if available
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
            // Ensure trailing separator for directory
            if (!libPath.endsWith("/") && !libPath.endsWith("\\")) {
                libPath += System.getProperty("file.separator");
            }
            String loglevel = System.getProperty("sysmlv2.tool.loglevel", "info").toLowerCase();
            if (loglevel.equals("info")) {
                System.out.printf("[INFO]  Loading library: %s%n", libPath);
            }
            // Redirect System.out to verbose logger if not verbose, else let pass
            PrintStream originalOut = System.out;
            PrintStream verboseOut = originalOut;
            if (!loglevel.equals("verbose")) {
                // Use a custom OutputStream that discards all output (compatible with Java 8+)
                OutputStream nullStream = new OutputStream() {
                    @Override
                    public void write(int b) {}
                };
                verboseOut = new PrintStream(nullStream);
            }
            try {
                System.setOut(verboseOut);
                sysml.loadLibrary(libPath);
            } finally {
                System.setOut(originalOut);
            }
        } else {
            System.err.println("[WARN]  No standard library found. Use --libdir or set $SYSML_LIBRARY.");
        }
    }

    public SysMLInteractive getSysML() { return sysml; }

    /**
     * Parses and links a SysML file using process(String).
     * Returns the SysMLInteractiveResult for further inspection.
     */
    public SysMLInteractiveResult process(Path sysmlFile) throws IOException {
        String source = Files.readString(sysmlFile);
        // process(String) parses + links the source, populating the resource set.
        // Unlike eval(), it does not produce spurious cross-resource errors.
        return sysml.process(source);
    }

    /**
     * Runs validation on the currently loaded model.
     * Must be called after process().
     * Returns a list of issue objects (type varies by version).
     */
    @SuppressWarnings("unchecked")
    public List<?> validate() {
        try {
            return sysml.validate();
        } catch (Exception e) {
            System.err.println("[WARN]  validate() failed: " + e.getMessage());
            return List.of();
        }
    }

    /**
     * Returns the root element of the parsed model.
     */
    public EObject getRootElement() {
        try {
            // getRootElement() returns Element which extends EObject
            Object el = sysml.getRootElement();
            if (el instanceof EObject eo) return eo;
        } catch (Exception e) {
            System.err.println("[WARN]  getRootElement() failed: " + e.getMessage());
        }
        return null;
    }

    /**
     * Returns the input resources from the resource set.
     * Used by DiagramCommand to access parsed model elements.
     */
    @SuppressWarnings("unchecked")
    public List<?> getInputResources() {
        try {
            return sysml.getInputResources();
        } catch (Exception e) {
            return List.of();
        }
    }

    private static Path autoDetectLibrary() {
        List<Path> candidatePaths = new ArrayList<>();

        // 1. Check environment variable first
        String env = System.getenv("SYSML_LIBRARY");
        if (env != null && Files.isDirectory(Path.of(env))) {
            candidatePaths.add(Path.of(env));
        }

        // 2. Add relative paths from current working directory
        candidatePaths.addAll(Arrays.asList(
            Path.of("./src/submodules/SysML-v2-Release/sysml.library"),
            Path.of("./submodules/SysML-v2-Release/sysml.library"),
            Path.of("../submodules/SysML-v2-Release/sysml.library"),
            Path.of("../../submodules/SysML-v2-Release/sysml.library")
        ));

        // 3. Add paths relative to user home directory
        String home = System.getProperty("user.home");
        candidatePaths.addAll(Arrays.asList(
            Path.of(home, "../submodules/SysML-v2-Release/sysml.library"),
            Path.of(home, "../../submodules/SysML-v2-Release/sysml.library")
        ));

        // 4. Check all candidate paths in order
        for (Path path : candidatePaths) {
            if (Files.isDirectory(path)) {
                // 5. Verify contains required SysML files
                Path sysmlFile = path.resolve("Systems Library/SysML.sysml");
                if (Files.exists(sysmlFile)) {
                    
                    System.out.println("[INFO] Found library at: " + path);
                    return path;
                }
            }
        }

        System.out.println("[WARN] Sysml library not found, please specify the directory using --libdir");
        return null;
    }
}
