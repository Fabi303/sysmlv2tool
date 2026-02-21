# sysmlv2-tool

A standalone Java command-line tool to **validate**, and **render views** or **render diagrams**
from SysML v2 files to puml, svg or png — using the OMG Pilot Implementation directly.
No API server, no Jupyter, no network required.

Since I am by no means a java developer, this codebase is heavily vibecoded, sometimes with focus to 
just make it work, not look pretty.

# Current status

Loading the Sysmlv2 Library and validating models (single files and recursive directory content)
Basic diagram export is also working.

# Usage

## Getting help

Running the tool without any arguments prints full usage and a list of available commands:
```bash
java -jar sysmlv2-tool-assembly\target\sysmlv2-tool-fat.jar
```

Get help on a specific command:
```bash
java -jar sysmlv2-tool-assembly\target\sysmlv2-tool-fat.jar help <command>
```

Print the tool version:
```bash
java -jar sysmlv2-tool-assembly\target\sysmlv2-tool-fat.jar -v
java -jar sysmlv2-tool-assembly\target\sysmlv2-tool-fat.jar --version
```

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

## Debugging

There are different loglevels available for information and debugging. Logging to stdout can be enabled with
-Dsysmlv2tool.loglevel=&lt;level&gt;\
```bash
java -Dsysmlv2tool.loglevel=debug -jar sysmlv2-tool-assembly\target\sysmlv2-tool-fat.jar
```
Available loglevels are
* debug
* info
* warn
* error

If no loglevel is specified on startup, the default level is warn, which emits warnings and errors.


## Validation
```bash
java -jar sysmlv2-tool-assembly\target\sysmlv2-tool-fat.jar validate <path or filename>
```
Validate a given file or directory containing files, if a directory is given, all files inside the directory including subfolders are loaded into context and validated. This allows for cross file import dependencies.

validate -f --format
allows the output to be either generated in text format (-f text), which is also the default output.
the output can also be generated in JUnit xml format for processing in CI pipelines (-f xml).  

0 is returned on successful validation, -1 otherwise.

## Diagram generation

### Usage modes:
 *   diagram &lt;path&gt;                         -- diagram the root element(s)
 *   diagram &lt;path&gt; --element MyPart        -- diagram a specific named element
 *   diagram &lt;path&gt; --all-elements          -- one diagram per top-level element
 *   diagram &lt;path&gt; -s --single             -- generate single diagram files in a folder structure
  
### Select output file format:
 *   diagram -f svg &lt;path&gt;                  -- Output in svg format
 *   diagram -f png &lt;path&gt;                  -- Output in png format
   
  by default, puml is generated (not extremely useful, because it contains sysmlv2 specific skins)
  png and svg is also supported and can be selected using "--format", "-f" after the diagram command.

### Render specific elements

Specific elements can be rendered by supplying the "-e" "--element" parameter and the elements name.
diagram --element  MyPartName               -- Render my part.

### Render all elements individually — `--single` / `-s`

The `--single` switch generates **one diagram file per named element** and mirrors the
SysML package hierarchy as subfolders under the output directory.
It is the most convenient mode when you want to browse individual diagrams in a file manager
or feed them into a documentation pipeline.

```bash
java -jar sysmlv2-tool-fat.jar diagram --single -o out/ test/dependency_test_model/
```

**Naming rules**

| Element | Result |
|---|---|
| `package Foo { … }` | Creates subdirectory `Foo/` |
| `part myPart { … }`, `requirement TSC001 { … }`, … | Creates `myPart.puml` (or `.svg`/`.png`) in the current directory |
| Unnamed element (e.g. `doc`, `comment`) | Named after its nearest named ancestor: `TSC001_Documentation.puml` |
| Two unnamed elements of the same type under the same parent | Numeric suffix: `TSC001_Documentation.puml`, `TSC001_Documentation_2.puml`, … |
| Synthetic file-root `Namespace` (parser artefact, no user-defined name) | Transparent — no directory is created for it |

**Compatible options**

| Option | Behaviour when combined with `--single` |
|---|---|
| `-f` / `--format` | Controls output format (`puml`, `svg`, `png`) for every generated file |
| `-o` / `--output` | Root of the generated folder tree (default: current directory) |
| `--single` + `--all-elements` | **Error** — mutually exclusive |

---

**Example**

Given the two files in `test/dependency_test_model/`:

```
test/dependency_test_model/
├── req/
│   └── requirements.sysml        # package ProjectRequirements { … }
└── system_model.sysml            # package SystemModel { … }
```

Running:

```bash
java -jar sysmlv2-tool-fat.jar diagram --single -f svg -o out/ test/dependency_test_model/
```

produces a folder tree like:

```
out/
├── ProjectRequirements/
│   ├── ProjectRequirements_Documentation.svg    ← unnamed doc on the package
│   ├── SafetyRequirement.svg
│   ├── FunctionalSafetyConcept/
│   │   ├── FunctionalSafetyConcept_Documentation.svg
│   │   └── FSC001.svg
│   ├── TechnicalSafetyConcept/
│   │   ├── TechnicalSafetyConcept_Documentation.svg
│   │   └── TSC001.svg
│   ├── SoftwareRequirements/
│   │   ├── SoftwareRequirements_Documentation.svg
│   │   └── SWS001.svg
│   └── HardwareRequirements/
│       ├── HardwareRequirements_Documentation.svg
│       └── HWS001.svg
└── SystemModel/
    ├── SystemModel_Documentation.svg            ← first doc block on the package
    ├── SystemModel_Documentation_2.svg          ← second doc block (numeric counter)
    ├── BatteryControllerDefinition.svg
    └── Components/
        ├── Components_Documentation.svg
        ├── myBatteryController.svg
        ├── TemperatureMonitor.svg
        └── ADCInput.svg
```

