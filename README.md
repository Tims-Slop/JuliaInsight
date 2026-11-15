# Julia Insight — Julia for PyCharm (and other JetBrains IDEs)

Julia language support with **natural math input**, built on JetBrains' native LSP client.

*Unofficial: not affiliated with or endorsed by the Julia project or JetBrains.*

Tested against PyCharm 2026.2.2 (build 262) on Windows; works the same on macOS and Linux.

## Install

1. Build or take `build/distributions/julia-insight-0.1.0.zip` (the zip is platform independent — the same file works on Mac).
2. PyCharm → **Settings → Plugins → ⚙ → Install Plugin from Disk…** → pick the zip → restart.
3. **Disable or uninstall "Flexible Julia"** first: both plugins register `.jl` files.
4. Open a `.jl` file. If Julia is not found automatically, set it in
   **Settings → Languages & Frameworks → Julia** (juliaup, `/Applications/Julia-*.app`, Homebrew,
   `%LOCALAPPDATA%\Programs\Julia-*` and `PATH` are searched).
5. The first time, a balloon offers to install **LanguageServer.jl** (one-time, ~5–10 min of precompilation,
   into `~/.julia/environments/julia-insight-ls-v1.x`). Afterwards the server needs ~30 s to start.

## Math input

By default nothing changes while you type: **Space** or **Return** converts the word before the cursor
(LaTeX names and subscripts together) and then inserts the space / line break.

| You type | You get |
|---|---|
| `\theta` + Space | `θ ` |
| `\omega_0` + Space | `ω₀ ` |
| `\omega\omega` + Space | `ωω ` |
| `f(v_0, \alpha*\beta)` + Return | `f(v₀, α*β)` + new line |
| `x\hat`, `x\_1`, `r\^2`, `2\pi` + Space | `x̂`, `x₁`, `r²`, `2π` |
| `x_12` + Space | `x₁₂` (digits after names of ≤ 2 characters, configurable) |
| `x_2d`, `step_1`, `1_000` | unchanged (identifier continues / long name / number) |
| `A\u`, `B\L` | unchanged — in code that is left division, not `ŭ`/`Ł` |
| `\alp` + **Tab** | `α` immediately, REPL style; Ctrl+Space after `\` lists all ~3800 symbols with glyphs |

Comments are included, strings are not (configurable), raw strings like `L"\alpha"` never.
The status bar shows how to type the symbol at the caret (e.g. `θ  \theta`).
*Convert LaTeX to Unicode* (editor context menu) converts a selection or file.

Settings → Languages & Frameworks → Julia → Math Input can switch to **"While typing"**: names are replaced as
soon as they are complete and unambiguous (`\theta` → `θ` immediately), subscripts as you type the digit, and
Backspace right after a replacement restores what you typed.

## Features

- **Highlighting**: hand-written lexer following Julia's own identifier/operator rules (Unicode identifiers and
  operators, `$` interpolation incl. nested strings, string macros `r"…"i`/`raw"…"`/`L"…"`, command literals,
  nested `#= =#`, transpose vs. char literal, `:symbol` vs. range, `var"…"`, contextual keywords).
  Validated against all 525 files of Julia's `base` + `stdlib` sources. Semantic colours for declarations, calls,
  types, parameters, modules and docstrings; all colours configurable (Settings → Editor → Color Scheme → Julia).
- **Language server** (LanguageServer.jl by default, or JETLS.jl / a custom command): completion, hover docs,
  go to definition, find usages, rename, diagnostics/lint, formatting, signature help, inlay hints, code actions,
  semantic tokens (JETLS).
- **Editing**: folding (blocks, docstrings, brackets, comment runs, `#region`), structure view, breadcrumbs,
  auto `end` on Enter, indentation and auto-dedent of `end`/`else`/`catch`, quote and bracket pairing,
  comment toggling, TODOs, live templates (`fn`, `for`, `struct`, `testset`, `main`, …), New → Julia File/Module.
- **REPL** (tool window *Julia*): a real terminal (PTY), colours and REPL modes work.
  **Ctrl+Enter** (⌘↩ on Mac, also Alt+Shift+E) sends the selection or the block under the caret and moves on;
  **Ctrl+Shift+Enter** runs the file (`include`). Stack traces are clickable.
- **Plots** (tool window *Julia Plots*): `display`ed plots/images (PNG or SVG) from the REPL and from run
  configurations, with history, save and copy. Works with Plots.jl, Makie (CairoMakie), etc.
- **Workspace** (tool window *Julia Workspace*): the REPL's variables with type and value.
- **Run configurations**: right-click a `.jl` file → Run; script/Julia args, environment, working directory,
  clickable stack traces.
- Tools → Julia: start/restart/interrupt REPL, run package tests, install/update/restart the language server.

Not (yet) included compared with Flexible Julia: debugger, profiler, Pluto notebooks, table viewer.

## Build

Requires a JDK 25 (PyCharm's bundled `jbr` works).

```sh
# Windows (PowerShell): $env:JAVA_HOME = "$env:LOCALAPPDATA\Programs\PyCharm 2026.2.2\jbr"
# macOS:                export JAVA_HOME="/Applications/PyCharm.app/Contents/jbr/Contents/Home"
./gradlew buildPlugin                       # downloads PyCharm 2026.2.2 to compile against
./gradlew buildPlugin -PplatformLocalPath="<path to an installed PyCharm>"   # or use an installed IDE
./gradlew runIde -PplatformLocalPath=…      # try it in a sandbox IDE
```

Tests: `./gradlew test`. Set `JULIA_INSIGHT_TEST_JULIA=<julia executable>` to also run the corpus, REPL/PTY and
plot tests, and `JULIA_INSIGHT_TEST_DEPOT=<depot with LanguageServer.jl>` for the end-to-end language-server test.
`scripts/gen_latex_table.jl` regenerates the LaTeX table from the installed Julia.

## License

MIT, see [LICENSE](LICENSE). The LaTeX/emoji symbol table and the identifier rules are derived from Julia
(MIT); see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
