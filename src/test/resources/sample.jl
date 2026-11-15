module Kinematics

using LinearAlgebra: norm, ⋅
import Base: show
export Projectile, trajectory, @twice

"""
    Projectile{T}

A point mass under gravity with initial speed `v₀` and angle `θ`.
"""
struct Projectile{T<:Real}
    v₀::T      # initial speed
    θ::T       # launch angle
end

mutable struct Counter
    n::Int
end

abstract type AbstractBody end
primitive type Flag8 8 end

const g = 9.81 # m/s²
#= block comments #= nest =# =#

range_of(p::Projectile) = p.v₀^2 * sin(2p.θ) / g
Base.show(io::IO, p::Projectile) = print(io, "Projectile(v₀=$(p.v₀), θ=$(p.θ))")
apex(p::Projectile{T}) where {T} = (p.v₀ * sin(p.θ))^2 / (2g)

function trajectory(p::Projectile, n = 100; dt::Float64 = 0.01)
    t = range(0, 2p.v₀ * sin(p.θ) / g; length = n)
    x = @. p.v₀ * cos(p.θ) * t
    y = @. p.v₀ * sin(p.θ) * t - g * t^2 / 2
    pts = [(x[i], y[i]) for i in eachindex(x) if y[i] ≥ 0]
    last = y[end]
    first = x[begin]
    M = [1 2; 3 4]'
    s = :done
    c = 'x'
    r = r"^\d+[a-z]*$"i
    cmd = `ls -l $(pwd())`
    msg = """
        flight time $(t[end]) s
        apex $(maximum(y)) m
        """
    if isempty(pts)
        @warn "no points"
    elseif length(pts) < 3
        return nothing
    else
        for (a, b) in pts
            a > b && continue
        end
    end
    while false
        break
    end
    try
        error("x")
    catch e
        @debug e
    finally
        nothing
    end
    map(pts) do (a, b)
        a + b
    end
    let z = 1
        z += 1
    end
    q = quote
        $x + 1
    end
    return x, y
end

macro twice(ex)
    return esc(quote
        $ex
        $ex
    end)
end

f(x) = x > 0 ? x : -x
g2(x::Vector{<:Real})::Float64 = sum(x) / length(x)
∇f(x) = 2x
x̂ = ∇f(3) ∈ 1:10
Δt₁ = 1e-3; ω₀ = 2π
v = [1, 2, 3] .* 2 .+ 1
A = rand(3, 3); b = A \ v

end # module
