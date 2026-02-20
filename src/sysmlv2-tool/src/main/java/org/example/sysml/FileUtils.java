package org.example.sysml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.example.sysml.Logger.*;

public class FileUtils {
    /**
     * Recursively collects all *.sysml files under {@code dir}, sorted by path
     * so that files in parent directories are processed before subdirectories
     * (a reasonable heuristic for dependency ordering).
     */
    public static List<Path> collectSysmlFiles(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk
                .filter(p -> Files.isRegularFile(p)
                          && p.getFileName().toString().endsWith(".sysml"))
                .sorted(Comparator.comparingInt(Path::getNameCount)
                                  .thenComparing(Comparator.naturalOrder()))
                .collect(Collectors.toList());
        } catch (IOException e) {
            Logger.error("Failed to scan directory '" + dir + "': " + e.getMessage());
            return List.of();
        }
    }
}
