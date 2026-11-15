# Julia Insight: starts LanguageServer.jl on stdio.
#   julia --startup-file=no --history-file=no --project=<language server env> ls_run.jl [<project environment>]
# Everything printed by packages is redirected to stderr so it cannot corrupt the JSON-RPC stream on stdout.
const conn_in = stdin
const conn_out = stdout
redirect_stdout(stderr)

function julia_insight_env_path()
    env = get(ARGS, 1, "")
    if !isempty(env) && isdir(env)
        return abspath(env)
    end
    p = Base.current_project(pwd())
    p === nothing || return dirname(p)
    # No project: use the default environment (~/.julia/environments/v1.x). LanguageServer.jl only lints with an
    # active environment, so create the (empty) folder if needed - Julia does the same on the first Pkg.add.
    default = dirname(Base.load_path_expand("@v#.#"))
    try
        mkpath(default)
    catch
    end
    return isdir(default) ? default : ""
end

const env_path = julia_insight_env_path()
import LanguageServer
@info "Julia Insight: LanguageServer $(pkgversion(LanguageServer)) on Julia $(VERSION), environment $(env_path)"
const julia_exe = (path = joinpath(Sys.BINDIR, Base.julia_exename()), version = VERSION)
server = LanguageServer.LanguageServerInstance(conn_in, conn_out, env_path, nothing, nothing, julia_exe)
run(server)
