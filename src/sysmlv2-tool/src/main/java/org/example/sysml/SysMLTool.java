
package org.example.sysml;

import java.nio.file.Path;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(
    name = "sysmlv2-tool",
    version = "1.0.0",
    description = "Validate, diagram, and render views from SysML v2 files."
)
public class SysMLTool implements Runnable {

    @Spec
    CommandSpec spec;

    @Option(names = {"--libdir"}, description = "Path to the SysML v2 standard library directory", paramLabel = "<dir>")
    private Path libraryPath;

    @Option(names = {"-v", "--version"}, versionHelp = true, description = "Print version information and exit")
    private boolean version;

    public static void main(String[] args) {
        SysMLTool tool = new SysMLTool();
        CommandLine cmd = new CommandLine(tool);
        cmd.addSubcommand("validate", new ValidateCommand(tool));
        cmd.addSubcommand("diagram", new DiagramCommand(tool));
        cmd.addSubcommand("views", new ViewsCommand(tool));
        cmd.addSubcommand("help", new CommandLine.HelpCommand());
        int exitCode = cmd.execute(args);
        System.exit(exitCode);
    }

    @Override
    public void run() {
        // No subcommand given – print full usage including all registered subcommands
        spec.commandLine().usage(System.out);
    }

    public Path getLibraryPath() {
        return libraryPath;
    }
}

