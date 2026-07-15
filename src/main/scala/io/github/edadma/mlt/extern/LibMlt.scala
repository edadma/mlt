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
  type mlt_repository = Ptr[Byte]

  /** A frame count. MLT's `mlt_position` is `int32_t` unless libmlt was built with
    * `DOUBLE_MLT_POSITION`, which redefines it to `double` — an ABI difference that would silently
    * corrupt every seek. The installed headers do not define that flag, so any consumer compiling
    * against them gets the 32-bit form, and this binding follows suit. */
  type mlt_position = CInt

  /** Unlike the service hierarchy, a profile is a plain struct whose fields are public — the video
    * format everything in a graph is rendered against. Its layout is bound directly so the fields
    * can be read without an accessor call.
    *
    * `description, frame_rate_num, frame_rate_den, width, height, progressive, sample_aspect_num,
    * sample_aspect_den, display_aspect_num, display_aspect_den, colorspace, is_explicit` */
  type mlt_profile_s = CStruct12[CString, CInt, CInt, CInt, CInt, CInt, CInt, CInt, CInt, CInt, CInt, CInt]
  type mlt_profile   = Ptr[mlt_profile_s]

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

  // -- Profile ---------------------------------------------------------------------------------

  /** Build a profile from a named preset (e.g. "atsc_1080p_30"), or the environment's default when
    * `name` is null. Returns null if the preset cannot be found. */
  def mlt_profile_init(name: CString): mlt_profile = extern

  /** Fill a profile in from a producer's own video properties — how to adopt a file's native
    * format rather than forcing it into a preset. */
  def mlt_profile_from_producer(profile: mlt_profile, producer: mlt_producer): Unit = extern

  def mlt_profile_fps(profile: mlt_profile): CDouble      = extern
  def mlt_profile_sar(profile: mlt_profile): CDouble      = extern
  def mlt_profile_dar(profile: mlt_profile): CDouble      = extern
  def mlt_profile_is_valid(profile: mlt_profile): CInt    = extern
  def mlt_profile_close(profile: mlt_profile): Unit       = extern

  // -- Service ---------------------------------------------------------------------------------

  /** Pull frame `index` from a service. The frame comes back through `frame` and belongs to the
    * caller, who must release it with [[mlt_frame_close]]. Returns 0 on success. */
  def mlt_service_get_frame(self: mlt_service, frame: Ptr[mlt_frame], index: CInt): CInt = extern

  /** The profile a service renders against. */
  def mlt_service_profile(self: mlt_service): mlt_profile = extern

  // -- Producer --------------------------------------------------------------------------------

  /** Construct a producer for `resource` using `service` (e.g. "avformat" for a media file, "color"
    * for a solid, or "loader" to let MLT choose by inspecting the resource). Returns null if no
    * module can handle it. */
  def mlt_factory_producer(profile: mlt_profile, service: CString, resource: CString): mlt_producer = extern

  /** Release a producer. This decrements its reference count and tears it down only at zero, so it
    * is the correct destructor for a producer — never a bare `mlt_properties_dec_ref`. */
  def mlt_producer_close(self: mlt_producer): Unit = extern

  def mlt_producer_seek(self: mlt_producer, position: mlt_position): CInt                 = extern
  def mlt_producer_position(self: mlt_producer): mlt_position                             = extern
  def mlt_producer_get_length(self: mlt_producer): mlt_position                           = extern
  def mlt_producer_get_playtime(self: mlt_producer): mlt_position                         = extern
  def mlt_producer_get_fps(self: mlt_producer): CDouble                                   = extern
  def mlt_producer_get_in(self: mlt_producer): mlt_position                               = extern
  def mlt_producer_get_out(self: mlt_producer): mlt_position                              = extern
  def mlt_producer_set_in_and_out(self: mlt_producer, in: mlt_position, out: mlt_position): CInt = extern
  def mlt_producer_set_speed(self: mlt_producer, speed: CDouble): CInt                    = extern
  def mlt_producer_get_speed(self: mlt_producer): CDouble                                 = extern

  // -- Frame -----------------------------------------------------------------------------------

  /** Render this frame's image. `format` is in/out: pass the desired [[mlt_image_format]] and MLT
    * converts, writing back what it actually produced. `buffer` receives a pointer to pixels owned
    * by the frame — valid until the frame is closed, and not to be freed. `writable` asks for a
    * buffer safe to modify in place. Returns 0 on success. */
  def mlt_frame_get_image(
      self: mlt_frame,
      buffer: Ptr[Ptr[Byte]],
      format: Ptr[CInt],
      width: Ptr[CInt],
      height: Ptr[CInt],
      writable: CInt,
  ): CInt = extern

  def mlt_frame_get_position(self: mlt_frame): mlt_position = extern

  /** Release a frame obtained from [[mlt_service_get_frame]]. */
  def mlt_frame_close(self: mlt_frame): Unit = extern
