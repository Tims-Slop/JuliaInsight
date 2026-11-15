# Regenerates src/main/resources/unicode/latex_symbols.tsv from the running Julia's REPL tables
# (Julia is MIT licensed; keep THIRD_PARTY_NOTICES.md in sync).
# Usage: julia scripts/gen_latex_table.jl
import REPL
out = joinpath(@__DIR__, "..", "src", "main", "resources", "unicode", "latex_symbols.tsv")
entries = Dict{String,String}()
for (k, v) in REPL.REPLCompletions.latex_symbols
    entries[k] = v
end
for (k, v) in REPL.REPLCompletions.emoji_symbols
    entries[k] = v
end
open(out, "w") do io
    println(io, "# Generated from Julia ", VERSION, " REPL.REPLCompletions (latex_symbols + emoji_symbols), MIT License, see THIRD_PARTY_NOTICES.md. name<TAB>codepoints(hex)")
    for k in sort!(collect(keys(entries)))
        v = entries[k]
        # store code points as hex to keep the file ASCII-safe and immune to editor normalisation
        println(io, k, '\t', join((string(UInt32(c), base = 16) for c in v), ' '))
    end
end
println("wrote ", length(entries), " entries to ", normpath(out))
