# Julia Insight IDE bridge.
# Loaded with `-L` into REPLs and scripts started from the IDE. It connects back to the IDE over a local socket and
#   * shows plots / images in the IDE's "Julia Plots" tool window, and
#   * answers workspace queries for the "Julia Workspace" tool window.
# Everything is best effort: if anything fails, Julia keeps working as if the bridge was never loaded.
module JuliaInsightBridge

import Sockets
import Base64

const PORT = something(tryparse(Int, get(ENV, "JULIA_INSIGHT_PORT", "")), 0)
const TOKEN = get(ENV, "JULIA_INSIGHT_TOKEN", "")
const KIND = get(ENV, "JULIA_INSIGHT_KIND", "repl")
const PLOTS = get(ENV, "JULIA_INSIGHT_PLOTS", "1") == "1"
const conn = Ref{Any}(nothing)
const write_lock = ReentrantLock()

# ---- minimal JSON writer (no package dependencies) ---------------------------------------------------------------------
function writejson(io::IO, s::AbstractString)
    print(io, '"')
    for c in s
        if !isvalid(c)
            print(io, '�')
        elseif c == '"'
            print(io, "\\\"")
        elseif c == '\\'
            print(io, "\\\\")
        elseif c == '\n'
            print(io, "\\n")
        elseif c == '\r'
            print(io, "\\r")
        elseif c == '\t'
            print(io, "\\t")
        elseif c < ' '
            print(io, "\\u", lpad(string(UInt32(c), base = 16), 4, '0'))
        else
            print(io, c)
        end
    end
    print(io, '"')
end
writejson(io::IO, x::Bool) = print(io, x ? "true" : "false")
writejson(io::IO, x::Integer) = print(io, x)
writejson(io::IO, x::AbstractFloat) = isfinite(x) ? print(io, x) : print(io, "null")
writejson(io::IO, ::Nothing) = print(io, "null")
writejson(io::IO, x::Symbol) = writejson(io, string(x))
function writejson(io::IO, d::AbstractDict)
    print(io, '{')
    first = true
    for (k, v) in d
        first || print(io, ',')
        first = false
        writejson(io, string(k))
        print(io, ':')
        writejson(io, v)
    end
    print(io, '}')
end
function writejson(io::IO, v::Union{AbstractVector,Tuple})
    print(io, '[')
    for (i, x) in enumerate(v)
        i > 1 && print(io, ',')
        writejson(io, x)
    end
    print(io, ']')
end
writejson(io::IO, x) = writejson(io, string(x))

function send(msg)
    c = conn[]
    c === nothing && return false
    payload = sprint(writejson, msg)
    lock(write_lock) do
        try
            write(c, payload, "\n")
            flush(c)
            true
        catch
            conn[] = nothing
            false
        end
    end
end

# ---- plots -------------------------------------------------------------------------------------------------------------
struct IdeDisplay <: AbstractDisplay end
const PLOT_MIMES = ("image/png", "image/svg+xml")

function safe_showable(m, x)
    try
        showable(m, x)
    catch
        false
    end
end

function Base.display(d::IdeDisplay, x)
    if conn[] !== nothing
        for m in PLOT_MIMES
            safe_showable(m, x) && return display(d, MIME(m), x)
        end
    end
    throw(MethodError(display, (d, x)))
end

function Base.display(d::IdeDisplay, m::MIME, x)
    mime = string(m)
    (mime in PLOT_MIMES && conn[] !== nothing) || throw(MethodError(display, (d, m, x)))
    data = if mime == "image/png"
        Base64.base64encode(io -> show(io, m, x))
    else
        sprint(io -> show(io, m, x))
    end
    send(Dict("type" => "plot", "mime" => mime, "data" => data, "title" => string(nameof(typeof(x)))))
    return nothing
end

Base.displayable(::IdeDisplay, m::MIME) = string(m) in PLOT_MIMES

"Keeps the IDE display on top of the display stack (packages like Plots push their own displays when loaded)."
function fix_displays()
    PLOTS || return nothing
    ds = Base.Multimedia.displays
    filter!(d -> !(d isa IdeDisplay), ds)
    push!(ds, IdeDisplay())
    return nothing
end

# ---- workspace ---------------------------------------------------------------------------------------------------------
const HIDDEN = Set([:Base, :Core, :Main, :JuliaInsightBridge, :InteractiveUtils, :ans, :eval, :include, :err])

function short(f, x, n = 300)
    s = try
        f(x)
    catch
        "…"
    end
    s = replace(s, '\n' => " ")
    length(s) > n ? first(s, n) * "…" : s
end

function workspace()
    vars = Any[]
    for n in names(Main; all = true)
        (n in HIDDEN || startswith(string(n), '#')) && continue
        isdefined(Main, n) || continue
        v = try
            getfield(Main, n)
        catch
            continue
        end
        v isa Module && continue
        kind = v isa Function ? "function" : v isa Type ? "type" : "value"
        push!(vars, Dict(
            "name" => string(n),
            "kind" => kind,
            "type" => short(x -> x isa AbstractArray || x isa AbstractDict ? summary(x) : string(typeof(x)), v, 120),
            "value" => kind == "function" ? short(x -> string(length(methods(x))) * " method(s)", v) :
                       short(x -> sprint(show, x; context = (:limit => true, :compact => true, :displaysize => (10, 80))), v),
        ))
    end
    sort!(vars, by = d -> (d["kind"] == "value" ? 0 : d["kind"] == "type" ? 1 : 2, lowercase(d["name"])))
    return vars
end

# ---- connection --------------------------------------------------------------------------------------------------------
function reader(s)
    while isopen(s)
        line = try
            readline(s)
        catch
            break
        end
        cmd = strip(line)
        if cmd == "workspace"
            send(Dict("type" => "workspace", "vars" => Base.invokelatest(workspace)))
        elseif cmd == "ping"
            send(Dict("type" => "pong"))
        elseif isempty(cmd) && !isopen(s)
            break
        end
    end
    conn[] = nothing
end

function connect_ide()
    PORT == 0 && return
    try
        s = Sockets.connect(Sockets.localhost, PORT)
        conn[] = s
        send(Dict("type" => "hello", "token" => TOKEN, "kind" => KIND, "pid" => getpid(), "julia" => string(VERSION)))
        errormonitor(@async reader(s))
    catch e
        @debug "Julia Insight: IDE bridge not available" exception = e
    end
end

function __init_bridge__()
    connect_ide()
    if PLOTS
        fix_displays()
        push!(Base.package_callbacks, _ -> fix_displays())
        if isdefined(Base, :active_repl_backend) || isinteractive()
            try
                REPL = Base.require(Base.PkgId(Base.UUID("3fa0cd96-eef1-5676-8a61-b3b8758bbffb"), "REPL"))
                pushfirst!(REPL.repl_ast_transforms, ex -> Expr(:toplevel, :($(fix_displays)()), ex))
            catch e
                @debug "Julia Insight: could not hook the REPL" exception = e
            end
        end
    end
end

end # module

JuliaInsightBridge.__init_bridge__()
