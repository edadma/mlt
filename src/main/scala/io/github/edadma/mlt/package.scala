package io.github.edadma.mlt

import scala.annotation.targetName
import scala.io.Source
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

import io.github.edadma.mlt.extern.LibMlt as lib

// The programmer-friendly facade over MLT. Nothing here exposes Scala Native's `unsafe` types except
// the one pointer accessor meant for zero-copy access to a frame's pixels; the rest works in plain
// Scala values.
//
// MLT differs from a one-handle library like TurboJPEG in a way that shapes this whole layer: it is
// an object *graph* with shared ownership. Everything in the service hierarchy is reference counted
// through `mlt_properties_inc_ref`/`dec_ref`, and each type's own `mlt_X_close` decrements that count,
// tearing the object down only when it reaches zero.
//
// The rule this facade holds to, so that callers never have to reason about MLT's refcounts:
//
//   *** Every live wrapper owns exactly one reference. ***
//
// A wrapper built around a pointer MLT just handed us (a factory result) adopts the reference that
// call already returned. A wrapper built around a pointer we merely borrowed (something reached
// through the graph) takes a reference of its own first. Either way `close()` gives back exactly one.
// That decouples a Scala wrapper's lifetime from the object's membership in the graph: append a
// producer to a playlist, close your wrapper, and the playlist's own reference keeps it alive.

/** Raised when an MLT call fails, or when a closed wrapper is used. */
class MltException(message: String) extends RuntimeException(message)

/** Close-once machinery shared by everything holding a native handle.
  *
  * `close()` is idempotent and every accessor is guarded, which turns MLT's two nastiest failure
  * modes — double-close and use-after-free — from segfaults inside libmlt into ordinary Scala
  * exceptions with a stack trace. On a graph API that is worth the branch. */
trait MltResource:
  private[mlt] var isClosed: Boolean = false

  /** Release this wrapper's hold on the native object. Idempotent. */
  def close(): Unit =
    if !isClosed then
      isClosed = true
      destroy()

  /** How this type releases its handle. Each MLT type has its own destructor which internally
    * decrements the reference count and tears down only at zero, so an implementation must call its
    * own — a producer must go through `mlt_producer_close`, not a bare `dec_ref`, or its
    * type-specific teardown never runs. */
  protected def destroy(): Unit

  protected inline def guard[A](inline body: A): A =
    if isClosed then throw new MltException(s"${getClass.getSimpleName} has been closed")
    body

  protected def fail(op: String): Nothing = throw new MltException(s"mlt: $op failed")

/** MLT's property bag, and the root of its type hierarchy — a service, producer, filter, consumer,
  * transition, and frame are all property bags, and most of MLT's API is reading and writing named
  * properties on one.
  *
  * Every subclass here wraps the *same* pointer as its parent: MLT's structs each begin with their
  * parent struct as the first member, so an upcast is the identity. That is why the C
  * `MLT_*_PROPERTIES` macros need no shim in this binding. */
class Properties private[mlt] (private[mlt] val ptr: lib.mlt_properties) extends MltResource:

  /** Set a string property. */
  def set(name: String, value: String): Unit = guard {
    Zone(if lib.mlt_properties_set(ptr, toCString(name), toCString(value)) != 0 then fail(s"set($name)"))
  }

  /** Read a string property, or `None` if it is unset. The underlying buffer belongs to MLT and is
    * invalidated by a later set, so it is copied into a Scala string here. */
  def get(name: String): Option[String] = guard {
    Zone {
      val s = lib.mlt_properties_get(ptr, toCString(name))

      if s == null then None else Some(fromCString(s))
    }
  }

  def setInt(name: String, value: Int): Unit = guard {
    Zone(if lib.mlt_properties_set_int(ptr, toCString(name), value) != 0 then fail(s"setInt($name)"))
  }

  def getInt(name: String): Int = guard(Zone(lib.mlt_properties_get_int(ptr, toCString(name))))

  def setDouble(name: String, value: Double): Unit = guard {
    Zone(if lib.mlt_properties_set_double(ptr, toCString(name), value) != 0 then fail(s"setDouble($name)"))
  }

  def getDouble(name: String): Double = guard(Zone(lib.mlt_properties_get_double(ptr, toCString(name))))

  /** MLT's current reference count for this object — a debugging aid. */
  def refCount: Int = guard(lib.mlt_properties_ref_count(ptr))

  protected def destroy(): Unit = lib.mlt_properties_close(ptr)

/** Wrap a pointer reached *through* the graph rather than returned by a factory. MLT still owns it,
  * so take a reference of our own: that is what lets the wrapper be closed on its own schedule
  * without the graph losing the object, and keeps the one-reference-per-wrapper rule intact. */
private def borrowed[A](ptr: lib.mlt_properties)(wrap: lib.mlt_properties => A): A =
  lib.mlt_properties_inc_ref(ptr)
  wrap(ptr)

/** A node in a rendering graph — the base of producers, filters, transitions, and consumers. What a
  * service can do generically is hand out frames, and have filters attached to it. */
class Service private[mlt] (ptr: lib.mlt_service) extends Properties(ptr):

  /** Render the next frame from this service. The returned frame belongs to the caller and must be
    * closed. `index` selects a track on a multitrack service; 0 is the only meaningful value for a
    * plain producer. */
  def frame(index: Int = 0): Frame = guard {
    val out = stackalloc[lib.mlt_frame]()

    if lib.mlt_service_get_frame(ptr, out, index) != 0 then fail("mlt_service_get_frame")
    if !out == null then fail("mlt_service_get_frame returned no frame")
    new Frame(!out)
  }

  /** Attach a filter, so every frame this service produces passes through it. Attaching is
    * independent of the wrapper's lifetime — the service takes its own reference, so the filter may
    * be closed here and goes on filtering.
    *
    * Filters apply in the order attached. Attach to a clip's producer to affect that clip, or to a
    * playlist to affect the whole track. */
  def attach(filter: Filter): Unit =
    guard(if lib.mlt_service_attach(ptr, filter.ptr) != 0 then fail("mlt_service_attach"))

  /** Remove a filter attached to this service. */
  def detach(filter: Filter): Unit =
    guard(if lib.mlt_service_detach(ptr, filter.ptr) != 0 then fail("mlt_service_detach"))

  /** How many filters are attached — including ones the caller never attached. A producer built
    * through the "loader" arrives with MLT's format-conversion filters already on it, and they are
    * attached filters like any other; expect a count in double figures on a fresh producer. */
  def filterCount: Int = guard(lib.mlt_service_filter_count(ptr))

  /** The attached filter at `index`, as a wrapper of its own that must be closed. */
  def filterAt(index: Int): Filter = guard {
    val f = lib.mlt_service_filter(ptr, index)

    if f == null then throw new MltException(s"mlt: no filter at index $index")
    borrowed(f)(new Filter(_))
  }

  /** Reorder the attached filters, which is to reorder how they apply. */
  def moveFilter(from: Int, to: Int): Unit =
    guard(if lib.mlt_service_move_filter(ptr, from, to) != 0 then fail("mlt_service_move_filter"))

  /** Which subclass of service this reports itself as.
    *
    * It reports a *label*, not a type: MLT answers by reading the object's `mlt_type` and `resource`
    * properties, and a playlist is a playlist to this call because its `resource` reads "<playlist>".
    * That is reliable for anything reached through a graph, and it is how MLT's own C++ binding
    * decides a downcast — but a project loaded from a file has had its root's `resource` overwritten
    * with the file's path, and so reports [[ServiceType.Producer]] whatever it really is. Prefer
    * [[Producer.asTractor]], which examines the object instead of asking it. */
  def serviceType: ServiceType = guard(ServiceType.of(lib.mlt_service_identify(ptr)))

/** Anything that produces frames over a timeline — a media file, a generated colour, a playlist, a
  * multitrack tractor.
  *
  * Positions are frame counts against the profile's frame rate, not timestamps. */
