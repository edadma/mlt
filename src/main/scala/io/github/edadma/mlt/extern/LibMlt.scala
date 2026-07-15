package io.github.edadma.mlt.extern

import scala.scalanative.unsafe.*

// Raw MLT C entry points (the `mlt-framework-7` library). MLT's C++ wrapper (mlt++) is deliberately
// not bound — Scala Native cannot call mangled C++ symbols, and the C API is the real one anyway.
//
// The library's SONAME is versioned: the file on disk is `libmlt-7.dylib`, so the link name is
// "mlt-7", not "mlt".
@link("mlt-7")
@extern
object LibMlt:
  // MLT's object model is struct embedding: every type below begins with its parent as its FIRST
  // member, so every "upcast" in the C headers is `(&(x)->parent)` — the address of the first
  // member, which is the same address as the struct itself. Modelling each as an opaque pointer
  // therefore makes the whole hierarchy free to navigate: an upcast is the identity function, and
  // the MLT_*_PROPERTIES macros need no C shim to replicate.
  //
  //   properties <- service <- producer <- playlist
  //                                     <- tractor
  //                         <- consumer
  //                         <- filter
  //                         <- transition
  //   properties <- frame
  //
  // `profile` and `repository` are NOT part of that hierarchy — they are plain structs with their
  // own lifecycles.
  type mlt_properties = Ptr[Byte]
  type mlt_service    = Ptr[Byte]
  type mlt_producer   = Ptr[Byte]
  type mlt_frame      = Ptr[Byte]
  type mlt_consumer   = Ptr[Byte]
  type mlt_filter     = Ptr[Byte]
  type mlt_profile    = Ptr[Byte]
  type mlt_repository = Ptr[Byte]

  // -- Factory ---------------------------------------------------------------------------------

  /** Initialise the framework and load the module repository. `directory` may be null to use the
    * built-in module path. Must be called before anything else; returns null on failure. */
  def mlt_factory_init(directory: CString): mlt_repository = extern

  /** Shut the framework down, releasing the repository and every registered clean-up. */
  def mlt_factory_close(): Unit = extern

  /** The directory the module repository was loaded from. */
  def mlt_factory_directory(): CString = extern

  // -- Version ---------------------------------------------------------------------------------

  /** The full version as a display string, e.g. "7.40.0". */
  def mlt_version_get_string(): CString = extern

  /** The version encoded as a single comparable integer. */
  def mlt_version_get_int(): CInt = extern

  // -- Properties ------------------------------------------------------------------------------
  //
  // The property bag is the bulk of MLT's API surface: most of what a caller does to a producer,
  // filter, or consumer is set a named property on it.

  /** Set `name` to a string `value`. Returns 0 on success. */
  def mlt_properties_set(self: mlt_properties, name: CString, value: CString): CInt = extern

  /** Read `name` as a string. The returned pointer is owned by the property bag — do not free it,
    * and treat it as invalidated by the next set of the same name. Returns null if unset. */
  def mlt_properties_get(self: mlt_properties, name: CString): CString = extern

  def mlt_properties_set_int(self: mlt_properties, name: CString, value: CInt): CInt        = extern
  def mlt_properties_get_int(self: mlt_properties, name: CString): CInt                     = extern
  def mlt_properties_set_int64(self: mlt_properties, name: CString, value: CLongLong): CInt = extern
  def mlt_properties_get_int64(self: mlt_properties, name: CString): CLongLong              = extern
  def mlt_properties_set_double(self: mlt_properties, name: CString, value: CDouble): CInt  = extern
  def mlt_properties_get_double(self: mlt_properties, name: CString): CDouble               = extern

  // Reference counting. Every MLT type in the properties hierarchy is refcounted through these;
  // the Scala facade holds exactly one reference per live wrapper.
  def mlt_properties_inc_ref(self: mlt_properties): CInt   = extern
  def mlt_properties_dec_ref(self: mlt_properties): CInt   = extern
  def mlt_properties_ref_count(self: mlt_properties): CInt = extern

  /** Give back a reference to a plain property bag, tearing it down at zero. Types further down
    * the hierarchy have their own destructor which must be used instead — see `mlt_producer_close`
    * and friends — since each performs its own teardown before delegating here. */
  def mlt_properties_close(self: mlt_properties): Unit = extern
