# sysmlv2-tool

A standalone Java command-line tool to **validate**, **diagram**, and **render views**
from SysML v2 files — using the OMG Pilot Implementation directly.
No API server, no Jupyter, no network required.

---

## Architecture

```
your .sysml file
      │
      ▼
SysMLInteractive.eval()          ← Pilot Implementation Xtext/EMF parser
      │                             (full KerML + SysML v2 grammar)
      ├─► getProblems()            ← Validation errors/warnings (Xtext diagnostics)
      │
      └─► getRootElement()         ← EMF element tree (fully resolved)
                │
                ▼
        SysML2PlantUMLText          ← Pilot Implementation PlantUML generator
        .doSwitch(element)          (same as Eclipse PlantUML view + %viz)
                │
                ▼
        PlantUML library            ← net.sourceforge.plantuml
        → PNG / SVG / .puml
```

The parser resolves imports against the **standard library** (`sysml.library/`),
which must be either in your PATH or specified with `--lib`.

---

## Prerequisites

| Requirement | Notes |
|---|---|
| Java 17+ | `java --version` |
| Maven 3.8+ | `mvn --version` |
| Pilot Implementation JARs | Built from source or from GitHub Packages |
| `sysml.library/` directory | Shipped with the Pilot Implementation release |
| GraphViz (optional) | For complex diagrams; PlantUML uses it automatically if found |

---

## Building

### Option A – JARs from GitHub Packages (recommended)

The Pilot Implementation publishes to GitHub Packages. Authenticate once:

```bash
# ~/.m2/settings.xml
<settings>
  <servers>
    <server>
      <id>github-sysml</id>
      <username>YOUR_GITHUB_USER</username>
      <password>YOUR_GITHUB_TOKEN</password>   <!-- needs read:packages scope -->
    </server>
  </servers>
</settings>
```

Then build:

```bash
cd sysmlv2-tool
mvn clean package
```

### Option B – Local JARs from your source build

If you've already built the Pilot Implementation from source, switch the
dependencies in `pom.xml` to `<scope>system</scope>` and point `<systemPath>`
at your local JARs:

```xml
<dependency>
  <groupId>org.omg.sysml</groupId>
  <artifactId>org.omg.sysml.interactive</artifactId>
  <version>0.48.0</version>
  <scope>system</scope>
  <systemPath>/path/to/SysML-v2-Pilot-Implementation/
      org.omg.sysml.interactive/target/org.omg.sysml.interactive-0.48.0.jar</systemPath>
</dependency>
<dependency>
  <groupId>org.omg.sysml</groupId>
  <artifactId>org.omg.sysml.plantuml</artifactId>
  <version>0.48.0</version>
  <scope>system</scope>
  <systemPath>/path/to/SysML-v2-Pilot-Implementation/
      org.omg.sysml.plantuml/target/org.omg.sysml.plantuml-0.48.0.jar</systemPath>
</dependency>
```

---

## Standard Library

The tool auto-detects the library in the following order:

1. `$SYSML_LIBRARY` environment variable
2. `./sysml.library/` in the current directory
3. `~/git/SysML-v2-Pilot-Implementation/sysml.library/`
4. `~/git/SysML-v2-Release/sysml.library/`

Or specify it explicitly with every command:

```bash
java -jar target/sysmlv2-tool-1.0.0.jar validate model.sysml \
  --lib ~/SysML-v2-Pilot-Implementation/sysml.library
```

---

## Usage

### 1. Validate

```bash
java -jar target/sysmlv2-tool-1.0.0.jar validate model.sysml
java -jar target/sysmlv2-tool-1.0.0.jar validate model.sysml --warnings
java -jar target/sysmlv2-tool-1.0.0.jar validate model.sysml --verbose
java -jar target/sysmlv2-tool-1.0.0.jar validate a.sysml b.sysml c.sysml
```

Exit code `0` = no errors, `1` = errors found. Suitable for CI/CD pipelines.

**Example output:**
```
────────────────────────────────────────────────────────────
  Validating: model.sysml
────────────────────────────────────────────────────────────
  ✅  No issues found.
```

```
  ❌  [ERROR] line 12 col 5 – Couldn't resolve reference to Type 'Foo'
  ❌  FAILED: 1 error(s), 0 warning(s)
```

### 2. Generate diagrams

```bash
# One diagram for the whole file (default format: PNG)
java -jar target/sysmlv2-tool-1.0.0.jar diagram model.sysml

# Specific element only
java -jar target/sysmlv2-tool-1.0.0.jar diagram model.sysml \
  --element BatteryControllerDefinition

# One diagram per top-level element
java -jar target/sysmlv2-tool-1.0.0.jar diagram model.sysml --all-elements

# Output as SVG into a directory
java -jar target/sysmlv2-tool-1.0.0.jar diagram model.sysml \
  --format svg --output ./diagrams

# Output as raw PlantUML text (for further processing)
java -jar target/sysmlv2-tool-1.0.0.jar diagram model.sysml --format puml
```

The PlantUML generator is `org.omg.sysml.plantuml.SysML2PlantUMLText` from the
Pilot Implementation — the same one used in the Eclipse plugin and Jupyter `%viz`.

### 3. List and render views

SysML v2 has first-class support for **views** and **viewpoints**:

```sysml
view def ComponentView {
    expose SystemModel::Components::*;
}

view myComponentView : ComponentView {
    part ref myBatteryController;
}
```

```bash
# List all ViewDefinitions and ViewUsages in the file
java -jar target/sysmlv2-tool-1.0.0.jar views model.sysml

# Render all views to PNG
java -jar target/sysmlv2-tool-1.0.0.jar views model.sysml --render

# Render a specific view to SVG
java -jar target/sysmlv2-tool-1.0.0.jar views model.sysml \
  --view myComponentView --render --format svg --output ./views

# Output raw PlantUML for all views
java -jar target/sysmlv2-tool-1.0.0.jar views model.sysml \
  --render --format puml
```

**Example output (listing):**
```
────────────────────────────────────────────────────────────
  Views in: system_model.sysml
────────────────────────────────────────────────────────────
  Found 1 ViewDefinition(s), 2 ViewUsage(s)

  ViewDefinitions (viewpoints / view templates):
    • [ViewDefinition] ComponentView

  ViewUsages (concrete views / rendered pages):
    • [ViewUsage] myComponentView : ComponentView
        part: myBatteryController : BatteryControllerDefinition
    • [ViewUsage] requirementView : RequirementView
        expose: FSC001
        expose: TSC001
```

---

## Integration with Git / Doorstop

Since you are using Git + Doorstop, a typical workflow:

```bash
# In CI / pre-commit hook:
java -jar sysmlv2-tool.jar validate design/system/system_model.sysml
# Exit 1 if errors → blocks commit

# Generate diagrams as part of documentation build:
java -jar sysmlv2-tool.jar diagram design/system/system_model.sysml \
  --all-elements --format svg --output docs/diagrams/

# Render views into the Doorstop document tree:
java -jar sysmlv2-tool.jar views design/system/system_model.sysml \
  --render --format png --output docs/views/
```

---

## Version notes

The `pom.xml` references version `0.48.0` of the Pilot Implementation (April 2025).
If you have built a different version, update the version numbers in `pom.xml`
and the `<systemPath>` values accordingly.

Check your build version:
```bash
# In your Pilot Implementation source directory:
grep -m1 "bundle-version" pom.xml
```
