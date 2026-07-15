package io.github.edadma.mlt

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

/** A node in a rendering graph — the base of producers, filters, transitions, and consumers. What a
  * service can do generically is hand out frames. */
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

  override protected def destroy(): Unit = lib.mlt_producer_close(ptr)

object Producer:
  /** Construct a producer for `resource` — a file path, or a service-specific string such as "red"
    * for the "color" service. `service` names the module to use ("avformat" for media files, "color"
    * for a solid); omit it to let MLT choose by inspecting the resource.
    *
    * Throws if no module can handle the resource. */
  def apply(profile: Profile, resource: String, service: Option[String] = None): Producer = Zone {
    val svc = service.map(toCString(_)).getOrElse(null)
    val p   = lib.mlt_factory_producer(profile.ptr, svc, toCString(resource))

    if p == null then
      throw new MltException(s"mlt: no producer for resource '$resource'${service.fold("")(s => s" (service '$s')")}")
    new Producer(p)
  }

/** A single rendered frame. Obtain one from [[Service.frame]]; it must be closed. */
class Frame private[mlt] (ptr: lib.mlt_frame) extends Properties(ptr):

  /** This frame's position, as a frame number. */
  def position: Int = guard(lib.mlt_frame_get_position(ptr))

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

  /** Render this frame's image into a fresh Scala array — a self-contained copy that outlives the
    * frame. Only the packed formats ([[ImageFormat.Rgb]], [[ImageFormat.Rgba]]) are supported here,
    * since a planar YUV layout has no single meaningful stride to copy against. */
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
  * frame through Cairo therefore costs a channel swap — see [[rgbaToArgb32]]. */
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

extension (f: ImageFormat)
  /** The `mlt_image_format` integer this maps to. */
  def value: Int = f

  /** Bytes per pixel, for the packed formats where that is meaningful. */
  def bytesPerPixel: Int = f match
    case ImageFormat.Rgb    => 3
    case ImageFormat.Rgba   => 4
    case ImageFormat.Rgba64 => 8
    case _                  => throw new MltException(s"mlt: format $f is not packed — bytes-per-pixel is undefined")

/** A rendered image copied out of a frame: its size, its packed pixels, and their layout. */
final case class Image(width: Int, height: Int, pixels: Array[Byte], format: ImageFormat)

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

/** Framework lifecycle and version. [[init]] must run before any other MLT call. */
object Mlt:
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
