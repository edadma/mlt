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
  type mlt_transition = Ptr[Byte]
  type mlt_playlist   = Ptr[Byte]
  type mlt_tractor    = Ptr[Byte]
  type mlt_multitrack = Ptr[Byte]
  type mlt_repository = Ptr[Byte]

  /** A field is not in the hierarchy above: it is the tractor's registry of planted filters and
    * transitions, owned by the tractor and closed with it. */
  type mlt_field = Ptr[Byte]

  /** `mlt_whence` — what a relative position is measured from. */
  final val mlt_whence_relative_start   = 0
  final val mlt_whence_relative_current = 1
  final val mlt_whence_relative_end     = 2

  /** `mlt_service_type` — the subclass a service reports itself as, and the key the repository
    * indexes its metadata by. */
  final val mlt_service_producer_type   = 2
  final val mlt_service_filter_type     = 6
  final val mlt_service_transition_type = 7
  final val mlt_service_consumer_type   = 8

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

  /** `mlt_playlist_clip_info` — everything a playlist knows about one entry, filled in by
    * [[mlt_playlist_get_clip_info]]. This is the shape a timeline draws from: where the clip sits on
    * the playlist, which part of the source it shows, and where that source came from.
    *
    * `clip, producer, cut, start, resource, frame_in, frame_out, frame_count, length, fps, repeat`
    *
    * The `producer`/`cut` pointers and the `resource` string are borrowed from the playlist entry and
    * are invalidated by any edit to it. */
  type mlt_playlist_clip_info = CStruct11[
    CInt,        // clip — index within the playlist
    mlt_producer, // producer — the clip's producer (or a cut's parent)
    mlt_producer, // cut
    mlt_position, // start — where this begins on the playlist
    CString,     // resource
    mlt_position, // frame_in
    mlt_position, // frame_out
    mlt_position, // frame_count — the clip's duration as edited
    mlt_position, // length — the source's unedited duration
    CFloat,      // fps
    CInt,        // repeat
  ]

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

  // A property bag doubles as MLT's list type — the repository returns the available services as one,
  // keyed by service name. These three walk it by position.
  def mlt_properties_count(self: mlt_properties): CInt                 = extern
  def mlt_properties_get_name(self: mlt_properties, index: CInt): CString  = extern
  def mlt_properties_get_value(self: mlt_properties, index: CInt): CString = extern

  // A property may hold an opaque data value rather than a string — the module-metadata YAML parser
  // stores nested maps and sequences (a module's `parameters:` list, a parameter's `values:` list)
  // as child `mlt_properties` reached this way. Both return borrowed pointers owned by the parent;
  // `length` may be null when the size is not wanted.
  def mlt_properties_get_data(self: mlt_properties, name: CString, length: Ptr[CInt]): Ptr[Byte]  = extern
  def mlt_properties_get_data_at(self: mlt_properties, index: CInt, size: Ptr[CInt]): Ptr[Byte]   = extern

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

  /** Tell a service which profile to render against. Needed by the types MLT constructs without one
    * — `mlt_tractor_new` takes no profile, so its service must be told afterwards. */
  def mlt_service_set_profile(self: mlt_service, profile: mlt_profile): Unit = extern

  /** Attach a filter, so that every frame this service produces passes through it. A service may
    * have any number, applied in order. Returns 0 on success. */
  def mlt_service_attach(self: mlt_service, filter: mlt_filter): CInt = extern

  /** Remove a previously attached filter. Returns 0 on success. */
  def mlt_service_detach(self: mlt_service, filter: mlt_filter): CInt = extern

  def mlt_service_filter_count(self: mlt_service): CInt = extern

  /** The attached filter at `index`. The pointer is borrowed — owned by the service, not the
    * caller. */
  def mlt_service_filter(self: mlt_service, index: CInt): mlt_filter = extern

  /** Reorder the attached filters, which reorders how they are applied. Returns 0 on success. */
  def mlt_service_move_filter(self: mlt_service, from: CInt, to: CInt): CInt = extern

  /** What subclass a service reports itself as — one of the `mlt_service_*_type` values. This reads
    * the `mlt_type` and `resource` properties rather than inspecting the object, so it reports a
    * label and not a type: anything that overwrites `resource` changes the answer. */
  def mlt_service_identify(self: mlt_service): CInt = extern

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

  // -- Consumer --------------------------------------------------------------------------------

  /** Construct a consumer from a named module — "sdl2" to render to a window, "avformat" to encode
    * to a file, "null" to pull frames and discard them. `resource` is module-specific (an output
    * filename for "avformat") and may be null. Returns null if no module can handle it. */
  def mlt_factory_consumer(profile: mlt_profile, service: CString, resource: CString): mlt_consumer = extern

  /** Construct a bare consumer with no module behind it. It has no output of its own, which is
    * exactly what an application wanting the frames for itself needs: connect a producer, then pull
    * with [[mlt_consumer_rt_frame]]. */
  def mlt_consumer_new(profile: mlt_profile): mlt_consumer = extern

  /** Attach `producer` (or any service) as the consumer's input. Returns 0 on success. */
  def mlt_consumer_connect(self: mlt_consumer, producer: mlt_service): CInt = extern

  /** Begin consuming. With the `real_time` property non-zero this spawns MLT's own render threads,
    * which decode ahead into a buffer. Returns non-zero on error. */
  def mlt_consumer_start(self: mlt_consumer): CInt = extern

  /** Stop consuming, joining any render threads. Returns non-zero on error. */
  def mlt_consumer_stop(self: mlt_consumer): CInt = extern

  def mlt_consumer_is_stopped(self: mlt_consumer): CInt = extern

  /** Discard buffered frames — what a seek during playback needs, so that stale frames decoded ahead
    * of the jump are not shown after it. */
  def mlt_consumer_purge(self: mlt_consumer): Unit = extern

  /** Pull the next frame synchronously, rendering it on the calling thread. */
  def mlt_consumer_get_frame(self: mlt_consumer): mlt_frame = extern

  /** Pull the next frame with the consumer's real-time policy applied: when `real_time` is non-zero
    * this takes an already-decoded frame from the read-ahead buffer (and may drop frames to keep
    * pace); when it is zero this is [[mlt_consumer_get_frame]]. Returns null at end of stream.
    *
    * The returned frame belongs to the caller and must be closed. */
  def mlt_consumer_rt_frame(self: mlt_consumer): mlt_frame = extern

  def mlt_consumer_position(self: mlt_consumer): mlt_position = extern

  /** Release a consumer, stopping it first if it is still running. */
  def mlt_consumer_close(self: mlt_consumer): Unit = extern

  // -- Playlist --------------------------------------------------------------------------------
  //
  // A playlist is a sequence of clips and blanks that is itself a producer — which is what makes a
  // timeline track composable: it plays as one continuous piece of video.

  /** Construct an empty playlist rendering against `profile`. */
  def mlt_playlist_new(profile: mlt_profile): mlt_playlist = extern

  /** Append a producer's whole in..out region as the next clip. Returns 0 on success. */
  def mlt_playlist_append(self: mlt_playlist, producer: mlt_producer): CInt = extern

  /** Append only `in..out` of a producer — the trimmed cut, without disturbing the producer's own
    * in/out points, so one producer can appear as several different cuts. Returns 0 on success. */
  def mlt_playlist_append_io(
      self: mlt_playlist,
      producer: mlt_producer,
      in: mlt_position,
      out: mlt_position,
  ): CInt = extern

  /** Append `out + 1` frames of nothing — how a gap between clips is expressed. */
  def mlt_playlist_blank(self: mlt_playlist, out: mlt_position): CInt = extern

  /** The number of entries, counting blanks. */
  def mlt_playlist_count(self: mlt_playlist): CInt = extern

  def mlt_playlist_clear(self: mlt_playlist): CInt = extern

  /** Insert `producer`'s `in..out` before entry `where`, shifting the rest later. */
  def mlt_playlist_insert(
      self: mlt_playlist,
      producer: mlt_producer,
      where: CInt,
      in: mlt_position,
      out: mlt_position,
  ): CInt = extern

  /** Remove entry `where`, closing the gap. Returns 0 on success. */
  def mlt_playlist_remove(self: mlt_playlist, where: CInt): CInt = extern

  /** Move an entry, shifting whatever lies between. Returns 0 on success. */
  def mlt_playlist_move(self: mlt_playlist, from: CInt, to: CInt): CInt = extern

  /** Retrim entry `clip` to a new in..out — the edit a timeline drag performs. */
  def mlt_playlist_resize_clip(self: mlt_playlist, clip: CInt, in: mlt_position, out: mlt_position): CInt = extern

  /** Cut entry `clip` in two at `position`, measured from the clip's own start. */
  def mlt_playlist_split(self: mlt_playlist, clip: CInt, position: mlt_position): CInt = extern

  /** Cut whatever entry lies under `position` (measured on the playlist), keeping the left part as
    * the earlier entry when `left` is non-zero. */
  def mlt_playlist_split_at(self: mlt_playlist, position: mlt_position, left: CInt): CInt = extern

  /** Rejoin `count` entries starting at `clip` back into one. */
  def mlt_playlist_join(self: mlt_playlist, clip: CInt, count: CInt, merge: CInt): CInt = extern

  /** Overlap the end of `clip` with the start of the next by `length` frames, rendering the overlap
    * through `transition` — a cross-fade. Pass null to mix without one. */
  def mlt_playlist_mix(self: mlt_playlist, clip: CInt, length: CInt, transition: mlt_transition): CInt = extern

  /** Fill `info` in for entry `index`. Returns 0 on success. */
  def mlt_playlist_get_clip_info(self: mlt_playlist, info: Ptr[mlt_playlist_clip_info], index: CInt): CInt = extern

  /** The producer of entry `clip`. Borrowed — owned by the playlist. */
  def mlt_playlist_get_clip(self: mlt_playlist, clip: CInt): mlt_producer = extern

  /** The producer playing at `position` on the playlist. Borrowed. */
  def mlt_playlist_get_clip_at(self: mlt_playlist, position: mlt_position): mlt_producer = extern

  /** The index of the entry at `position` on the playlist. */
  def mlt_playlist_get_clip_index_at(self: mlt_playlist, position: mlt_position): CInt = extern

  /** Where entry `clip` begins, as a frame number on the playlist. */
  def mlt_playlist_clip_start(self: mlt_playlist, clip: CInt): CInt = extern

  /** How many frames entry `clip` occupies. */
  def mlt_playlist_clip_length(self: mlt_playlist, clip: CInt): CInt = extern

  /** Whether entry `clip` is a blank rather than a clip. */
  def mlt_playlist_is_blank(self: mlt_playlist, clip: CInt): CInt = extern

  def mlt_playlist_is_blank_at(self: mlt_playlist, position: mlt_position): CInt = extern

  /** Fold runs of adjacent blanks into one, optionally preserving the playlist's total length. */
  def mlt_playlist_consolidate_blanks(self: mlt_playlist, keep_length: CInt): Unit = extern

  /** Cut `length` frames out of the playlist at `position`, closing the gap. */
  def mlt_playlist_remove_region(self: mlt_playlist, position: mlt_position, length: CInt): CInt = extern

  /** Release a playlist. As with every type here, this is the destructor to use — it performs the
    * playlist's own teardown before giving back its reference. */
  def mlt_playlist_close(self: mlt_playlist): Unit = extern

  // -- Filter ----------------------------------------------------------------------------------

  /** Construct a filter from a named module ("greyscale", "brightness", ...). `arg` is
    * module-specific and may be null. Returns null if no module can handle it. */
  def mlt_factory_filter(profile: mlt_profile, service: CString, arg: CString): mlt_filter = extern

  /** Limit a filter to the frames `in..out` of whatever it is attached to. A filter with no in/out
    * set applies throughout. */
  def mlt_filter_set_in_and_out(self: mlt_filter, in: mlt_position, out: mlt_position): Unit = extern

  def mlt_filter_get_in(self: mlt_filter): mlt_position     = extern
  def mlt_filter_get_out(self: mlt_filter): mlt_position    = extern
  def mlt_filter_get_length(self: mlt_filter): mlt_position = extern

  /** Which track of a multitrack this filter was planted on. */
  def mlt_filter_get_track(self: mlt_filter): CInt = extern

  def mlt_filter_close(self: mlt_filter): Unit = extern

  // -- Transition ------------------------------------------------------------------------------

  /** Construct a transition from a named module ("luma" to wipe, "mix" for audio, "composite" or
    * "frei0r.cairoblend" to overlay). Returns null if no module can handle it. */
  def mlt_factory_transition(profile: mlt_profile, service: CString, arg: CString): mlt_transition = extern

  /** The frames over which the transition runs. */
  def mlt_transition_set_in_and_out(self: mlt_transition, in: mlt_position, out: mlt_position): Unit = extern

  /** Which two tracks of a multitrack the transition combines — `a_track` is the one underneath. */
  def mlt_transition_set_tracks(self: mlt_transition, a_track: CInt, b_track: CInt): Unit = extern

  def mlt_transition_get_a_track(self: mlt_transition): CInt      = extern
  def mlt_transition_get_b_track(self: mlt_transition): CInt      = extern
  def mlt_transition_get_in(self: mlt_transition): mlt_position   = extern
  def mlt_transition_get_out(self: mlt_transition): mlt_position  = extern
  def mlt_transition_get_length(self: mlt_transition): mlt_position = extern

  def mlt_transition_close(self: mlt_transition): Unit = extern

  // -- Tractor, multitrack, field --------------------------------------------------------------
  //
  // The tractor is the multitrack timeline: it holds parallel tracks (a multitrack) and the filters
  // and transitions that combine them (a field), and is itself a producer.

  /** Construct an empty tractor. Note it takes no profile — unlike every other constructor here —
    * so the caller must set one on its service afterwards with [[mlt_service_set_profile]]. */
  def mlt_tractor_new(): mlt_tractor = extern

  /** Put `producer` on track `index`, replacing whatever was there. Returns 0 on success. */
  def mlt_tractor_set_track(self: mlt_tractor, producer: mlt_producer, index: CInt): CInt = extern

  /** Put `producer` on track `index`, shifting the tracks above it up. Returns 0 on success. */
  def mlt_tractor_insert_track(self: mlt_tractor, producer: mlt_producer, index: CInt): CInt = extern

  def mlt_tractor_remove_track(self: mlt_tractor, index: CInt): CInt = extern

  /** The producer on track `index`. Borrowed — owned by the tractor's multitrack. */
  def mlt_tractor_get_track(self: mlt_tractor, index: CInt): mlt_producer = extern

  /** The tractor's multitrack. Borrowed; the tractor closes it. */
  def mlt_tractor_multitrack(self: mlt_tractor): mlt_multitrack = extern

  /** The tractor's field — where its filters and transitions are planted. Borrowed; the tractor
    * closes it. */
  def mlt_tractor_field(self: mlt_tractor): mlt_field = extern

  /** Recompute the tractor's length from its tracks. Needed after changing what is on one. */
  def mlt_tractor_refresh(self: mlt_tractor): Unit = extern

  def mlt_tractor_close(self: mlt_tractor): Unit = extern

  /** How many tracks a multitrack holds. */
  def mlt_multitrack_count(self: mlt_multitrack): CInt = extern

  /** Plant a filter on `track` of the field's multitrack, so it applies to that track's frames as
    * they are combined. Returns 0 on success. */
  def mlt_field_plant_filter(self: mlt_field, that: mlt_filter, track: CInt): CInt = extern

  /** Plant a transition combining `a_track` (underneath) with `b_track` (on top). Returns 0 on
    * success. */
  def mlt_field_plant_transition(self: mlt_field, that: mlt_transition, a_track: CInt, b_track: CInt): CInt = extern

  // -- Repository ------------------------------------------------------------------------------
  //
  // What modules are installed, and what they claim to do — the backing for an effects browser.

  /** The repository loaded by [[mlt_factory_init]]. Borrowed; the factory closes it. */
  def mlt_factory_repository(): mlt_repository = extern

  // Each returns a property bag whose *names* are the available service names.
  def mlt_repository_producers(self: mlt_repository): mlt_properties   = extern
  def mlt_repository_filters(self: mlt_repository): mlt_properties     = extern
  def mlt_repository_transitions(self: mlt_repository): mlt_properties = extern
  def mlt_repository_consumers(self: mlt_repository): mlt_properties   = extern

  /** A service's self-description — title, description, parameters and their ranges. `type` is one
    * of the `mlt_service_*_type` values. Returns null if the module supplies no metadata. */
  def mlt_repository_metadata(self: mlt_repository, `type`: CInt, service: CString): mlt_properties = extern

  // -- Frame -----------------------------------------------------------------------------------

  /** Render this frame's image. `format` is in/out: pass the desired `mlt_image_format` and MLT
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

  /** Render this frame's audio and hand back a pointer to the samples, owned by the frame and valid
    * until it is closed. `format` is in/out like [[mlt_frame_get_image]]: pass the desired
    * `mlt_audio_format` and MLT converts, writing back what it produced. `frequency` receives the
    * sample rate in Hz, `channels` the channel count, and `samples` the number of samples per
    * channel (so an interleaved buffer holds `samples * channels` values). Returns 0 on success. */
  def mlt_frame_get_audio(
      self: mlt_frame,
      buffer: Ptr[Ptr[Byte]],
      format: Ptr[CInt],
      frequency: Ptr[CInt],
      channels: Ptr[CInt],
      samples: Ptr[CInt],
  ): CInt = extern

  /** The number of audio samples that belong to the frame at `position`, for a graph running at
    * `fps` with audio at `frequency` Hz. Because the sample rate rarely divides evenly by the frame
    * rate, this varies frame to frame (1601, then 1601, then 1602, …); it accumulates against the
    * position so the counts stay exact over time. [[mlt_frame_get_audio]] must be asked for this
    * many samples — a request of 0 yields silence. */
  def mlt_audio_calculate_frame_samples(fps: CFloat, frequency: CInt, position: CLongLong): CInt = extern

  // -- Image formats ---------------------------------------------------------------------------

  /** The name of an `mlt_image_format` — "rgba", "yuv422", and so on. This is the spelling MLT's
    * own string properties use, so it is what a format must be converted to before being set as
    * one. Returns a static string; do not free it. */
  def mlt_image_format_name(format: CInt): CString = extern

  /** The `mlt_image_format` a name denotes — the inverse of [[mlt_image_format_name]]. */
  def mlt_image_format_id(name: CString): CInt = extern

  /** `mlt_image_s` — a described image: its layout, its size, and where each plane starts.
    *
    * Bound as a struct rather than an opaque pointer because reading `planes` and `strides` is the
    * entire reason to hold one: they are what a planar format's picture is addressed through, and
    * MLT publishes them as fields rather than through accessors. The trailing four members are
    * function pointers to destructors, which this binding never sets and never calls, so they are
    * modelled as plain pointers.
    *
    * Field order: format, width, height, colorspace, planes[4], strides[4], data, release_data,
    * alpha, release_alpha, close. */
  type mlt_image_s = CStruct11[
    CInt,                      // format — an mlt_image_format
    CInt,                      // width
    CInt,                      // height
    CInt,                      // colorspace
    CArray[Ptr[Byte], Nat._4], // planes — first byte of each; unused planes are null
    CArray[CInt, Nat._4],      // strides — bytes per row of each plane
    Ptr[Byte],                 // data — the whole buffer the planes point into
    Ptr[Byte],                 // release_data — destructor, or null when the data is borrowed
    Ptr[Byte],                 // alpha
    Ptr[Byte],                 // release_alpha
    Ptr[Byte],                 // close
  ]

  type mlt_image = Ptr[mlt_image_s]

  /** Allocate an empty image description. It owns no pixels until told about some. */
  def mlt_image_new(): mlt_image = extern

  /** Describe `data` as an image of `format` at `width` x `height`, filling in `planes` and
    * `strides` for that layout.
    *
    * **It borrows the buffer**: it stores the pointer and sets `release_data` to null, so closing
    * the image will not free the pixels. That is what makes it safe to point at a frame's own
    * buffer, which the frame owns and frees. It does not consult the previous contents of the
    * struct, so it is equally safe on a freshly allocated one. */
  def mlt_image_set_values(
      self: mlt_image,
      data: Ptr[Byte],
      format: CInt,
      width: CInt,
      height: CInt,
  ): Unit = extern

  /** Release an image description. Frees the pixels only if the image owns them, which one filled
    * in by [[mlt_image_set_values]] does not. */
  def mlt_image_close(self: mlt_image): Unit = extern

  /** The name of an `mlt_colorspace` — "bt709", "bt601", and so on. Returns a static string. */
  def mlt_image_colorspace_name(colorspace: CInt): CString = extern

  /** Release a frame obtained from [[mlt_service_get_frame]]. */
  def mlt_frame_close(self: mlt_frame): Unit = extern
