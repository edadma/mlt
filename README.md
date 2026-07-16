# mlt

Scala Native bindings for [MLT](https://www.mltframework.org/) — the multitrack audio/video
authoring framework that powers Shotcut, Kdenlive, and Flowblade.

> **Status:** early, but it plays and encodes real video. The framework lifecycle, profiles, the
> property bag, producers (including `avformat` media files), frame rendering, and consumers all
> work — `sbt run` decodes a clip frame by frame and transcodes it to H.264. Playlists, filters, and
> transitions are next.

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
  Mlt.init()

  val profile  = Profile("atsc_720p_30")
  val producer = Producer(profile, "demo.mp4")

  producer.seek(producer.length / 2)

  val frame = producer.frame()
  val img   = frame.image(ImageFormat.Rgba)

  println(s"${img.width}x${img.height}, ${img.pixels.length} bytes")

  frame.close()
  producer.close()
  profile.close()
  Mlt.close()
```

To play a clip rather than sample it, pull frames through a consumer:

```scala
val producer = Producer(profile, "demo.mp4")
val consumer = Consumer.bare(profile)

consumer.connect(producer)
consumer.start()

var running = true

while running do
  val frame = consumer.rtFrame().get

  val (pixels, w, h, _) = frame.imagePtr(ImageFormat.Rgba)

  running = frame.speed != 0 // the end; this frame is a repeat of the last

  if running then draw(pixels, w, h)

  frame.close()
```

`sbt run` renders a frame of the bundled `demo.mp4` as a PPM, plays the clip through to the end, and
transcodes it to `mlt-export.mp4`.

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

### Two kinds of consumer

A consumer is the far end of a graph, and which kind you have decides the shape of your loop.

A **module consumer** — `Consumer(profile, "avformat", Some("out.mp4"))`, or `"sdl2"` for a window —
drives itself. Connect a producer, `start()`, and its own render threads run it to completion while
the application waits on `isStopped`. Set `terminateOnPause = true` or it will sit on the last frame
forever rather than finishing. Don't also pull from one: its threads are consuming the same frames,
and you will race them for it.

A **bare consumer** — `Consumer.bare(profile)` — has no output of its own, and is the one for an
application that wants the frames itself. It is worth being precise about what it does, because it is
less than the API suggests: `mlt_consumer_start` returns immediately unless the consumer reports
itself stopped, and a consumer with no module behind it can never report that, so **`start()` is a
no-op and the render threads never exist**. `real_time`, `buffer`, and `mlt_image_format` configure
those threads, so on a bare consumer they are inert; `rtFrame()` renders synchronously on the calling
thread, and `isStopped` is always false. (Measured, not assumed: pulls are flat at ~1.9ms each at
every `real_time` setting, with none of the slow-first-pull signature of a prefilling buffer.)

That is the right division for an application anyway. It means the decode thread is one the
application created — a thread the Scala Native runtime knows about — where MLT's render threads are
not. For the same reason this binding exposes no `mlt_events` listeners: `consumer-frame-show` and
friends fire on MLT's own C threads, and no Scala code may run on one. Pull; do not be called back.

### Knowing when a clip ends

Nothing fails at the end of a producer. It goes on yielding perfectly good frames, repeating the
final one, and `rtFrame()` goes on succeeding forever. The signal is `Frame.speed`, which is the
producer's speed at the moment the frame was made and drops to zero once it has run out of material.

MLT advances the producer *after* making each frame, so the first frame reporting `speed == 0` is one
produced past the end — a duplicate of the last real frame. Stop on it, and discard it rather than
show it. That yields exactly the clip: 60 frames, positions 0..59, no repeats.

### Pixel formats, and the one unavoidable copy

MLT's `mlt_image_format` offers only `rgb` and `rgba` — byte order R,G,B,A. There is **no BGRA**.
Cairo's `ARGB32` is native-endian, so on a little-endian machine it wants B,G,R,A in memory.

That matters because an image codec can usually be *asked* for a given layout and made to write
straight into a target surface. MLT cannot, so painting a frame through Cairo costs a channel swap.
`rgbaToArgb32` does it in place, a 32-bit word at a time — at 1080p30 that path moves ~250 MB/s.

Note also that MLT renders letterbox/pillarbox padding as **transparent** black (alpha 0) while the
picture itself is alpha 255 — so a surface receiving a frame should be cleared to a known colour
rather than assuming full coverage.

## License

ISC
