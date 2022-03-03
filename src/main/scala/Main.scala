import de.netherspace.apps.dscvry.CddbdBootstrap
import zio.Clock
import zio.ZLayer
import zio.URIO
import zio.ZEnv
import zio.ExitCode
import zio.URLayer
import zio.UIO


object Main extends zio.App {

  // run the app:
  override def run(args: List[String]): URIO[ZEnv, ExitCode] =
    CddbdBootstrap
      .appLogic
      .exitCode
}
