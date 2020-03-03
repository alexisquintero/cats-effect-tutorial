package TCP

import cats.effect.{ Sync, Resource, Concurrent, IOApp, ExitCode, IO, ExitCase, ContextShift }
import cats.effect.concurrent.MVar
import ExitCase.{ Completed, Canceled, Error }

import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.applicativeError._

import scala.concurrent.{ ExecutionContext, ExecutionContextExecutor }

import java.net.{ Socket, ServerSocket }
import java.io.{ BufferedReader, BufferedWriter, InputStreamReader, PrintWriter }
import java.util.concurrent.{ Executors, ExecutorService }

object TCP {
  def echoProtocol[F[_]: Sync: ContextShift](clientSocket: Socket, stopFlag: MVar[F, Unit])
    (implicit clientsExecutionContext: ExecutionContext): F[Unit] = {

    def loop(reader: BufferedReader, writer: BufferedWriter, stopFlag: MVar[F, Unit]): F[Unit] =
      for {
        lineE <- ContextShift[F]
                  .evalOn(clientsExecutionContext)(Sync[F].delay(reader.readLine).attempt)
        _    <- lineE match {
                  case Right(line) => line match {
                    case "STOP" => stopFlag.put(())
                    case "" => Sync[F].unit
                    case _ => Sync[F].delay {
                      writer.write(line)
                      writer.newLine
                      writer.flush
                    } >> loop(reader, writer, stopFlag)
                  }
                  case Left(e) =>
                    for {
                      isEmpty <- stopFlag.isEmpty
                      _ <- if(!isEmpty) Sync[F].unit
                           else Sync[F].raiseError(e)
                    } yield ()
                }
      } yield ()

    def reader(clientSocket: Socket): Resource[F, BufferedReader] =
      Resource.make {
        Sync[F].delay(
          new BufferedReader(
            new InputStreamReader(
              clientSocket.getInputStream)))
      } { reader =>
        Sync[F].delay(reader.close).handleErrorWith(_ => Sync[F].unit)
      }

    def writer(clientSocket: Socket): Resource[F, BufferedWriter] =
      Resource.make {
        Sync[F].delay(
          new BufferedWriter(
            new PrintWriter(
              clientSocket.getOutputStream)))
      } { reader =>
        Sync[F].delay(reader.close).handleErrorWith(_ => Sync[F].unit)
      }

    def readerWriter(clientSocket: Socket): Resource[F, (BufferedReader, BufferedWriter)] =
      for {
        reader <- reader(clientSocket)
        writer <- writer(clientSocket)
      } yield (reader, writer)

    readerWriter(clientSocket).use { case (reader, writer) =>
      loop(reader, writer, stopFlag)
    }
  }

  def serve[F[_]: Concurrent: ContextShift](
    serverSocket: ServerSocket,
    stopFlag: MVar[F, Unit]
  )(
    implicit clientsExecutionContext: ExecutionContext
  ): F[Unit] = {
    import cats.effect.syntax.all._

    def close(socket: Socket): F[Unit] =
      Sync[F].delay(socket.close).handleErrorWith(_ => Sync[F].unit)

    for {
      fiber <- Sync[F]
                  .delay(serverSocket.accept)
                  .bracketCase { socket =>
                    echoProtocol(socket, stopFlag)
                      .guarantee(close(socket))
                      .start
                  } { (socket, exit) => exit match {
                    case Completed => Sync[F].unit
                    case Error(_) | Canceled => close(socket)
                  }}
      _ <- (stopFlag.read >> fiber.cancel)
              .start
      _ <- serve(serverSocket, stopFlag)
    } yield ()
  }


  def server[F[_]: Concurrent: ContextShift](serverSocket: ServerSocket): F[ExitCode] = {
    import cats.effect.syntax.all._

    val clientsThreadPool: ExecutorService = Executors.newCachedThreadPool
    implicit val clientsExecutionContext: ExecutionContextExecutor =
      ExecutionContext.fromExecutor(clientsThreadPool)

    for {
      stopFlag <- MVar[F].empty[Unit]
      serverFiber <- serve(serverSocket, stopFlag).start
      _ <- stopFlag.read
      _ <- Sync[F].delay(clientsThreadPool.shutdown)
      _ <- serverFiber.cancel.start
    } yield ExitCode.Success
  }
}

object Main extends IOApp {
  import TCP.server

  def run(args: List[String]): IO[ExitCode] = {
    def close[F[_]: Sync](socket: ServerSocket): F[Unit] =
      Sync[F].delay(socket.close).handleErrorWith(_ => Sync[F].unit)

    IO(new ServerSocket(args.headOption.map(_.toInt).getOrElse(5432)))
      .bracket {
        serverSocket => server[IO](serverSocket) >> IO.pure(ExitCode.Success)
      } {
        serverSocket => close[IO](serverSocket) >> IO(println("Server finished"))
      }
  }
}
