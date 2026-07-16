import io.github.edadma.mlt.*

import java.io.FileOutputStream

// Exercises the binding end to end: the framework starts, a profile resolves, both a synthetic and a
// real (H.264) producer render, and a frame's pixels come back in the layout we asked for.
@main def run(): Unit =
  println(s"MLT ${Mlt.version} (int ${Mlt.versionInt})")

  Mlt.init()
  println(s"modules: ${Mlt.directory}")

  val profile = Profile("atsc_720p_30")
  println(s"profile: $profile")

  synthetic(profile)
  mediaFile(profile)
  playback(profile)
  exportFile(profile)
  timeline(profile)
  effects(profile)
  multitrack(profile)
  modules()
  swizzle()

  profile.close()
  Mlt.close()

// A generated colour needs no media file, so it isolates the frame/image path from the decoder: if
// this produces red pixels, the graph and the format request are working.
def synthetic(profile: Profile): Unit =
  val producer = Producer(profile, "red", Some("color"))
  val frame    = producer.frame()
  val img      = frame.image(ImageFormat.Rgba)

  println(s"\ncolor:red -> ${img.width}x${img.height} ${img.format.value} (${img.pixels.length} bytes)")
  println(s"  first pixel RGBA: ${img.pixels.take(4).map(_ & 0xff).mkString(", ")}")

  frame.close()
  producer.close()

// The real path: decode an H.264 file through the avformat module, seek into it, and render a frame.
def mediaFile(profile: Profile): Unit =
  val producer = Producer(profile, "demo.mp4")

  println(s"\ndemo.mp4 -> ${producer.length} frames @ ${producer.fps}fps, in=${producer.in} out=${producer.out}")

  val middle = producer.length / 2

  producer.seek(middle)

  val frame = producer.frame()
  val img   = frame.image(ImageFormat.Rgba)

  println(s"  frame $middle: ${img.width}x${img.height}, format ${img.format.value}, ${img.pixels.length} bytes")

  // Sample the centre, not a corner: a 4:3 source rendered against a 16:9 profile is pillarboxed, and
  // the padding is transparent black — which says nothing about whether the picture decoded.
  println(s"  centre pixel RGBA: ${pixel(img, img.width / 2, img.height / 2).mkString(", ")}")
  println(s"  opaque pixels: ${percentOpaque(img)}%")

  writePpm("mlt-demo.ppm", img)
  println(s"  wrote mlt-demo.ppm")

  frame.close()
  producer.close()

// The preview path: a bare consumer pulls the clip through frame by frame, the way a video pane would
// drive it from a thread of its own. Nothing here paces the loop, so it runs flat out — real playback
// would rate-limit to the profile's fps.
def playback(profile: Profile): Unit =
  val producer = Producer(profile, "demo.mp4")
  val consumer = Consumer.bare(profile)

  consumer.connect(producer)
  consumer.start()

  val started   = System.nanoTime()
  var positions = List.empty[Int]
  var running   = true

  while running do
    val frame = consumer.rtFrame().getOrElse(throw new MltException("consumer produced nothing"))

    // Pulling a frame does not decode it — the image is rendered on demand, so this is where a
    // preview's real per-frame cost lives, and a loop that skipped it would be timing nothing.
    val (_, w, h, fmt) = frame.imagePtr(ImageFormat.Rgba)

    if positions.isEmpty then println(s"\npreview: pulling ${w}x$h ${fmt.name}")

    // Zero speed means the producer has run out and this frame is a repeat of the last real one, so
    // it is the end of the loop and not a frame to show. Nothing else marks the end: the pull would
    // go on succeeding forever.
    running = frame.speed != 0

    if running then positions = frame.position :: positions

    frame.close()

  val elapsed = (System.nanoTime() - started) / 1e6
  val seen    = positions.reverse

  println(s"  showed ${seen.length} frames in ${elapsed.toInt}ms (${(seen.length / (elapsed / 1000)).toInt} fps)")
  println(s"  positions ${seen.head}..${seen.last}, in order: ${seen == seen.sorted}, no repeats: ${seen.distinct == seen}")
  println(s"  clip is ${producer.length} frames, out=${producer.out}")

  consumer.stop()
  consumer.close()
  producer.close()

// The other kind of consumer: one that drives itself. An export is not a pull loop — connect it,
// start it, and wait for it to run out of input.
def exportFile(profile: Profile): Unit =
  val path     = "mlt-export.mp4"
  val producer = Producer(profile, "demo.mp4")
  val consumer = Consumer(profile, "avformat", Some(path))

  // Without this it would sit on the last frame forever rather than finishing.
  consumer.terminateOnPause = true
  consumer.realTime = 0 // encode every frame; never drop one to keep up with a clock

  consumer.connect(producer)
  consumer.start()

  val started = System.nanoTime()

  while !consumer.isStopped do Thread.sleep(5)

  println(f"\nexport: wrote $path in ${(System.nanoTime() - started) / 1e6}%.0fms")

  consumer.stop()
  consumer.close()
  producer.close()