`SystemModel_Documentation` and `SystemModel_Documentation_2` illustrate the numeric
counter: `system_model.sysml` has two consecutive anonymous `doc` blocks at the package
level which would otherwise map to the same filename.

### Exclude standard lib

To exclude the standard sysmlv2 library imports from being rendered, add --nostdlib to the command.

## Views

### Usage modes:
 *   views &lt;path or file&gt;

Lists the defined views from the model and the items they contain:

  ViewUsages:\
    &nbsp;&nbsp;softwareSafetyView : View\
        &nbsp;&nbsp;&nbsp;&nbsp;expose: SWS001 : RequirementCheck\
        &nbsp;&nbsp;&nbsp;&nbsp;expose: TSC001 : RequirementCheck\
    &nbsp;&nbsp;hardwareSafetyView : View\
        &nbsp;&nbsp;&nbsp;&nbsp;expose: HWS001 : RequirementCheck\
        &nbsp;&nbsp;&nbsp;&nbsp;expose: TSC001 : RequirementCheck

Views can be rendered as diagrams by using the diagram command:
diagram &lt;path&gt; --view &lt;viewName&gt; -o &lt;output-dir&gt;

## Structure

The `structure` command prints the document outline of one or more SysML v2 files.
It shows the **element hierarchy** (names and metatypes) without any attribute content, documentation bodies, or literal values, and appends a flat **Relations** section listing all named dependency, satisfy, verify, derive, allocate, subset, and redefine links found in the model.

### Usage

```bash
java -jar sysmlv2-tool-fat.jar structure <path or file>
```

A directory is scanned recursively; all `.sysml` files are loaded as one batch so that cross-file references resolve correctly.

### Output formats

`-f text` (default) — ASCII tree followed by a Relations section:

```
ProjectRequirements [Package]
├── SafetyRequirement [RequirementDefinition]
├── FunctionalSafetyConcept [Package]
│   └── FSC001 [RequirementDefinition]
├── TechnicalSafetyConcept [Package]
│   └── TSC001 [RequirementDefinition]
├── SoftwareRequirements [Package]
│   └── SWS001 [RequirementDefinition]
└── HardwareRequirements [Package]
    └── HWS001 [RequirementDefinition]
SystemModel [Package]
├── BatteryControllerDefinition [PartDefinition]
└── Components [Package]
    ├── myBatteryController [PartUsage]
    ├── TemperatureMonitor [RequirementUsage]
    └── ADCInput [RequirementUsage]

Relations:
  TSC001                                   -[dependency  ]-> FSC001
  SWS001                                   -[dependency  ]-> TSC001
  HWS001                                   -[dependency  ]-> TSC001
  BatteryControllerDefinition              -[satisfy     ]-> FSC001
  BatteryControllerDefinition              -[satisfy     ]-> TSC001
```

`-f json` — a JSON object with two top-level keys:

```bash
java -jar sysmlv2-tool-fat.jar structure -f json <path or file>
```

```json
{
  "structure": [
    {
      "type": "Package",
      "name": "ProjectRequirements",
      "children": [
        { "type": "RequirementDefinition", "name": "SafetyRequirement" },
        ...
      ]
    }
  ],
  "relations": [
    { "kind": "dependency", "from": "TSC001",  "to": "FSC001" },
    { "kind": "satisfy",    "from": "BatteryControllerDefinition", "to": "FSC001" }
  ]
}
```

### Relations-only output (`--relations`)

The `--relations` flag suppresses the element tree and prints only the flat relations list.
This is useful for piping into other tools or for a quick traceability check without the
full document outline.

```bash
java -jar sysmlv2-tool-fat.jar structure --relations <path or file>
```

Text output (`-f text`, default):

```
Relations:
  TSC001                                   -[dependency  ]-> FSC001
  SWS001                                   -[dependency  ]-> TSC001
  HWS001                                   -[dependency  ]-> TSC001
  BatteryControllerDefinition              -[satisfy     ]-> FSC001
  BatteryControllerDefinition              -[satisfy     ]-> TSC001
```

JSON output (`-f json`) — only the `relations` key is emitted; `structure` is omitted:

```bash
java -jar sysmlv2-tool-fat.jar structure --relations -f json <path or file>
```

```json
{
  "relations": [
    { "kind": "dependency", "from": "TSC001",  "to": "FSC001" },
    { "kind": "satisfy",    "from": "BatteryControllerDefinition", "to": "FSC001" }
  ]
}
```

`--relations` combines freely with `-f`:

| Command | Output |
|---|---|
| `structure <path>` | tree + relations (text) |
| `structure <path> -f json` | tree + relations (JSON) |
| `structure <path> --relations` | relations only (text) |
| `structure <path> --relations -f json` | relations only (JSON) |

### Relation kinds

| Keyword in SysML v2 | Kind label in output |
|---|---|
| `dependency` / `dependency derivation` | `dependency` |
| `satisfy` | `satisfy` |
| `verify` | `verify` |
| `derive` / `DeriveReqtUsage` | `derive` |
| `allocate` | `allocate` |
| `:>` (feature subsetting) | `subset` |
| `:>>` (redefinition) | `redefine` |

`FeatureTyping` (the `:` type annotation) is intentionally excluded — it would produce one entry per typed feature and make the output very verbose.

# Todo

- Write comprehensive build instructions to build this thing
- Maybe add json output for some functions.

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