class Producer private[mlt] (ptr: lib.mlt_producer) extends Service(ptr):

  /** Move the playhead to `position` (a frame number). */
  def seek(position: Int): Unit = guard(if lib.mlt_producer_seek(ptr, position) != 0 then fail("seek"))

  /** The current playhead position, as a frame number. */
  def position: Int = guard(lib.mlt_producer_position(ptr))

  /** Total length in frames. */
  def length: Int = guard(lib.mlt_producer_get_length(ptr))

  /** Length in frames of the in..out region actually played. */
  def playtime: Int = guard(lib.mlt_producer_get_playtime(ptr))

  def fps: Double = guard(lib.mlt_producer_get_fps(ptr))
  def in: Int     = guard(lib.mlt_producer_get_in(ptr))
  def out: Int    = guard(lib.mlt_producer_get_out(ptr))

  /** Trim to the region `in..out` (inclusive frame numbers) — the cut this producer contributes when
    * it is part of a larger graph. */
  def setInAndOut(in: Int, out: Int): Unit =
    guard(if lib.mlt_producer_set_in_and_out(ptr, in, out) != 0 then fail("set_in_and_out"))

  /** Playback rate: 1.0 normal, 0.0 paused, negative reverse. */
  def speed: Double            = guard(lib.mlt_producer_get_speed(ptr))
  def speed_=(v: Double): Unit = guard(if lib.mlt_producer_set_speed(ptr, v) != 0 then fail("set_speed"))

  /** This producer as a [[Tractor]], if it is one — how a loaded project's tracks are reached, since
    * [[Xml.load]] can only promise a producer.
    *
    * Unlike [[serviceType]] this examines the object rather than believing its label: only a tractor
    * carries the multitrack it combines, so asking for that answers the question even for a project
    * root whose label the XML producer has overwritten.
    *
    * The result is a wrapper of its own, with a reference of its own, and must be closed. Closing it
    * does not disturb this one. */
  def asTractor: Option[Tractor] = guard {
    if lib.mlt_tractor_multitrack(ptr) == null then scala.None else Some(borrowed(ptr)(new Tractor(_)))
  }

  /** This producer as a [[Playlist]], if it is one — how a track of a loaded project is read as the
    * sequence of clips it is.
    *
    * This goes on the label ([[serviceType]]), there being nothing on a playlist to test for the way
    * a tractor has its multitrack. That is sound for a track reached through a [[Tractor]], which is
    * where a project keeps its playlists, and for anything [[Xml.load]] returns. It is not sound for a
    * project reached by path through [[Producer.apply]], whose root MLT relabels: that answers `None`
    * here whatever it is.
    *
    * The result is a wrapper of its own and must be closed. */
  def asPlaylist: Option[Playlist] = guard {
    if serviceType != ServiceType.Playlist then scala.None else Some(borrowed(ptr)(new Playlist(_)))
  }

  override protected def destroy(): Unit = lib.mlt_producer_close(ptr)

object Producer:
  /** Construct a producer for `resource` — a file path, or a service-specific string such as
    * "color:red" for a solid.
    *
    * **Omit `service` unless you have a reason not to.** Doing so routes construction through MLT's
    * "loader" producer, which does more than pick a module by looking at the resource: it wraps the
    * module in MLT's format-conversion filters. Without them, a producer renders whatever format is
    * asked of it — but only until a filter is attached, because a filter that renders in a format of
    * its own (greyscale works in `yuv422`) hands that format back regardless of the request, and
    * nothing is left to convert it. Naming a service is the escape hatch, and it costs the
    * conversion.
    *
    * Throws if no module can handle the resource. */
  def apply(profile: Profile, resource: String, service: Option[String] = None): Producer = Zone {
    val svc = service.map(toCString(_)).getOrElse(null)
    val p   = lib.mlt_factory_producer(profile.ptr, svc, toCString(resource))

    if p == null then
      throw new MltException(s"mlt: no producer for resource '$resource'${service.fold("")(s => s" (service '$s')")}")
    new Producer(p)
  }

/** What a playlist knows about one of its entries — the shape a timeline draws from.
  *
  * `start` and `length` place the entry on the playlist; `in` and `out` say which part of the source
  * it shows. A [[blank]] entry is a gap: it has a start and a length and nothing else.
  *
  * This is a snapshot, not a view. Any edit to the playlist invalidates what it says. */
final case class ClipInfo(
    index: Int,
    start: Int,
    length: Int,
    in: Int,
    out: Int,
    sourceLength: Int,
    fps: Double,
    repeat: Int,
    resource: Option[String],
    blank: Boolean,
)

/** A sequence of clips and blanks, played end to end — and a producer in its own right, which is
  * what makes it a timeline track: an editor's whole track plays as a single continuous piece of
  * video, and can be filtered, put on a [[Tractor]], or rendered on its own.
  *
  * Positions come in two frames of reference, and mixing them up is the easiest mistake to make
  * here. An *index* numbers the entries, counting blanks; a *position* is a frame number on the
  * playlist's own timeline. [[clipIndexAt]] converts. */
