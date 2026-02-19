# sysmlv2-tool

A standalone Java command-line tool to **validate**, and **render views** or **render diagrams**
from SysML v2 files to puml — using the OMG Pilot Implementation directly.
No API server, no Jupyter, no network required.

Since I am by no means a java developer, this codebase is heavily vibecoded, sometimes with focus to 
just make it work, not look pretty.

# Current status

Loading the Sysmlv2 Library and validating models (single files and recursive directory content)
Basic diagram export is also working.

# Usage

## getting help

java -jar sysmlv2-tool-assembly\target\sysmlv2-tool-fat.jar --help

To get a list of available command.

java -jar sysmlv2-tool-assembly\target\sysmlv2-tool-fat.jar help <command>

Get help on a specific command.

## Specifying the sysml.library path

The sysml base library files are required. They can be found in the Sysml-v2-Release submodule.
the library is searched for in the following locations:

- Path specified in the SYSML_LIBRARY environment variable
- "./src/submodules/SysML-v2-Release/sysml.library",
- "./submodules/SysML-v2-Release/sysml.library",
- "../submodules/SysML-v2-Release/sysml.library",
- "../../submodules/SysML-v2-Release/sysml.library"
- $Userhome, "../submodules/SysML-v2-Release/sysml.library",
- $Userhome, "../../submodules/SysML-v2-Release/sysml.library"

If the library folder is not detected automatically, it can be supplied using the --libdir parameter on startup.

## Validation

java -Dsysml.debug=true -jar sysmlv2-tool-assembly\target\sysmlv2-tool-fat.jar validate <path or filename>

Validate a given file or directory containing files, if a directory is given, all files inside the directory including subfolders are loaded into context and validated. This allows for cross file import dependencies.

validate -f --format
allows the output to be either generated in text format (-f text), which is also the default output.
the output can also be generated in JUnit xml format for processing in CI pipelines (-f xml).  

0 is returned on successful validation, -1 otherwise.

## Diagram generation

### Usage modes:
 *   diagram <path>                         -- diagram the root element(s)
 *   diagram <path> --element MyPart        -- diagram a specific named element
 *   diagram <path> --all-elements          -- one diagram per top-level element
  
### Select output file format:
 *   diagram -f svg <path>                  -- Output in svg format
 *   diagram -f png <path>                  -- Output in png format
   
  by default, puml is generated (not extremely useful, because it contains sysmlv2 specific skins)
  png and svg is also supported and can be selected using "--format", "-f" after the diagram command.

### Render specific elements

Specific elements can be rendered by supplying the "-e" "--element" parameter and the elements name.
diagram --element  MyPartName               -- Render my part.

### Exclude stdandard lib

To exclude the standard sysmlv2 library imports from being rendered, add --nostdlib to the command.

# Todo

- Ability to list and render view definitions
- Write comprehensive build instructions to build this thing

# Building

- Requires maven
- Checkout the two pilot implementation repositories under src/submodules
- Currently tested against tag 2026-01 for both repos
- go to src/ folder
- run "mvn compile package" and hope for the best.
- If the build goes well, you will end up with a fat Jarfile under src/sysmlv2-tool-assembly/target/sysmlv2-tool-fat.jar
- Run with java -jar src/sysmlv2-tool-assembly/target/sysmlv2-tool-fat.jar test_model/test_ScalarValues.sysml
- The path to the sysml.library i picked up automatically, if you run it inside the repo structure.
  Otherwise you will get a warning and you can supply your custom file to the sysml library files using --libdir parameter.