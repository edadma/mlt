import io.github.edadma.mlt.*

// Proves the binding links and that MLT can find its module repository — the two things that have
// to work before anything else can be built on top.
@main def run(): Unit =
  println(s"MLT ${Mlt.version} (int ${Mlt.versionInt})")

  Mlt.init()
  println(s"modules: ${Mlt.directory}")

  Mlt.close()
