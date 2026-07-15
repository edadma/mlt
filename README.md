# mlt

Scala Native bindings for [MLT](https://www.mltframework.org/) — the multitrack audio/video
authoring framework that powers Shotcut, Kdenlive, and Flowblade.

> **Status:** early. The framework lifecycle, version, and property bag are bound and working;
> the service hierarchy (producers, filters, consumers, playlists) is next.

## Requirements

MLT 7 (developed against 7.40.0):

```
brew install mlt
```

No build configuration is needed — Scala Native's toolchain discovery already searches
`/opt/homebrew/{include,lib}`, and MLT's headers use relative includes internally, so the
default paths resolve `libmlt-7` and its headers as-is.

## Usage

```scala
import io.github.edadma.mlt.*

@main def run(): Unit =
  println(s"MLT ${Mlt.version}")

  Mlt.init()
  println(s"modules: ${Mlt.directory}")
  Mlt.close()
```

## Design

Two layers, as with the other `edadma` bindings:

- **`io.github.edadma.mlt.extern.LibMlt`** — the raw `@extern` C entry points. The library links
  as `mlt-7`, not `mlt`; the SONAME is versioned.
- **`io.github.edadma.mlt`** — the idiomatic facade. No `unsafe` types escape it.

### Why there is no C shim

MLT expresses inheritance by struct embedding, and navigates it with macros — `MLT_PRODUCER_PROPERTIES(p)`
and friends, which Scala Native cannot call. But every one of those macros expands to
`(&(x)->parent)`, and `parent` is the **first** member of every struct in the hierarchy. The address
of the first member is the address of the struct, so every upcast is the identity function. Modelling
each type as an opaque pointer makes the whole hierarchy free to navigate, and the macros need no
shim to replicate:

```
properties <- service <- producer <- playlist
                                  <- tractor
                      <- consumer
                      <- filter
                      <- transition
properties <- frame
```

(`profile` and `repository` sit outside that hierarchy — plain structs with their own lifecycles.)

### Ownership

MLT is an object *graph* with shared ownership, reference counted through
`mlt_properties_inc_ref`/`dec_ref`. Each type's own `mlt_X_close` decrements that count and tears the
object down only at zero — so a producer must be released with `mlt_producer_close`, never a bare
`dec_ref`, or its type-specific teardown never runs.

The facade holds to one rule, so callers never reason about refcounts:

> **Every live wrapper owns exactly one reference.**

A wrapper around a pointer MLT just returned adopts that reference; a wrapper around a borrowed
pointer takes one of its own. Either way `close()` gives back exactly one. A wrapper's lifetime is
therefore independent of the object's membership in the graph — append a producer to a playlist,
close your wrapper, and the playlist's reference keeps it alive.

`close()` is idempotent, and every accessor is guarded, so double-close and use-after-close raise a
Scala exception with a stack trace instead of segfaulting inside libmlt.

## License

ISC