// A playlist is one track of a timeline: cuts laid end to end, with gaps. It is also a producer, so
// what this builds could be handed straight to the consumer loop above.
def timeline(profile: Profile): Unit =
  val source   = Producer(profile, "demo.mp4")
  val playlist = Playlist(profile)

  // Two cuts of the same file with a gap between them. The cuts do not disturb the source's own
  // in/out, which is what lets one open file appear on a track any number of times.
  playlist.append(source, 0, 29)
  playlist.blank(10)
  playlist.append(source, 45, 59)

  println(s"\ntimeline: ${playlist.count} entries, ${playlist.length} frames")

  for c <- playlist.clips do
    val what = if c.blank then "(blank)" else s"${c.resource.getOrElse("?")} ${c.in}..${c.out}"

    println(f"  [${c.index}] start ${c.start}%3d  len ${c.length}%3d  $what")

  // The razor: cut the first clip in two, and the entry count grows without the length changing.
  val before = playlist.length

  playlist.split(0, 10)

  println(s"  split clip 0 at 10 -> ${playlist.count} entries, ${playlist.length} frames (was $before)")

  // Ripple delete: take frames out and everything after moves earlier.
  playlist.removeRegion(0, 5)

  println(s"  removed 5 frames at 0 -> ${playlist.length} frames")
  println(s"  frame 35 is in entry ${playlist.clipIndexAt(35)}, blank there: ${playlist.isBlankAt(35)}")

  // It renders like anything else — the point of a playlist being a producer.
  val frame = playlist.frame()
  val img   = frame.image(ImageFormat.Rgba)

  println(s"  renders: ${img.width}x${img.height}")

  frame.close()
  playlist.close()
  source.close()

// A filter modifies one producer's frames. Rendering through one and reading the pixels back is the
// only way to know it actually ran.
def effects(profile: Profile): Unit =
  // "color:red" rather than the "color" service by name: greyscale renders in yuv422, and only a
  // producer built through the loader carries the filters that convert the frame back to what we ask
  // for. Name the service and this reads back as yuv422 instead.
  val producer = Producer(profile, "color:red")
  val filter   = Filter(profile, "greyscale")

  val before = pixel(producer.frame().image(ImageFormat.Rgba), 10, 10)

  producer.attach(filter)

  val after = pixel(producer.frame().image(ImageFormat.Rgba), 10, 10)

  println(s"\neffects: red pixel ${before.mkString(",")} -> greyscale ${after.mkString(",")}")
  println(s"  attached: ${producer.filterCount}, grey (R==G==B): ${after(0) == after(1) && after(1) == after(2)}")

  // Disabling leaves it in the graph but stops it working — an effect's on/off switch, which is not
  // the same edit as removing it.
  filter.disabled = true

  val off = pixel(producer.frame().image(ImageFormat.Rgba), 10, 10)

  println(s"  disabled -> ${off.mkString(",")}, still attached: ${producer.filterCount}")

  producer.detach(filter)

  println(s"  detached -> ${producer.filterCount}")

  filter.close()
  producer.close()

// The multitrack timeline: parallel tracks, and the transitions that combine them into one picture.
def multitrack(profile: Profile): Unit =
  println("\nmultitrack: a red track and a green one, 5 frames, read at two corners")

  // Each case gets a graph of its own. A producer played to its end stays there, and seeking a
  // tractor does not rewind the producers on its tracks — so reusing one would compare a fresh graph
  // against a spent one.
  def build(label: String)(plant: (Tractor, Profile) => Option[Transition]): Unit =
    val bottom  = Producer(profile, "color:red")
    val top     = Producer(profile, "color:green")
    val tractor = Tractor(profile)

    bottom.setInAndOut(0, 4)
    top.setInAndOut(0, 4)

    tractor.setTrack(bottom, 0)
    tractor.setTrack(top, 1)
    tractor.refresh()

    val transition = plant(tractor, profile)

    println(f"  $label%-34s ${sweep(profile, tractor)}")

    transition.foreach(_.close())
    tractor.close()
    top.close()
    bottom.close()

  // Nothing says how to combine the tracks, so nothing is combined: what comes out is the top track,
  // and the red one underneath may as well not be there.
  build("no transition:")((_, _) => scala.None)

  // Composite insets the B track into the A track, at the rectangle its geometry names — the green
  // track covering the top-left quadrant of the red one.
  build("composite, inset top-left:") { (tractor, profile) =>
    Some(composite(profile, tractor, "0/0:50%x50%:100"))
  }

  // Two keyframes make that rectangle a path, and the inset travels it as the frames advance: it
  // starts in the top-left corner, crosses the middle where neither corner sees it, and lands in the
  // bottom-right.
  build("composite, inset moving:") { (tractor, profile) =>
    Some(composite(profile, tractor, "0=0/0:50%x50%:100; 4=50%/50%:50%x50%:100"))
  }