class Playlist private[mlt] (ptr: lib.mlt_playlist) extends Producer(ptr):

  /** Append the whole of `producer` as the next entry. */
  def append(producer: Producer): Unit =
    guard(if lib.mlt_playlist_append(ptr, producer.ptr) != 0 then fail("mlt_playlist_append"))

  /** Append only `in..out` of `producer` (inclusive frame numbers on the *source*).
    *
    * This is a cut, and it does not disturb the producer's own in/out points — so one producer can
    * appear on a playlist any number of times, each time showing a different part of itself. That is
    * how an editor puts three pieces of the same clip on a track without opening the file thrice. */
  def append(producer: Producer, in: Int, out: Int): Unit =
    guard(if lib.mlt_playlist_append_io(ptr, producer.ptr, in, out) != 0 then fail("mlt_playlist_append_io"))

  /** Append a gap of `length` frames. */
  def blank(length: Int): Unit =
    guard(if lib.mlt_playlist_blank(ptr, length - 1) != 0 then fail("mlt_playlist_blank"))

  /** The number of entries, counting blanks. */
  def count: Int = guard(lib.mlt_playlist_count(ptr))

  def clear(): Unit = guard(if lib.mlt_playlist_clear(ptr) != 0 then fail("mlt_playlist_clear"))

  /** Insert `in..out` of `producer` before entry `index`, shifting the rest later. */
  def insert(producer: Producer, index: Int, in: Int, out: Int): Unit =
    guard(if lib.mlt_playlist_insert(ptr, producer.ptr, index, in, out) != 0 then fail("mlt_playlist_insert"))

  /** Remove entry `index`, closing the gap it leaves. */
  def remove(index: Int): Unit = guard(if lib.mlt_playlist_remove(ptr, index) != 0 then fail("mlt_playlist_remove"))

  /** Move entry `from` to `to`, shifting whatever lies between. */
  def move(from: Int, to: Int): Unit = guard(if lib.mlt_playlist_move(ptr, from, to) != 0 then fail("mlt_playlist_move"))

  /** Retrim entry `index` to show `in..out` of its source — the edit dragging a clip's edge makes. */
  def resizeClip(index: Int, in: Int, out: Int): Unit =
    guard(if lib.mlt_playlist_resize_clip(ptr, index, in, out) != 0 then fail("mlt_playlist_resize_clip"))

  /** Cut entry `index` in two at `position`, counted from that entry's own start — the razor tool. */
  def split(index: Int, position: Int): Unit =
    guard(if lib.mlt_playlist_split(ptr, index, position) != 0 then fail("mlt_playlist_split"))

  /** Cut whatever lies under `position` on the playlist. The frame at `position` starts the second
    * of the two entries. */
  def splitAt(position: Int): Unit =
    guard(if lib.mlt_playlist_split_at(ptr, position, 0) != 0 then fail("mlt_playlist_split_at"))

  /** Rejoin `count` entries from `index` back into one, undoing a split. */
  def join(index: Int, count: Int): Unit =
    guard(if lib.mlt_playlist_join(ptr, index, count, 0) != 0 then fail("mlt_playlist_join"))

  /** Overlap the last `length` frames of entry `index` with the start of the next, rendering the
    * overlap through `transition` — a cross-fade. The playlist gets shorter by `length`.
    *
    * Without a transition the overlap is a hard cut on the later clip; "luma" wipes, and "mix" is
    * the audio equivalent. */
  def mix(index: Int, length: Int, transition: Option[Transition] = scala.None): Unit =
    guard(
      if lib.mlt_playlist_mix(ptr, index, length, transition.map(_.ptr).getOrElse(null)) != 0 then
        fail("mlt_playlist_mix"),
    )

  /** Everything the playlist knows about entry `index`. */
  def clipInfo(index: Int): ClipInfo = guard {
    val info = stackalloc[lib.mlt_playlist_clip_info]()

    if lib.mlt_playlist_get_clip_info(ptr, info, index) != 0 then fail(s"mlt_playlist_get_clip_info($index)")

    // The resource string is borrowed from the entry and dies with the next edit, so copy it out.
    val resource = if info._5 == null then scala.None else Some(fromCString(info._5))

    ClipInfo(
      index = info._1,
      start = info._4,
      length = info._8,
      in = info._6,
      out = info._7,
      sourceLength = info._9,
      fps = info._10.toDouble,
      repeat = info._11,
      resource = resource,
      blank = lib.mlt_playlist_is_blank(ptr, index) != 0,
    )
  }

  /** Every entry, in order — one pass over the whole track. */
  def clips: Seq[ClipInfo] = (0 until count).map(clipInfo)

  /** The producer of entry `index`, as a wrapper of its own that must be closed. */
  def clipAt(index: Int): Producer = guard {
    val p = lib.mlt_playlist_get_clip(ptr, index)

    if p == null then throw new MltException(s"mlt: no clip at index $index")
    borrowed(p)(new Producer(_))
  }

  /** The index of the entry playing at `position` on the playlist. */
  def clipIndexAt(position: Int): Int = guard(lib.mlt_playlist_get_clip_index_at(ptr, position))

  /** Where entry `index` begins, as a frame number on the playlist. */
  def clipStart(index: Int): Int = guard(lib.mlt_playlist_clip_start(ptr, index))

  /** How many frames entry `index` occupies on the playlist. */
  def clipLength(index: Int): Int = guard(lib.mlt_playlist_clip_length(ptr, index))

  /** Whether entry `index` is a gap rather than a clip. */
  def isBlank(index: Int): Boolean = guard(lib.mlt_playlist_is_blank(ptr, index) != 0)

  def isBlankAt(position: Int): Boolean = guard(lib.mlt_playlist_is_blank_at(ptr, position) != 0)

  /** Fold runs of adjacent blanks into single ones. `keepLength` preserves the playlist's total
    * length by leaving trailing blanks alone. */
  def consolidateBlanks(keepLength: Boolean = false): Unit =
    guard(lib.mlt_playlist_consolidate_blanks(ptr, if keepLength then 1 else 0))

  /** Cut `length` frames out of the playlist at `position`, closing the gap — ripple delete. */
  def removeRegion(position: Int, length: Int): Unit =
    guard(if lib.mlt_playlist_remove_region(ptr, position, length) != 0 then fail("mlt_playlist_remove_region"))

  override protected def destroy(): Unit = lib.mlt_playlist_close(ptr)

object Playlist:
  /** An empty playlist rendering against `profile`. */
  def apply(profile: Profile): Playlist =
    val p = lib.mlt_playlist_new(profile.ptr)

    if p == null then throw new MltException("mlt: mlt_playlist_new failed")
    new Playlist(p)

/** A service that modifies the frames of a single producer — a colour correction, a blur, a fade.
  *
  * A filter does nothing until it is attached ([[Service.attach]]) or planted on a track of a
  * [[Tractor]]. What it does is set by its properties, whose names are the filter module's own.
  *
  * Attaching a filter is what first makes a producer's construction matter: a filter renders in
  * whatever format suits it, and only a producer built through the "loader" — one constructed
  * without naming a service — carries the conversion filters that put the frame back into the format
  * the caller asked for. See [[Producer.apply]]. */
class Filter private[mlt] (ptr: lib.mlt_filter) extends Service(ptr):

  /** Limit the filter to frames `in..out` of what it is attached to — how a filter becomes an
    * effect that starts and stops. A filter left alone applies throughout. */
  def setInAndOut(in: Int, out: Int): Unit = guard(lib.mlt_filter_set_in_and_out(ptr, in, out))

  def in: Int     = guard(lib.mlt_filter_get_in(ptr))
  def out: Int    = guard(lib.mlt_filter_get_out(ptr))
  def length: Int = guard(lib.mlt_filter_get_length(ptr))

  /** Which track of a [[Tractor]] this was planted on, or 0 if it was simply attached. */
  def track: Int = guard(lib.mlt_filter_get_track(ptr))

  /** Keep the filter in the graph but stop it doing anything — what an effect's on/off switch sets,
    * and why it is not the same as detaching. */
  def disabled: Boolean            = getInt("disable") != 0
  def disabled_=(v: Boolean): Unit = setInt("disable", if v then 1 else 0)

  override protected def destroy(): Unit = lib.mlt_filter_close(ptr)

object Filter:
  /** Construct a filter from a named module — "greyscale", "brightness", and whatever else is
    * installed ([[Mlt.filters]] lists them). `arg` is module-specific. Throws if there is no such
    * module. */
  def apply(profile: Profile, service: String, arg: Option[String] = scala.None): Filter = Zone {
    val a = arg.map(toCString(_)).getOrElse(null)
    val f = lib.mlt_factory_filter(profile.ptr, toCString(service), a)

    if f == null then throw new MltException(s"mlt: no filter '$service'")
    new Filter(f)
  }

/** A service that combines the frames of *two* producers — a wipe, a dissolve, an overlay.
  *
  * A transition reaches a graph one of two ways: [[Playlist.mix]] overlaps two clips on one track
  * with it, or [[Tractor.plantTransition]] runs it between two tracks. Either way it needs to know
  * over which frames it runs ([[setInAndOut]]) — a transition with no span does nothing. */
class Transition private[mlt] (ptr: lib.mlt_transition) extends Service(ptr):

  /** The frames over which the transition runs. */
  def setInAndOut(in: Int, out: Int): Unit = guard(lib.mlt_transition_set_in_and_out(ptr, in, out))

  /** Which two tracks this combines. `a` is the track underneath, `b` the one on top — the one
    * being transitioned *to*. [[Tractor.plantTransition]] sets these for you. */
  def setTracks(a: Int, b: Int): Unit = guard(lib.mlt_transition_set_tracks(ptr, a, b))

  def aTrack: Int = guard(lib.mlt_transition_get_a_track(ptr))
  def bTrack: Int = guard(lib.mlt_transition_get_b_track(ptr))
  def in: Int     = guard(lib.mlt_transition_get_in(ptr))
  def out: Int    = guard(lib.mlt_transition_get_out(ptr))
  def length: Int = guard(lib.mlt_transition_get_length(ptr))

  /** Ignore [[in]]/[[out]] and run over every frame. This is what an overlay wants — compositing a
    * track onto another is not an event with a duration, it is simply how the two relate. */
  def alwaysActive: Boolean            = getInt("always_active") != 0
  def alwaysActive_=(v: Boolean): Unit = setInt("always_active", if v then 1 else 0)

  def disabled: Boolean            = getInt("disable") != 0
  def disabled_=(v: Boolean): Unit = setInt("disable", if v then 1 else 0)

  override protected def destroy(): Unit = lib.mlt_transition_close(ptr)

object Transition:
  /** Construct a transition from a named module — "luma" to wipe, "mix" for audio, "frei0r.cairoblend"
    * or "composite" to overlay ([[Mlt.transitions]] lists them). Throws if there is no such module. */
  def apply(profile: Profile, service: String, arg: Option[String] = scala.None): Transition = Zone {
    val a = arg.map(toCString(_)).getOrElse(null)
    val t = lib.mlt_factory_transition(profile.ptr, toCString(service), a)

    if t == null then throw new MltException(s"mlt: no transition '$service'")
    new Transition(t)
  }

