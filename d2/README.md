# D2 Diagram Sources

This directory contains [D2](https://d2lang.com) diagram source files — the single source of truth for all diagrams in the [LIRP GitHub Wiki](https://github.com/Transgressoft/lirp/wiki). Rendered SVGs are placed in the wiki repository at `../lirp.wiki/images/` and referenced in wiki Markdown pages.

## Files

| Source | Description |
|--------|-------------|
| `entity-hierarchy.d2` | Entity class hierarchy (LirpEntity → ReactiveEntityBase) |
| `event-flow.d2` | Sequence: property mutation through publisher to subscribers |
| `module-dependency.d2` | lirp-api vs lirp-core module boundary |
| `repository-lifecycle.d2` | Repository lifecycle from constructor to close() |
| `aggregate-resolution.d2` | Sequence: aggregate reference resolution via LirpContext |
| `persistent-repository-architecture.d2` | PersistentRepositoryBase write pipeline architecture |
| `operation-collapse.d2` | Operation collapse algorithm example |
| `clear-handling.d2` | Clear operation collapse behavior |
| `debounce-timing.d2` | Dual coroutine debounce strategy overview |
| `debounce-scenario-burst.d2` | Debounce scenario: burst followed by inactivity |
| `debounce-scenario-continuous.d2` | Debounce scenario: continuous mutations (maxDelay fires) |
| `flush-error-recovery.d2` | writePending() failure recovery pipeline |
| `sql-architecture.d2` | SQL persistence class hierarchy and table descriptors |

## Local Rendering

### Install D2

```bash
# Arch Linux
yay -S d2-bin

# macOS
brew install d2

# Or see https://d2lang.com/tour/install
```

### Render a single file

```bash
d2 d2/entity-hierarchy.d2 entity-hierarchy.svg
```

### Render all files

```bash
for f in d2/*.d2; do
    d2 "$f" "../lirp.wiki/images/$(basename "${f%.d2}.svg")"
done
```

### Themes and layout engines

```bash
# Dark theme
d2 --theme 200 d2/entity-hierarchy.d2 entity-hierarchy.svg

# Alternative layout engine
d2 --layout=elk d2/entity-hierarchy.d2 entity-hierarchy.svg
```

Available layout engines: `dagre` (default), `elk`, `tala`.

### Watch mode (live reload)

```bash
d2 --watch d2/entity-hierarchy.d2 entity-hierarchy.svg
```

Opens a browser with live reload on file changes.

## Adding a New Diagram

1. Create a new `.d2` file in this directory
2. Render it: `d2 d2/your-diagram.d2 ../lirp.wiki/images/your-diagram.svg`
3. Reference it in the wiki page: `![Title](./images/your-diagram.svg)`
4. Push both repos

## D2 Quick Reference

```d2
# Shapes
mybox: "Label"                              # rectangle (default)
mybox: "Label" { shape: circle }            # circle
mybox: "Label" { shape: class }             # UML class
mybox: "Label" { shape: queue }             # queue
mybox: "Label" { shape: sequence_diagram }  # sequence diagram

# Connections
a -> b                     # directed
a -> b: "label"            # labeled
a -- b                     # undirected
a -> b -> c                # chained

# Containers
parent: {
    child1
    child2
}

# Styling
mybox.style.fill: "#e8f5e9"
mybox.style.stroke: "#333"
mybox.style.font-size: 16
```

Full reference: https://d2lang.com/tour/intro
