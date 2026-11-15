# Julia Insight: installs (or updates) LanguageServer.jl into a dedicated environment.
#   julia --startup-file=no ls_install.jl <envdir> [install|update]
# Progress goes to stderr; the last line on success is "JULIA-INSIGHT-OK <version>".
import Pkg
const envdir = ARGS[1]
const mode = length(ARGS) >= 2 ? ARGS[2] : "install"
mkpath(envdir)
Pkg.activate(envdir; io = stderr)
if mode == "update"
    Pkg.update(; io = stderr)
else
    Pkg.add(["LanguageServer", "SymbolServer"]; io = stderr)
end
Pkg.precompile(; io = stderr)
@eval import LanguageServer
const v = pkgversion(LanguageServer)
open(joinpath(envdir, ".julia-insight-ok"), "w") do io
    println(io, v)
end
println(stderr, "JULIA-INSIGHT-OK ", v)
