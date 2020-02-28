package Files

import cats.effect.{ IO, Resource }
import java.io.{ File, FileInputStream, FileOutputStream, InputStream, OutputStream }

object Resources {
  def inputStream(f: File): Resource[IO, FileInputStream] =
    Resource.make {
      IO(new FileInputStream(f))
    } { inStream =>
      IO(inStream.close()).handleErrorWith(_ => IO.unit)
    }

  // def inputStream(f: File): Resource[IO, FileInputStream] =
  //   Resource.fromAutoCloseable(IO(new FileInputStream(f)))

  def outputStream(f: File): Resource[IO, FileOutputStream] =
    Resource.make {
      IO(new FileOutputStream(f))
    } { outStream =>
      IO(outStream.close()).handleErrorWith(_ => IO.unit)
    }

  def inputOutputStream(in: File, out: File): Resource[IO, (InputStream, OutputStream)] =
    for {
      inStream <- inputStream(in)
      outStream <- outputStream(out)
    } yield (inStream, outStream)
}

object Copy {
  import Resources._

  def transfer(origin: InputStream, destination: OutputStream): IO[Long] = ???

  def copy(origin: File, destination: File): IO[Long] =
    inputOutputStream(origin, destination).use { case (in, out) =>
      transfer(in, out)
    }

  // def copy(origin: File, destination: File): IO[Long] = {
  //   import cats.syntax.apply._
  //   import cats.syntax.functor._

  //   val inIO: IO[InputStream] = IO(new FileInputStream(origin))
  //   val outIO: IO[OutputStream] = IO(new FileOutputStream(destination))

  //   (inIO, outIO)
  //     .tupled
  //     .bracket {
  //       case (in, out) =>
  //         transfer(in, out)
  //     } {
  //       case (in, out) =>
  //         (IO(in.close()), IO(out.close()))
  //           .tupled
  //           .handleErrorWith(_ => IO.unit).void
  //     }
  // }
}
