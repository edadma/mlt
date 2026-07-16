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
