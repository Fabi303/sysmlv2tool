
package org.example.sysml;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.Properties;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.IVersionProvider;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(
    name = "sysmlv2-tool",
    versionProvider = SysMLTool.VersionProvider.class,
    description = "Validate, diagram, and render views from SysML v2 files."
)
public class SysMLTool implements Runnable {

    static class VersionProvider implements IVersionProvider {
        @Override
        public String[] getVersion() throws Exception {
            Properties props = new Properties();
            try (InputStream is = SysMLTool.class.getResourceAsStream("/META-INF/sysmlv2-version.properties")) {
                if (is != null) {
                    props.load(is);
                    return new String[]{ props.getProperty("version", "unknown") };
                }
            }
            return new String[]{ "unknown" };
        }
    }

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
        cmd.addSubcommand("structure", new StructureCommand(tool));
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