/** Parallel tracks combined into one picture — the multitrack timeline, and a producer like any
  * other, so it renders through the same consumers as a bare clip. Give each track a [[Playlist]]
  * and this is an editor's timeline.
  *
  * A tractor does nothing to combine its tracks by itself. **With no transitions planted, what comes
  * out is the frame of the highest-numbered track**, and the tracks below it may as well not be
  * there: nothing has said how they relate. Combining them is a transition ([[plantTransition]]),
  * and per-track effects are filters ([[plantFilter]]); MLT calls the place both are planted the
  * tractor's *field*.
  *
  * A transition delivers its result onto its `b` track, so a tractor's output is whatever ends up on
  * the top track. That is what makes the usual arrangement work: tracks numbered from 0 at the
  * bottom, each composited onto the one below with a transition whose `a` is the lower track and
  * whose `b` is the upper. */
class Tractor private[mlt] (ptr: lib.mlt_tractor) extends Producer(ptr):

  /** Put `producer` on track `index`, replacing whatever was there. */
  def setTrack(producer: Producer, index: Int): Unit =
    guard(if lib.mlt_tractor_set_track(ptr, producer.ptr, index) != 0 then fail("mlt_tractor_set_track"))

  /** Put `producer` on track `index`, shifting the tracks above it up. */
  def insertTrack(producer: Producer, index: Int): Unit =
    guard(if lib.mlt_tractor_insert_track(ptr, producer.ptr, index) != 0 then fail("mlt_tractor_insert_track"))

  def removeTrack(index: Int): Unit =
    guard(if lib.mlt_tractor_remove_track(ptr, index) != 0 then fail("mlt_tractor_remove_track"))

  /** The producer on track `index`, as a wrapper of its own that must be closed. */
  def track(index: Int): Producer = guard {
    val p = lib.mlt_tractor_get_track(ptr, index)

    if p == null then throw new MltException(s"mlt: no track at index $index")
    borrowed(p)(new Producer(_))
  }

  def trackCount: Int = guard(lib.mlt_multitrack_count(lib.mlt_tractor_multitrack(ptr)))

  /** Apply `filter` to track `index` as it is combined. Unlike attaching a filter to the track's own
    * producer, this belongs to the tractor, and survives the track being replaced. */
  def plantFilter(filter: Filter, index: Int): Unit =
    guard(if lib.mlt_field_plant_filter(lib.mlt_tractor_field(ptr), filter.ptr, index) != 0 then fail("plant_filter"))

  /** Combine track `a` (underneath) with track `b` (on top) through `transition`. This is how a
    * tractor comes to show more than one track — and the result lands on track `b`, which is why the
    * top track is the one that comes out.
    *
    * Set the transition's [[Transition.setInAndOut]] span first, or [[Transition.alwaysActive]] for
    * an overlay that has no duration to speak of. What picture the combination actually makes is the
    * transition module's own business: "composite" insets `b` into `a` at the rectangle its
    * `geometry` names, and each module has properties of its own that [[Mlt.metadata]] reports. */
  def plantTransition(transition: Transition, a: Int, b: Int): Unit =
    guard(
      if lib.mlt_field_plant_transition(lib.mlt_tractor_field(ptr), transition.ptr, a, b) != 0 then
        fail("plant_transition"),
    )

  /** Recompute the tractor's length from its tracks — a tractor is as long as its longest one. Call
    * after changing what is on a track, or the length is the one from before. */
  def refresh(): Unit = guard(lib.mlt_tractor_refresh(ptr))

  override protected def destroy(): Unit = lib.mlt_tractor_close(ptr)

object Tractor:
  /** An empty tractor rendering against `profile`.
    *
    * MLT's own constructor takes no profile — alone among the ones here — so the profile is set on
    * the tractor's service afterwards. */
  def apply(profile: Profile): Tractor =
    val t = lib.mlt_tractor_new()

    if t == null then throw new MltException("mlt: mlt_tractor_new failed")
    lib.mlt_service_set_profile(t, profile.ptr)
    new Tractor(t)

/** The far end of a graph: what pulls frames out of it. Consumers come in two shapes, and which one
  * you have decides the whole shape of your loop.
  *
  * A **module consumer** ([[Consumer.apply]]) drives itself — "avformat" encodes to a file, "sdl2"
  * renders to a window. Connect a producer, [[start]], and it runs on its own render threads until
  * the input ends; the application waits on [[isStopped]] rather than pulling. Do not also pull from
  * one: its threads are consuming the same frames, and the two of you will race for them.
  *
  * A **bare consumer** ([[Consumer.bare]]) has no output of its own. It is the one for an application
  * that wants the frames itself — a preview pane — and the loop is [[rtFrame]] until the stream ends.
  *
  * Frames must be pulled from a thread the application owns, and used on it. MLT's render threads are
  * C threads the Scala Native runtime knows nothing about, so no Scala code may run on one — which is
  * why this binding exposes no `mlt_events` listeners such as `consumer-frame-show`. Pull; do not be
  * called back. */
class Consumer private[mlt] (ptr: lib.mlt_consumer) extends Service(ptr):

  /** Attach the service this consumer pulls from. */
  def connect(service: Service): Unit =
    guard(if lib.mlt_consumer_connect(ptr, service.ptr) != 0 then fail("mlt_consumer_connect"))

  /** Begin consuming, spawning a module consumer's render threads. Set the properties that configure
    * those threads — [[realTime]], [[buffer]], [[imageFormat]] — before calling this; they are read
    * once, here.
    *
    * On a bare consumer this does nothing at all, by MLT's design rather than by omission: the first
    * thing `mlt_consumer_start` does is return if the consumer is not stopped, and a consumer with no
    * module behind it has no way to report itself stopped, so it always looks like it is already
    * running. Call it anyway — it costs nothing and keeps a graph's setup uniform. */
  def start(): Unit = guard(if lib.mlt_consumer_start(ptr) != 0 then fail("mlt_consumer_start"))

  /** Stop consuming, joining any render threads. */
  def stop(): Unit = guard(if lib.mlt_consumer_stop(ptr) != 0 then fail("mlt_consumer_stop"))

  /** Whether the consumer has finished or been stopped. This is how to wait out a module consumer,
    * paired with [[terminateOnPause]].
    *
    * Always false on a bare consumer, which has no module to answer the question — detect the end of
    * a pull loop with [[Frame.speed]] instead. */
  def isStopped: Boolean = guard(lib.mlt_consumer_is_stopped(ptr) != 0)

  /** Throw away frames already decoded into the read-ahead buffer. Needed after a seek during
    * playback, or the frames decoded before the jump are shown after it. */
  def purge(): Unit = guard(lib.mlt_consumer_purge(ptr))

  /** The position of the frame most recently pulled. */
  def position: Int = guard(lib.mlt_consumer_position(ptr))

  /** Pull the next frame. On a bare consumer this renders on the calling thread; the frame's image
    * is not decoded until asked for, so the cost lands in [[Frame.imagePtr]], not here.
    *
    * `None` means MLT could produce nothing at all — not the end of the stream. Running off the end
    * of a producer yields real frames forever, repeating the last one, so a pull loop must stop on
    * [[Frame.speed]] reaching zero instead.
    *
    * The frame belongs to the caller and must be closed — dropping one on the floor here leaks a
    * whole decoded image. */
  def rtFrame(): Option[Frame] = guard {
    val f = lib.mlt_consumer_rt_frame(ptr)

    if f == null then scala.None else Some(new Frame(f))
  }

  /** Pull the next frame, bypassing the read-ahead buffer even on a module consumer that has one.
    * On a bare consumer this is what [[rtFrame]] already does. */
  def getFrame(): Frame = guard {
    val f = lib.mlt_consumer_get_frame(ptr)

    if f == null then fail("mlt_consumer_get_frame")
    new Frame(f)
  }

  /** Stop once the input runs out, rather than sitting on its last frame forever. This is what makes
    * [[isStopped]] a usable end condition when exporting. */
  def terminateOnPause: Boolean            = getInt("terminate_on_pause") != 0
  def terminateOnPause_=(v: Boolean): Unit = setInt("terminate_on_pause", if v then 1 else 0)

  /** How a module consumer paces itself: 1 asynchronous with frame dropping (MLT's default), -1
    * asynchronous without dropping, 0 synchronous. An export wants 0 — dropping frames to keep pace
    * with a clock is exactly wrong when there is no clock to keep pace with.
    *
    * Read once by [[start]], so set it before. Inert on a bare consumer, whose `start` is a no-op and
    * which therefore has no render threads to pace. */
  def realTime: Int            = getInt("real_time")
  def realTime_=(v: Int): Unit = setInt("real_time", v)

  /** The format a module consumer's render threads decode into (MLT defaults to `yuv422`). Setting it
    * to the format the frames will actually be used in moves the conversion onto a render thread.
    *
    * Inert on a bare consumer: with no render threads, the format is settled by what
    * [[Frame.imagePtr]] asks for. */
  def imageFormat: ImageFormat            = get("mlt_image_format").fold(ImageFormat.None)(ImageFormat.named)
  def imageFormat_=(f: ImageFormat): Unit = set("mlt_image_format", f.name)

  /** How many frames a module consumer's read-ahead buffer holds. Inert on a bare consumer. */
  def buffer: Int            = getInt("buffer")
  def buffer_=(v: Int): Unit = setInt("buffer", v)

  /** The number of frames a module consumer has dropped since starting — how far behind the clock it
    * has fallen. */
  def dropCount: Int = getInt("drop_count")

  override protected def destroy(): Unit = lib.mlt_consumer_close(ptr)

