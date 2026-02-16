
package org.example.sysml;

import java.nio.file.Path;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * SysMLv2 Tool – validate, diagram, and view rendering
 * using the OMG SysML v2 Pilot Implementation directly (no server needed).
 *
 * Usage:
 *   sysmlv2-tool validate  <file.sysml> [--lib <library-path>]
 *   sysmlv2-tool diagram   <file.sysml> [--element <name>] [--output <dir>] [--format png|svg|puml]
 *   sysmlv2-tool views     <file.sysml> [--render] [--output <dir>]
 *
 * Build:
 *   mvn clean package
 *   java -jar target/sysmlv2-tool-1.0.0.jar <command> [options]
 */

import picocli.CommandLine.Option;

public class SysMLTool implements Runnable {

    @Option(names = {"--libdir"}, description = "Path to the SysML v2 standard library directory", paramLabel = "<dir>")
    private Path libraryPath;


    public static void main(String[] args) {
        SysMLTool tool = new SysMLTool();
        CommandLine cmd = new CommandLine(tool);
        // Inject global --libdir into subcommands
        cmd.addSubcommand("validate", new ValidateCommand(tool));
        cmd.addSubcommand("diagram", new DiagramCommand(tool));
        cmd.addSubcommand("views", new ViewsCommand(tool));
        cmd.addSubcommand("help", new CommandLine.HelpCommand());
        int exitCode = cmd.execute(args);
        System.exit(exitCode);
    }

    @Override
    public void run() {
        // No subcommand given – print usage
        CommandLine.usage(this, System.out);
    }

    public Path getLibraryPath() {
        return libraryPath;
    }
}

