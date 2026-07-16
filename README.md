# mlt

Scala Native bindings for [MLT](https://www.mltframework.org/) — the multitrack audio/video
authoring framework that powers Shotcut, Kdenlive, and Flowblade.

> **Status:** early, but it edits, plays, encodes, saves and reopens real video. The framework
> lifecycle, profiles, the property bag, producers (including `avformat` media files), frame
> rendering, consumers, playlists, filters, transitions, multitrack tractors, and MLT XML projects
> all work — `sbt run` assembles a timeline, composites two tracks, decodes a clip frame by frame,
> transcodes it to H.264, and saves a project that reopens as the same graph.

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

A timeline is a `Playlist` — a sequence of cuts and gaps that is itself a producer, so it plays and
exports exactly like the clip above:

```scala
val playlist = Playlist(profile)

playlist.append(source, 0, 29)  // frames 0..29 of the source
playlist.blank(10)              // a ten-frame gap
playlist.append(source, 45, 59) // and a second cut of the same source

playlist.split(0, 10)           // razor the first clip in two
playlist.removeRegion(0, 5)     // ripple delete five frames

for c <- playlist.clips do println(s"${c.start}..${c.start + c.length}: ${c.resource}")
```

Put a playlist on each track of a `Tractor` and combine them with a `Transition`:

```scala
val tractor = Tractor(profile)

tractor.setTrack(background, 0)
tractor.setTrack(overlay, 1)
tractor.refresh()

val pip = Transition(profile, "composite")

pip.set("geometry", "0/0:50%x50%:100") // inset the upper track, top-left quadrant
pip.setInAndOut(0, tractor.length - 1)
tractor.plantTransition(pip, 0, 1)
```

Save the whole graph as a project and open it again — the same MLT XML that Shotcut and Kdenlive
read and write:

```scala
Xml.save(tractor, "project.mlt", title = Some("My Film"))

val loaded  = Xml.load(profile, "project.mlt")
val project = loaded.asTractor.get   // what a project of any substance is

for i <- 0 until project.trackCount do
  val track = project.track(i)

  track.asPlaylist.foreach(pl => println(s"track $i: ${pl.count} entries, ${pl.length} frames"))
```

`sbt run` renders a frame of the bundled `demo.mp4` as a PPM, plays the clip through to the end,
transcodes it to `mlt-export.mp4`, assembles the timeline above, greyscales a clip, composites a
moving inset over a second track, and saves a project, reopens it, edits it, and saves it again.

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

### The loader, and why a producer's construction matters

`Producer(profile, "demo.mp4")` and `Producer(profile, "demo.mp4", Some("avformat"))` look like the
same thing said two ways, and for a while they behave like it. They are not.

Naming no service routes construction through MLT's **loader** producer, which does more than pick a
module by looking at the resource: it wraps that module in MLT's format-conversion filters. Those are
what make `frame.image(ImageFormat.Rgba)` mean anything. Without them a producer still honours the
request — every module renders on demand, into whatever format is asked of it — so nothing looks
wrong until the first filter is attached. A filter renders in whatever format suits it (greyscale
works in `yuv422`), hands that format back regardless of the request, and with no conversion filters
in the graph there is nothing left to put it right:

| producer | asked for `rgba`, with a greyscale filter attached |
|---|---|
| `Producer(profile, "color:red")` — via loader | `rgba` |
| `Producer(profile, "red", Some("color"))` | **`yuv422`** |
| `Producer(profile, "demo.mp4")` — via loader | `rgba` |
| `Producer(profile, "demo.mp4", Some("avformat"))` | **`yuv422`** |

So: omit the service unless you have a reason not to. Naming one is the escape hatch, and it costs
the conversion. This is also why `Service.filterCount` reads 10 on a freshly loaded producer that has
had nothing attached to it — those are the loader's conversion filters, attached filters like any
other.

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

### Tracks, and which one comes out

A `Tractor` holds parallel tracks, numbered from 0 up. It is tempting to read track 0 as "the
output" and the rest as things layered onto it, but that is backwards, and the difference shows the
moment a tractor has two tracks and nothing planted on it: **what comes out is the frame of the
highest-numbered track.** Everything below it may as well not be there. Nothing has said how the
tracks relate, so nothing combines them.

What says how they relate is a transition, and a transition delivers its result onto its `b` track.
That is what makes the conventional arrangement work: number tracks from 0 at the bottom, composite
each onto the one below with a transition whose `a` is the lower and whose `b` is the upper, and the
result walks up the stack to emerge on top.

```scala
tractor.plantTransition(pip, 0, 1) // combine track 0 with track 1; result lands on track 1
```

What picture the combination actually makes is the transition module's own business, and the modules
vary more than their names suggest — `composite` insets its `b` track into its `a` track at the
rectangle its `geometry` names, and a geometry with two keyframes makes that rectangle a path the
inset travels as the frames advance. `Mlt.transitions` lists what is installed and `Mlt.metadata`
reports what each one says about itself; this binding plants them and does not second-guess them.

### Projects: the difference between a graph and a reference

MLT XML stores a graph — producers with their resources, playlists with their cuts and blanks,
tracks, filters, transitions, and the profile it all renders against. A `.mlt` file can also *be* a
producer, since a project may sit on a track of a larger project, and that is where a trap lives.

Given a **path**, MLT remembers the graph as being that file. Serialise it again and what comes out
is a four-line reference to the file rather than the graph — which is right for a nested project, and
silent data loss for an edited one:

```xml
<producer id="tractor0" in="0" out="54">
  <property name="resource">project.mlt</property>
  <property name="mlt_service">xml</property>
  <property name="xml">was here</property>
</producer>
```

Given **text**, there is no file for the graph to be, and it serialises as itself. So `Xml.load`
reads the file and hands MLT the text: open a project, move a clip, save, and the save records the
move. `Producer(profile, "other.mlt")` is the other reading, and the one to use when a project is a
part of the one being built.

The cost is where relative resources resolve from. Normally the `root` attribute in the XML says, and
MLT records one in every project it writes; a project written with `no_root` has none, and then only
the file's own location can answer — which text does not carry.

Opening a project also **overwrites the profile passed to it** with the project's own. A project
records the format it was cut against, and the frame rate its positions were counted in has to
survive the trip or they mean nothing.

### Asking what something is

`Service.serviceType` reports a label rather than a type: MLT answers by reading the object's
`mlt_type` and `resource` properties, and a playlist is a playlist to that call because its
`resource` reads `<playlist>`. It is reliable for anything reached through a graph, and it is how
MLT's own C++ binding decides a downcast — but anything that overwrites `resource` changes the
answer, which is exactly what loading a project by path does to its root.

`Producer.asTractor` therefore examines the object instead of asking it: only a tractor carries the
multitrack it combines, so looking for that answers the question whatever the label says.

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