object Consumer:
  /** Construct a consumer from a named module — "sdl2" to render to a window, "avformat" to encode
    * to `resource` as a file, "null" to pull and discard. Throws if no module can handle it. */
  def apply(profile: Profile, service: String, resource: Option[String] = scala.None): Consumer = Zone {
    val res = resource.map(toCString(_)).getOrElse(null)
    val c   = lib.mlt_factory_consumer(profile.ptr, toCString(service), res)

    if c == null then throw new MltException(s"mlt: no consumer for service '$service'")
    new Consumer(c)
  }

  /** A consumer with no output of its own — it pulls frames and hands them over, and does nothing
    * else with them. This is the one an application that draws video itself wants, and the only way
    * to get the frames: a module consumer's own threads would be competing for them.
    *
    * It renders synchronously, on whatever thread calls [[Consumer.rtFrame]], and has no clock — MLT
    * paces playback inside the module consumers that own an output, so a bare one runs as fast as it
    * is asked to. Both the frame timing and any decode-ahead are therefore the application's own to
    * arrange, which is the right division anyway: a decode thread the application created is a thread
    * the Scala Native runtime knows about, and MLT's render threads are not. */
  def bare(profile: Profile): Consumer =
    val c = lib.mlt_consumer_new(profile.ptr)

    if c == null then throw new MltException("mlt: mlt_consumer_new failed")
    new Consumer(c)

/** MLT XML — the project format, and what makes a graph outlive the process that built it.
  *
  * It is the same format Shotcut and Kdenlive save, so a project written here opens there and theirs
  * opens here. What it stores is the graph: the producers with their resources and properties, the
  * playlists with their cuts and blanks, the tracks, the filters, the transitions, and the profile
  * everything renders against. Reference `.mlt` files by path from another `.mlt` and they nest.
  *
  * Saving is a consumer and loading is a producer, which is worth knowing because it means neither
  * needs anything special to work — but both have a surprise in them, and each is documented on the
  * method it belongs to. */
object Xml:

  /** Write `root` and everything it reaches to `path` as MLT XML.
    *
    * Unlike an encode, this finishes before it returns. The XML consumer serialises the graph as it
    * starts, and by default renders no frames at all, so there is nothing to wait on — set
    * `processAllFrames` only for a filter that works in two passes (vidstab is the usual one),
    * measuring the footage on the first and storing what it learned as properties for the second. A
    * save that does not render is a save that costs nothing.
    *
    * `includeMeta` keeps the `meta.*` properties, which is where MLT files away what it learned about
    * a media file when it opened it — the codec, the stream layout, the tags. It is the larger half
    * of a small project file, and it is all rederivable by opening the media again, so a project that
    * is under version control or is going to be edited by hand is better off without it. */
  def save(
      root: Producer,
      path: String,
      title: Option[String] = scala.None,
      includeMeta: Boolean = true,
      processAllFrames: Boolean = false,
  ): Unit = run(root, path, title, includeMeta, processAllFrames)(_ => ())

  /** Serialise `root` to a string rather than a file — a project saved without touching the disk,
    * which is what an undo stack wants a snapshot to be.
    *
    * MLT has no separate service for this. The XML consumer decides between writing a file and
    * storing a property by looking for a period in its resource: a name with no extension is not a
    * file but the name of a property to leave the XML in. This method hides that, and takes the
    * property back out.
    *
    * The result is the same XML [[save]] writes, minus the indentation. */
  def serialise(
      root: Producer,
      title: Option[String] = scala.None,
      includeMeta: Boolean = true,
      processAllFrames: Boolean = false,
  ): String = run(root, "xml_string", title, includeMeta, processAllFrames)(
    _.get("xml_string").getOrElse(throw new MltException("mlt: the xml consumer stored nothing")),
  )

  /** Both entry points are the same consumer, differing only in what its resource is taken to mean.
    * The profile comes from the graph itself — a service already knows the one it renders against, and
    * the consumer must share it or the two disagree about what a frame is. */
  private def run[A](root: Producer, resource: String, title: Option[String], meta: Boolean, all: Boolean)(
      take: Consumer => A,
  ): A = Zone {
    val profile = lib.mlt_service_profile(root.ptr)

    if profile == null then throw new MltException("mlt: the graph has no profile to serialise against")

    val c = lib.mlt_factory_consumer(profile, c"xml", toCString(resource))

    if c == null then throw new MltException("mlt: no xml consumer — is the xml module installed?")

    val consumer = new Consumer(c)

    try
      title.foreach(consumer.set("title", _))
      if !meta then consumer.setInt("no_meta", 1)
      if all then consumer.setInt("all", 1)

      consumer.connect(root)
      consumer.start()

      val a = take(consumer)

      consumer.stop()
      a
    finally consumer.close()
  }

  /** Open the project at `path` — the graph it describes, ready to be edited and saved again.
    *
    * **`profile` is overwritten by the project's own.** A project records the format it was cut
    * against, and opening it makes that format the one `profile` describes: a PAL profile handed to a
    * 720p project comes back describing 720p. That is what opening a project should mean — the frame
    * rate the positions were counted in has to survive the trip or they mean nothing — but it is a
    * mutation of something the caller owns, and it happens whether or not the profile was asked for
    * by name.
    *
    * What comes back is the project's outermost producer, which for a project of any substance is a
    * tractor: reach its tracks with [[Producer.asTractor]].
    *
    * This reads the file and hands MLT the text, rather than handing MLT the path, and the difference
    * is one of *identity*: given a path, MLT remembers the graph as being that file, and serialising
    * it again writes a four-line reference to the file instead of the graph — so an edit made to a
    * project opened that way is silently dropped by the next save. Given text, there is no file for
    * the graph to be, and it serialises as itself. The cost is where relative resources are resolved
    * from: normally the `root` attribute in the XML says, and MLT records one in every project it
    * writes, but a project written with `no_root` leaves it out, and then only the file's own location
    * can answer — which text does not carry. Such a project loses its relative media here.
    *
    * `Producer(profile, path)` is the other reading, and the right one for a project that is a *part*
    * of the one being built: it stays a reference, which is what nesting a project on a track means. */
  def load(profile: Profile, path: String): Producer =
    val source =
      try Source.fromFile(path)
      catch case e: Exception => throw new MltException(s"mlt: cannot read project '$path' — ${e.getMessage}")

    val xml =
      try source.mkString
      finally source.close()

    parse(profile, xml)

  /** Read a project back from a string — the other half of [[serialise]], and how an undo stack gets
    * its snapshot back. [[load]]'s notes about the profile and about relative resources apply here
    * too, this being what it is built on. */
  def parse(profile: Profile, xml: String): Producer = Producer(profile, xml, Some("xml-string"))

