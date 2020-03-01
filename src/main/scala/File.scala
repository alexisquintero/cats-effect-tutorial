package Files

import cats.effect.{ IO, Resource, Concurrent, IOApp, ExitCode }
import java.io.{ File, FileInputStream, FileOutputStream, InputStream, OutputStream }
import cats.effect.concurrent.Semaphore

object Resources {
  def inputStream(f: File, guard: Semaphore[IO]): Resource[IO, FileInputStream] =
    Resource.make {
      IO(new FileInputStream(f))
    } { inStream =>
      guard.withPermit {
        IO(inStream.close()).handleErrorWith(_ => IO.unit)
      }
    }

  // def inputStream(f: File): Resource[IO, FileInputStream] =
  //   Resource.fromAutoCloseable(IO(new FileInputStream(f)))

  def outputStream(f: File, guard: Semaphore[IO]): Resource[IO, FileOutputStream] =
    Resource.make {
      IO(new FileOutputStream(f))
    } { outStream =>
      guard.withPermit {
        IO(outStream.close()).handleErrorWith(_ => IO.unit)
      }
    }

  def inputOutputStream(in: File, out: File, guard: Semaphore[IO]): Resource[IO, (InputStream, OutputStream)] =
    for {
      inStream <- inputStream(in, guard)
      outStream <- outputStream(out, guard)
    } yield (inStream, outStream)
}

object Copy {
  import Resources._
  import cats.syntax.flatMap._

  def transmit(origin: InputStream, destination: OutputStream, buffer: Array[Byte], acc: Long): IO[Long] =
    for {
      amount <- IO(origin.read(buffer, 0, buffer.size))
      count <- if(amount > -1) IO(destination.write(buffer, 0, amount)) >> transmit(origin, destination, buffer, acc + amount)
        else IO.pure(acc)
    } yield count

  def transfer(origin: InputStream, destination: OutputStream): IO[Long] =
    for {
      buffer <- IO(new Array[Byte](1024 * 10))
      total <- transmit(origin, destination, buffer, 0L)
    } yield total

  def copy(origin: File, destination: File)(implicit concurrent: Concurrent[IO]): IO[Long] =
    for {
      guard <- Semaphore[IO](1)
      count <- inputOutputStream(origin, destination, guard).use { case (in, out) =>
        guard.withPermit(transfer(in, out))
      }
    } yield count

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

object Main extends IOApp {
  import Copy.copy

  override def run(args: List[String]): IO[ExitCode] =
    for {
      _ <- if(args.length < 2) IO.raiseError(new IllegalArgumentException("Need origin and destination files"))
        else IO.unit
      orig = new File(args(0))
      dest = new File(args(1))
      count <- copy(orig, dest)
      _ <- IO(println(s"$count bytes copied from ${orig.getPath} to ${dest.getPath}"))
    } yield ExitCode.Success
}
