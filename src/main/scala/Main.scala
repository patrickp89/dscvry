import de.netherspace.apps.dscvry.CddbdBootstrap
import zio._
import zio.clock._
import zio.logging._
import zio.console._

object Main extends zio.App {

  // compose a logging service from Console + Clock:
  /* TODO: val logger: ZLayer[Console & Clock, Nothing, Logging] =
    Logging.console(
      logLevel = LogLevel.Info,
      format = LogFormat.ColoredLogFormat()
    ) >>> Logging.withRootLoggerName("my-component")*/

  override def run(args: List[String]): URIO[ZEnv, ExitCode] =
    CddbdBootstrap
      .appLogic
      // TODO: .provideLayer(logger)
      .exitCode
}