/** A single rendered frame. Obtain one from [[Service.frame]]; it must be closed. */
class Frame private[mlt] (ptr: lib.mlt_frame) extends Properties(ptr):

  /** This frame's position, as a frame number. */
  def position: Int = guard(lib.mlt_frame_get_position(ptr))

  /** The rate the producer was playing at when this frame was made: 1.0 normal, negative reverse,
    * and **zero once the producer has run out of material**.
    *
    * That last case is how a pull loop knows to stop, since running off the end of a producer does
    * not fail: it goes on yielding perfectly good frames, repeating the final one indefinitely. MLT
    * advances the producer after making each frame, so the first frame reporting `speed == 0` is one
    * produced *past* the end — a duplicate of the last real frame, to be discarded rather than shown.
    *
    * (A frame whose image is never rendered reports the terminal speed one frame earlier, on the last
    * real frame instead of the repeat. Loops that draw or encode what they pull — which is all of
    * them — see the behaviour described above.) */
  def speed: Double = getDouble("_speed")

  /** Render this frame's image and expose the pixels in place — no copy. The returned pointer is
    * owned by the frame and stays valid only until the frame is closed.
    *
    * `format` is a request, not a guarantee: MLT converts if it can, and reports back what it
    * actually produced, which is what the result carries. This is the path for handing video to a
    * drawing surface; for a self-contained copy use [[image]] instead. */
  def imagePtr(format: ImageFormat): (Ptr[Byte], Int, Int, ImageFormat) = guard {
    val buf = stackalloc[Ptr[Byte]]()
    val fmt = stackalloc[CInt]()
    val w   = stackalloc[CInt]()
    val h   = stackalloc[CInt]()

    !fmt = format.value

    if lib.mlt_frame_get_image(ptr, buf, fmt, w, h, 0) != 0 then fail("mlt_frame_get_image")
    (!buf, !w, !h, ImageFormat.of(!fmt))
  }

  /** Render this frame's image and describe it plane by plane — no copy, and the path for handing
    * video to a GPU.
    *
    * A planar format keeps each component in its own run of memory rather than interleaving them:
    * [[ImageFormat.Yuv420p]] is a full-resolution plane of luma followed by two half-size planes of
    * chroma. That is what a video decoder produces and what a graphics API takes for a video
    * texture, and uploading it as-is lets the GPU do the conversion to RGB while it draws — which
    * is why this, and not [[imagePtr]], is the preview path. Converting to [[ImageFormat.Rgba]] on
    * the CPU first would cost a full pass over every frame, and a second one to reorder the
    * channels for a drawing surface (see [[rgbaToArgb32]]).
    *
    * The packed formats are simply one-plane images here, so this describes any format MLT renders.
    * For [[ImageFormat.Yuv420p]] the planes are Y, U, V in that order.
    *
    * The pointers belong to the frame and die with it, exactly as [[imagePtr]]'s do. `format` is a
    * request and the result reports what was actually produced. */
  def imagePlanes(format: ImageFormat = ImageFormat.Yuv420p): ImagePlanes = guard {
    val (buf, w, h, actual) = imagePtr(format)
    val img                 = lib.mlt_image_new()

    if img == null then throw new MltException("mlt: could not allocate an image description")
    try
      // Borrows the buffer — it stores the pointer and nulls the destructor — so closing the
      // description below leaves the frame's pixels alone.
      lib.mlt_image_set_values(img, buf, actual.value, w, h)

      val planes  = img._5
      val strides = img._6
      val out = (0 until 4).iterator
        .map(i => Plane(!planes.at(i), !strides.at(i)))
        .takeWhile(_.data != null)
        .toIndexedSeq

      ImagePlanes(w, h, actual, out)
    finally lib.mlt_image_close(img)
  }

  /** The Y'CbCr standard this frame's values are encoded against.
    *
    * Every frame reports one, including frames nothing decoded: MLT renders a graph against its
    * profile's colorspace, so even a generated picture comes back carrying it. Anything converting
    * this frame's colour — a GPU that is handed [[imagePlanes]] to convert while drawing — needs it,
    * and getting it wrong is not an error, only a picture whose colours are quietly off. */
  def colorspace: Colorspace = Colorspace.of(getInt("colorspace"))

  /** Whether the picture uses the full 0..255 per component rather than video's studio range, which
    * keeps 16..235 for luma and reserves the rest as headroom. The other half of interpreting the
    * values — the same numbers mean different colours under each. */
  def fullRange: Boolean = getInt("full_range") != 0

  /** Render this frame's image into a fresh Scala array — a self-contained copy that outlives the
    * frame. Only the packed formats ([[ImageFormat.Rgb]], [[ImageFormat.Rgba]]) are supported here,
    * since a planar YUV layout has no single meaningful stride to copy against — use
    * [[imagePlanes]] for those. */
  def image(format: ImageFormat = ImageFormat.Rgba): Image =
    val (buf, w, h, actual) = imagePtr(format)
    val size                = w * h * actual.bytesPerPixel
    val out                 = new Array[Byte](size)
    var i                   = 0

    while i < size do
      out(i) = buf(i)
      i += 1
    Image(w, h, out, actual)

  override protected def destroy(): Unit = lib.mlt_frame_close(ptr)

/** The video format a graph renders against — resolution, frame rate, aspect ratio. Every service in
  * a graph shares one, and it is what a producer's output is normalised to.
  *
  * A profile is not part of MLT's service hierarchy: it is a plain struct with public fields and its
  * own lifecycle, not a reference-counted property bag. */
class Profile private[mlt] (private[mlt] val ptr: lib.mlt_profile) extends MltResource:
  def description: String  = guard(fromCString(ptr._1))
  def frameRateNum: Int    = guard(ptr._2)
  def frameRateDen: Int    = guard(ptr._3)
  def width: Int           = guard(ptr._4)
  def height: Int          = guard(ptr._5)
  def progressive: Boolean = guard(ptr._6 != 0)

  /** The Y'CbCr standard this graph renders against. Every frame it produces carries this, whether
    * or not anything decoded one — which is what makes it answerable before a frame exists, as
    * something that must size and configure a video surface up front needs. */
  def colorspace: Colorspace = guard(Colorspace.of(ptr._11))

  /** Frame rate as a single number — `frameRateNum / frameRateDen`. */
  def fps: Double = guard(lib.mlt_profile_fps(ptr))

  /** Sample (pixel) aspect ratio. */
  def sar: Double = guard(lib.mlt_profile_sar(ptr))

  /** Display aspect ratio. */
  def dar: Double = guard(lib.mlt_profile_dar(ptr))

  def isValid: Boolean = guard(lib.mlt_profile_is_valid(ptr) != 0)

  /** Adopt `producer`'s own video format — how to render a file at its native resolution and rate
    * rather than forcing it into a preset. */
  def adoptFormatOf(producer: Producer): Unit = guard(lib.mlt_profile_from_producer(ptr, producer.ptr))

  override def toString: String = s"Profile($description, ${width}x$height @ ${fps}fps)"

  protected def destroy(): Unit = lib.mlt_profile_close(ptr)

object Profile:
  /** Build a profile from a named preset, e.g. "atsc_1080p_30". Throws if no such preset exists. */
  def apply(name: String): Profile = Zone {
    val p = lib.mlt_profile_init(toCString(name))

    if p == null then throw new MltException(s"mlt: no such profile '$name'")
    new Profile(p)
  }

  /** The environment's default profile — what MLT falls back to when nothing is specified. */
  def default: Profile =
    val p = lib.mlt_profile_init(null)

    if p == null then throw new MltException("mlt: could not create default profile")
    new Profile(p)

