import de.netherspace.apps.dscvry.CddbdBootstrap
import zio._
import zio.clock._
import zio.console._
import zio.logging._

object Main extends zio.App {

  // compose a logging service from Console + Clock:
  val logger: ZLayer[Console & Clock, Nothing, Logging] =
    Logging.console(
      logLevel = LogLevel.Info,
      format = LogFormat.ColoredLogFormat()
    ) >>> Logging.withRootLoggerName("dscvry")

  // run the app:
  override def run(args: List[String]): URIO[ZEnv, ExitCode] =
    CddbdBootstrap
      .appLogic
      .provideCustomLayer(logger)
      .exitCode
}
