# sysmlv2-tool

A standalone Java command-line tool to **validate**, and **render views** or **render diagrams**
from SysML v2 files to puml — using the OMG Pilot Implementation directly.
No API server, no Jupyter, no network required.

# Current status

Loading the Sysmlv2 Library and validating single model files is working

# Todo

- Add the ability to load a bunch of sysml model files at once, to be able to validate cross
references
- Ability to render diagrams to puml
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