/** The pixel layout of a rendered image — an `mlt_image_format` value.
  *
  * Note what is *absent*: MLT has no BGRA. [[ImageFormat.Rgba]] is byte order R,G,B,A, while Cairo's
  * ARGB32 is native-endian and so wants B,G,R,A in memory on a little-endian machine. Painting an MLT
  * frame through Cairo therefore costs a channel swap — see [[rgbaToArgb32]].
  *
  * That cost is a reason to avoid drawing video through Cairo at all rather than a reason to pay it:
  * a graphics API that takes [[ImageFormat.Yuv420p]] through [[Frame.imagePlanes]] converts the frame while it
  * draws it, so nothing on the CPU touches the pixels. Keep the packed formats for stills and for
  * pixels being inspected; give moving video to the GPU planar. */
opaque type ImageFormat = Int

object ImageFormat:
  val None: ImageFormat      = 0
  val Rgb: ImageFormat       = 1
  val Rgba: ImageFormat      = 2
  val Yuv422: ImageFormat    = 3
  val Yuv420p: ImageFormat   = 4
  val Yuv422p16: ImageFormat = 7
  val Yuv420p10: ImageFormat = 8
  val Yuv444p10: ImageFormat = 9
  val Rgba64: ImageFormat    = 10
  val Invalid: ImageFormat   = 11

  private[mlt] def of(v: Int): ImageFormat = v

  /** The format a name denotes, e.g. "rgba" — the spelling MLT's own string properties use. */
  def named(name: String): ImageFormat = Zone(lib.mlt_image_format_id(toCString(name)))

extension (f: ImageFormat)
  /** The `mlt_image_format` integer this maps to. */
  def value: Int = f

  /** MLT's own name for this format, e.g. "rgba" — what to write when setting it as a property. */
  def name: String = fromCString(lib.mlt_image_format_name(f))

  /** Bytes per pixel, for the packed formats where that is meaningful. */
  def bytesPerPixel: Int = f match
    case ImageFormat.Rgb    => 3
    case ImageFormat.Rgba   => 4
    case ImageFormat.Rgba64 => 8
    case _                  => throw new MltException(s"mlt: format $f is not packed — bytes-per-pixel is undefined")

/** A rendered image copied out of a frame: its size, its packed pixels, and their layout. */
final case class Image(width: Int, height: Int, pixels: Array[Byte], format: ImageFormat)

/** One component plane of a rendered image: where its first byte is, and how many bytes one row of
  * it occupies.
  *
  * `stride` is not the same as the plane's width in bytes, and must not be assumed to be: a row can
  * be followed by padding so the next one starts on an alignment a decoder or a GPU prefers. Walk a
  * plane by adding `stride` per row, never by multiplying out the width. */
final case class Plane(data: Ptr[Byte], stride: Int)

/** A frame's rendered image described plane by plane — see [[Frame.imagePlanes]].
  *
  * `planes` holds only the planes this format actually uses, in the order the format defines them:
  * three for [[ImageFormat.Yuv420p]] (Y, U, V), one for a packed format like [[ImageFormat.Rgba]],
  * whose single plane is the whole interleaved image.
  *
  * `width` and `height` describe the *image*. A subsampled chroma plane is smaller than that — half
  * on each axis for 4:2:0 — so its own extent is the image's scaled down, and its rows are found
  * through its own [[Plane.stride]].
  *
  * The pointers are borrowed from the frame and are valid only while it is open. */
final case class ImagePlanes(width: Int, height: Int, format: ImageFormat, planes: IndexedSeq[Plane])

/** The Y'CbCr standard a picture's values are encoded against — an `mlt_colorspace` value.
  *
  * Luma and chroma are not colours by themselves; they become colours through a set of coefficients,
  * and this says which set. Its numbers are mostly the standards' own — 709, 601, 2020 — rather than
  * a dense enumeration, so treat it as a tag and compare against the named values here.
  *
  * The distinction that matters in practice is HD versus SD: [[Colorspace.Bt709]] is essentially all
  * HD video, while [[Colorspace.Bt601]], [[Colorspace.Bt470bg]] (625-line PAL), [[Colorspace.Smpte170m]]
  * (525-line NTSC) and [[Colorspace.Smpte240m]] are the SD family and share its coefficients. Something
  * choosing between two conversions wants to treat that whole family alike. */
opaque type Colorspace = Int

object Colorspace:
  /** Coefficients for RGB itself — the values are already colour, in G,B,R order. */
  val Rgb: Colorspace = 0

  /** The source declared nothing. MLT resolves this to a standard by picture height when it must. */
  val Unspecified: Colorspace = 2
  val Reserved: Colorspace    = 3
  val Fcc: Colorspace         = 4
  val Ycgco: Colorspace       = 8
  val Smpte2085: Colorspace   = 11

  /** 525-line NTSC — the SD family, same coefficients as [[Bt601]]. */
  val Smpte170m: Colorspace = 170

  /** Functionally identical to [[Smpte170m]]. */
  val Smpte240m: Colorspace = 240

  /** 625-line PAL and SECAM — the SD family, same coefficients as [[Bt601]]. */
  val Bt470bg: Colorspace = 470

  /** Standard-definition video. */
  val Bt601: Colorspace = 601

  /** High-definition video — what an HD file almost always is. */
  val Bt709: Colorspace = 709

  val Bt2020Ncl: Colorspace = 2020
  val Bt2020Cl: Colorspace  = 2021

  private[mlt] def of(v: Int): Colorspace = v

// Colorspace and ImageFormat are both opaque over Int, so their extensions erase to the same
// signatures and need distinct names on the JVM side of the fence.
extension (c: Colorspace)
  /** The `mlt_colorspace` integer this maps to. */
  @targetName("colorspaceValue")
  def value: Int = c

  /** MLT's own name for this standard, e.g. "bt709". */
  @targetName("colorspaceName")
  def name: String = fromCString(lib.mlt_image_colorspace_name(c))

/** Rewrite `count` pixels of MLT's [[ImageFormat.Rgba]] output, in place, into the byte order Cairo's
  * ARGB32 expects on a little-endian machine — swapping the red and blue channels.
  *
  * This exists because MLT cannot be asked for BGRA the way an image codec can, so the conversion has
  * to happen somewhere; doing it here keeps it next to the format that forces it. It works a 32-bit
  * word at a time rather than a byte at a time, which matters at video rates — a 1080p frame is
  * 8.3 MB, so 30fps is ~250 MB/s through this loop.
  *
  * Alpha is left as-is. Cairo's ARGB32 is *premultiplied*; opaque video (alpha 255) is already
  * trivially premultiplied, but a source with real transparency would need that step too. */
def rgbaToArgb32(buffer: Ptr[Byte], count: Int): Unit =
  val words = buffer.asInstanceOf[Ptr[UInt]]
  var i     = 0

  // A little-endian load of the bytes R,G,B,A reads as 0xAABBGGRR; Cairo wants the bytes B,G,R,A,
  // which reads as 0xAARRGGBB. So hold alpha and green still and exchange the outer two channels.
  while i < count do
    val v = words(i).toInt

    words(i) = ((v & 0xff00ff00) | ((v & 0x000000ff) << 16) | ((v >>> 16) & 0x000000ff)).toUInt
    i += 1

/** Which subclass of service an object actually is — what [[Service.serviceType]] reports.
  *
  * This overlaps [[ServiceKind]] without being it: a kind is a category of *module* the repository
  * lists, while a type is what a given object turned out to be. Only a type distinguishes a tractor
  * from a playlist from a plain producer, all three of which are producers as far as the repository
  * is concerned. */
enum ServiceType:
  case Invalid, Unknown, Producer, Tractor, Playlist, Multitrack, Filter, Transition, Consumer, Field, Link, Chain

object ServiceType:
  private[mlt] def of(v: Int): ServiceType =
    if v >= 0 && v < values.length then fromOrdinal(v) else ServiceType.Unknown

/** Which kind of service a name refers to. The same name can mean different things to different
  * kinds — "mix" is both a filter and a transition — so looking anything up in the repository takes
  * both. */
