package io.github.edadma.mlt

import scala.scalanative.unsafe.*

import io.github.edadma.mlt.extern.LibMlt as lib

// The programmer-friendly facade over MLT. Nothing here exposes Scala Native's `unsafe` types;
// callers work in plain Scala values.
//
// MLT differs from a one-handle library like TurboJPEG in a way that shapes this whole layer: it
// is an object *graph* with shared ownership. Everything in the service hierarchy is reference
// counted through `mlt_properties_inc_ref`/`dec_ref`, and each type's own `mlt_X_close` decrements
// that count, tearing the object down only when it reaches zero.
//
// The rule this facade holds to, so that callers never have to reason about MLT's refcounts:
//
//   *** Every live wrapper owns exactly one reference. ***
//
// A wrapper built around a pointer MLT just handed us (a factory result) adopts the reference that
// call already returned. A wrapper built around a pointer we merely borrowed (something reached
// through the graph) takes a reference of its own first. Either way `close()` gives back exactly
// one. That decouples a Scala wrapper's lifetime from the object's membership in the graph: append
// a producer to a playlist, close your wrapper, and the playlist's own reference keeps it alive.

/** Raised when an MLT call fails, or when a closed wrapper is used. */
class MltException(message: String) extends RuntimeException(message)

/** MLT's property bag, and the root of its type hierarchy — a service, producer, filter, consumer,
  * transition, and frame are all property bags, and most of MLT's API is reading and writing named
  * properties on one.
  *
  * Every subclass in this facade wraps the *same* pointer as its parent: MLT's structs each begin
  * with their parent struct as the first member, so an upcast is the identity. That is why the C
  * `MLT_*_PROPERTIES` macros need no shim here.
  */
class Properties private[mlt] (private[mlt] val ptr: lib.mlt_properties):
  private var isClosed = false

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

  /** Give back this wrapper's single reference. Idempotent: closing twice is a no-op rather than a
    * double-free. The object itself survives until every other holder has closed too. */
  def close(): Unit =
    if !isClosed then
      isClosed = true
      destroy()

  /** How this type gives back a reference. Each MLT type has its own destructor which internally
    * decrements the count and tears down only at zero, so subclasses override this to call theirs
    * — a producer must go through `mlt_producer_close`, not a bare `dec_ref`, or its type-specific
    * teardown never runs. */
  protected def destroy(): Unit = lib.mlt_properties_close(ptr)

  // Every accessor funnels through here so that use-after-close surfaces as an ordinary Scala
  // exception with a stack trace, rather than as a segfault inside libmlt.
  private inline def guard[A](inline body: A): A =
    if isClosed then throw new MltException(s"${getClass.getSimpleName} has been closed")
    body

  private def fail(op: String): Nothing = throw new MltException(s"mlt: $op failed")

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
