package Files

import cats.effect.{ Resource, Concurrent, IOApp, ExitCode, Sync }
import java.io.{ File, FileInputStream, FileOutputStream, InputStream, OutputStream }
import cats.effect.concurrent.Semaphore
import cats.syntax.applicativeError._

object Resources {
  def inputStream[F[_]: Sync](f: File, guard: Semaphore[F]): Resource[F, FileInputStream] =
    Resource.make {
      Sync[F].delay(new FileInputStream(f))
    } { inStream =>
      guard.withPermit {
        Sync[F].delay(inStream.close()).handleErrorWith(_ => Sync[F].unit)
      }
    }

  // def inputStream(f: File): Resource[IO, FileInputStream] =
  //   Resource.fromAutoCloseable(IO(new FileInputStream(f)))

  def outputStream[F[_]: Sync](f: File, guard: Semaphore[F]): Resource[F, FileOutputStream] =
    Resource.make {
      Sync[F].delay(new FileOutputStream(f))
    } { outStream =>
      guard.withPermit {
        Sync[F].delay(outStream.close()).handleErrorWith(_ => Sync[F].unit)
      }
    }

  def inputOutputStream[F[_]: Sync](in: File, out: File, guard: Semaphore[F]): Resource[F, (InputStream, OutputStream)] =
    for {
      inStream <- inputStream(in, guard)
      outStream <- outputStream(out, guard)
    } yield (inStream, outStream)
}

object Copy {
  import Resources._
  import cats.syntax.flatMap._
  import cats.syntax.functor._

  def transmit[F[_]: Sync](origin: InputStream, destination: OutputStream, buffer: Array[Byte], acc: Long): F[Long] =
    for {
      amount <- Sync[F].pure(origin.read(buffer, 0, buffer.size))
      count <- if(amount > -1) Sync[F].pure(destination.write(buffer, 0, amount)) >> transmit(origin, destination, buffer, acc + amount)
        else Sync[F].pure(acc)
    } yield count

  def transfer[F[_]: Sync](origin: InputStream, destination: OutputStream): F[Long] =
    for {
      buffer <- Sync[F].delay(new Array[Byte](1024 * 10))
      total <- transmit(origin, destination, buffer, 0L)
    } yield total

  def checkFile[F[_]: Sync](file: File): F[Unit] = { // ???
    import java.nio.file.{ Paths, Files }
    import scala.io.StdIn

    for {
      exists <- Sync[F].delay(Files.exists(Paths.get(file.getPath)))
      outcome = if (exists) {
        for {
          _ <- Sync[F].delay(Console.println(s"File ${file.getName} already exists, overwrite?"))
          ans <- Sync[F].delay(StdIn.readLine)
        } yield if (ans != "Y") Sync[F].raiseError(new Error("Can't overwrite destination"))
        else Sync[F].unit
      } else Sync[F].unit
    } yield Sync[F].unit
  }

  def copy[F[_]: Concurrent](origin: File, destination: File): F[Long] =
    for {
      _ <- if (origin == destination) Sync[F].raiseError(new IllegalArgumentException("Origin and destination can't be the same")) else Sync[F].unit
      // _ <- checkFile(destination)
      guard <- Semaphore[F](1)
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
  import cats.effect.IO

  override def run(args: List[String]): IO[ExitCode] =
    for {
      _ <- if(args.length < 2) IO.raiseError(new IllegalArgumentException("Need origin and destination files"))
        else IO.unit
      orig = new File(args(0))
      dest = new File(args(1))
      count <- copy[IO](orig, dest)
      _ <- IO(println(s"$count bytes copied from ${orig.getPath} to ${dest.getPath}"))
    } yield ExitCode.Success
}