def composite(profile: Profile, tractor: Tractor, geometry: String): Transition =
  val t = Transition(profile, "composite")

  t.setInAndOut(0, 4)
  t.set("geometry", geometry)
  tractor.plantTransition(t, 0, 1)
  t

// Pull a producer through and report what two opposite corners show on each frame. A tractor's
// position has to advance through the graph for its transitions to know where they are, so this is
// not the same as seeking and rendering frames by hand.
def sweep(profile: Profile, producer: Producer): String =
  val consumer = Consumer.bare(profile)

  producer.seek(0)
  consumer.connect(producer)
  consumer.start()

  var out     = List.empty[String]
  var running = true

  while running do
    val frame = consumer.rtFrame().get
    val img   = frame.image(ImageFormat.Rgba)

    running = frame.speed != 0

    if running then out = s"${colour(pixel(img, 40, 40))}/${colour(pixel(img, 1240, 680))}" :: out

    frame.close()

  consumer.stop()
  consumer.close()
  out.reverse.mkString("  ")

// The two tracks are pure colours, so naming them says more than the numbers do — and anything that
// is neither is worth seeing as numbers.
def colour(px: Seq[Int]): String =
  if px(0) > 200 && px(1) < 60 then "red"
  else if px(1) > 200 && px(0) < 60 then "green"
  else px.take(3).mkString(",")

// What is installed, and what it says about itself — the backing for an effects browser, which
// otherwise requires the user to already know the module names.
def modules(): Unit =
  println(s"\nmodules: ${Mlt.producers.length} producers, ${Mlt.filters.length} filters, " +
    s"${Mlt.transitions.length} transitions, ${Mlt.consumers.length} consumers")

  val described = Mlt.filters.flatMap(f => Mlt.metadata(ServiceKind.Filter, f))

  println(s"  ${described.length} of ${Mlt.filters.length} filters describe themselves:")

  for m <- described.take(3) do println(s"    ${m.name}: ${m.title.getOrElse("-")} — ${m.description.getOrElse("-")}")

// The swizzle is what every frame must pass through to reach a Cairo surface, so prove the channel
// exchange lands where Cairo expects it.
def swizzle(): Unit =
  import scala.scalanative.unsafe.*

  Zone {
    val buf = alloc[Byte](4)

    buf(0) = 0x11.toByte // R
    buf(1) = 0x22.toByte // G
    buf(2) = 0x33.toByte // B
    buf(3) = 0x44.toByte // A

    rgbaToArgb32(buf, 1)

    val got = (0 until 4).map(i => f"${buf(i) & 0xff}%02x").mkString(",")

    println(s"\nswizzle: RGBA 11,22,33,44 -> $got (Cairo ARGB32 wants 33,22,11,44)")
  }

def pixel(img: Image, x: Int, y: Int): Seq[Int] =
  val o = (y * img.width + x) * 4

  (0 until 4).map(i => img.pixels(o + i) & 0xff)

// Cairo's ARGB32 is premultiplied, so alpha decides whether a frame is visible at all once it reaches
// a surface. Worth knowing what MLT actually hands back for a video with no alpha channel of its own.
def percentOpaque(img: Image): Int =
  var opaque = 0
  var i      = 3

  while i < img.pixels.length do
    if (img.pixels(i) & 0xff) == 255 then opaque += 1
    i += 4

  opaque * 100 / (img.width * img.height)

// A binary PPM (P6) is a dependency-free way to prove the decode: any image viewer will open it.
def writePpm(path: String, img: Image): Unit =
  val rgb = new Array[Byte](img.width * img.height * 3)
  var i   = 0

  while i < img.width * img.height do
    rgb(i * 3) = img.pixels(i * 4)
    rgb(i * 3 + 1) = img.pixels(i * 4 + 1)
    rgb(i * 3 + 2) = img.pixels(i * 4 + 2)
    i += 1

  val out = new FileOutputStream(path)

  out.write(s"P6\n${img.width} ${img.height}\n255\n".getBytes("US-ASCII"))
  out.write(rgb)
  out.close()