enum ServiceKind(private[mlt] val value: Int):
  case Producer   extends ServiceKind(2)
  case Filter     extends ServiceKind(6)
  case Transition extends ServiceKind(7)
  case Consumer   extends ServiceKind(8)

/** The kind of value a parameter takes, from MLT's metadata vocabulary. A control panel maps this to
  * a widget: a slider for `Integer`/`Float`, a checkbox for `Boolean`, a colour well for `Color`, a
  * geometry editor for `Rect`, a text field for `Text`. `Other` carries the raw type name for the few
  * structural kinds a module may declare (`properties`, `seq`, `map`, `time`) that a generic editor
  * renders as text. A parameter that declares no type has `None` at [[ParameterMetadata.paramType]],
  * which MLT treats as a string. */
enum ParameterType:
  case Integer, Float, Boolean, Text, Color, Rect
  case Other(raw: String)

object ParameterType:
  /** MLT's metadata spells string three ways (`string`/`str`/`text`) and folds them to one here. */
  private[mlt] def fromRaw(s: String): ParameterType = s match
    case "integer"                 => Integer
    case "float"                   => Float
    case "boolean"                 => Boolean
    case "string" | "str" | "text" => Text
    case "color"                   => Color
    case "rect"                    => Rect
    case other                     => Other(other)

/** One tunable of a module — enough to render a control for it and know which property to set on the
  * [[Filter]]/[[Transition]]/producer to drive it. `identifier` is that property name. The bounds and
  * default are the module's own strings, left unparsed because their shape follows [[paramType]] (a
  * `Rect` default is `"0/0:100%x100%"`, an `Integer` default is `"0"`). `values` is the closed set of
  * allowed strings for an enumerated parameter, empty when it is free-valued. `mutable` marks a
  * parameter that can be changed during playback, `animation` one that accepts keyframes. */
final case class ParameterMetadata(
    identifier: String,
    title: Option[String],
    description: Option[String],
    paramType: Option[ParameterType],
    minimum: Option[String],
    maximum: Option[String],
    default: Option[String],
    unit: Option[String],
    widget: Option[String],
    values: Seq[String],
    mutable: Boolean,
    animation: Boolean,
    argument: Boolean,
)

/** What a module says about itself, for presenting it in a browser rather than requiring the user to
  * already know the name. Modules are not obliged to supply any of this, and many older ones do
  * not — hence [[Mlt.metadata]] returning an option and the title/description being ones too.
  * `parameters` lists the module's tunables (empty when it declares none), which is what builds an
  * effect's control panel. */
final case class ServiceMetadata(
    name: String,
    title: Option[String],
    description: Option[String],
    parameters: Seq[ParameterMetadata],
)

/** Framework lifecycle and version. [[init]] must run before any other MLT call. */
object Mlt:

  /** The names of every installed producer module — "avformat", "color", "qtext", and so on. */
  def producers: Seq[String] = names(lib.mlt_repository_producers(repository))

  /** The names of every installed filter module. This plus [[metadata]] is an effects browser. */
  def filters: Seq[String] = names(lib.mlt_repository_filters(repository))

  /** The names of every installed transition module. */
  def transitions: Seq[String] = names(lib.mlt_repository_transitions(repository))

  /** The names of every installed consumer module — the available outputs. */
  def consumers: Seq[String] = names(lib.mlt_repository_consumers(repository))

  /** What `service` says about itself, or `None` if the module supplies no metadata — which is
    * common enough that a browser must have something to fall back on. The returned
    * [[ServiceMetadata.parameters]] describe the module's tunables, from which a control panel is
    * built. */
  def metadata(kind: ServiceKind, service: String): Option[ServiceMetadata] = Zone {
    val m = lib.mlt_repository_metadata(repository, kind.value, toCString(service))

    if m == null then scala.None
    else Some(ServiceMetadata(service, propStr(m, "title"), propStr(m, "description"), parameters(m)))
  }

  /** Read a string property, `None` when it is unset. */
  private def propStr(p: lib.mlt_properties, name: String)(using Zone): Option[String] =
    val s = lib.mlt_properties_get(p, toCString(name))

    if s == null then scala.None else Some(fromCString(s))

  /** MLT's metadata spells a boolean as the YAML scalar `yes`/`no` (some modules use `1`/`0`); absent
    * reads as false. */
  private def propBool(p: lib.mlt_properties, name: String)(using Zone): Boolean =
    lib.mlt_properties_get(p, toCString(name)) match
      case s if s == null => false
      case s              => val t = fromCString(s); t == "yes" || t == "1" || t == "true"

  /** Walk a module's `parameters:` sequence. The metadata YAML parser stores it as a child property
    * bag reached by [[lib.mlt_properties_get_data]], each entry itself a bag reached by position; a
    * parameter with no `identifier` is not something a UI can bind, so it is dropped. */
  private def parameters(meta: lib.mlt_properties)(using Zone): Seq[ParameterMetadata] =
    val len    = stackalloc[CInt]()
    val params = lib.mlt_properties_get_data(meta, toCString("parameters"), len)

    if params == null then Seq.empty
    else
      (0 until lib.mlt_properties_count(params)).flatMap { i =>
        val p = lib.mlt_properties_get_data_at(params, i, len)

        if p == null then scala.None
        else
          propStr(p, "identifier").map { id =>
            ParameterMetadata(
              identifier = id,
              title = propStr(p, "title"),
              description = propStr(p, "description"),
              paramType = propStr(p, "type").map(ParameterType.fromRaw),
              minimum = propStr(p, "minimum"),
              maximum = propStr(p, "maximum"),
              default = propStr(p, "default"),
              unit = propStr(p, "unit"),
              widget = propStr(p, "widget"),
              values = enumeratedValues(p),
              mutable = propBool(p, "mutable"),
              animation = propBool(p, "animation"),
              argument = propBool(p, "argument"),
            )
          }
      }

  /** The closed set of allowed strings for an enumerated parameter — its `values:` sequence, whose
    * scalar entries the parser keeps as ordinary indexed values. Empty when the parameter is free. */
  private def enumeratedValues(param: lib.mlt_properties)(using Zone): Seq[String] =
    val len    = stackalloc[CInt]()
    val values = lib.mlt_properties_get_data(param, toCString("values"), len)

    if values == null then Seq.empty
    else
      (0 until lib.mlt_properties_count(values)).flatMap { i =>
        val v = lib.mlt_properties_get_value(values, i)

        if v == null then scala.None else Some(fromCString(v))
      }

  /** The repository is created by [[init]] and closed by [[close]] — borrowed here, never released. */
  private def repository: lib.mlt_repository =
    val r = lib.mlt_factory_repository()

    if r == null then throw new MltException("mlt: no repository — Mlt.init() has not been called")
    r

  /** MLT returns a list of services as a property bag whose *names* are the service names. */
  private def names(props: lib.mlt_properties): Seq[String] =
    if props == null then Seq.empty
    else (0 until lib.mlt_properties_count(props)).map(i => fromCString(lib.mlt_properties_get_name(props, i)))
  /** MLT's version as a display string, e.g. "7.40.0". Safe to call before [[init]]. */
  def version: String = fromCString(lib.mlt_version_get_string())

  /** MLT's version as a single comparable integer. Safe to call before [[init]]. */
  def versionInt: Int = lib.mlt_version_get_int()

  /** Initialise the framework and load the module repository. Pass a `directory` to override where
    * modules are looked for; the default finds the ones installed alongside libmlt. Throws if the
    * repository cannot be loaded. */
  def init(directory: Option[String] = None): Unit = Zone {
    val dir  = directory.map(toCString(_)).getOrElse(null)
    val repo = lib.mlt_factory_init(dir)

    if repo == null then throw new MltException("mlt: mlt_factory_init failed — no module repository found")
  }

  /** The directory the module repository was loaded from. */
  def directory: String = fromCString(lib.mlt_factory_directory())

  /** Shut the framework down. No MLT object may be used afterwards. */
  def close(): Unit = lib.mlt_factory_close()
