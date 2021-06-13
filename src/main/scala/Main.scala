import de.netherspace.apps.dscvry.CddbdBootstrap
import zio.{ExitCode, URIO, ZEnv}

object Main extends zio.App {

  override def run(args: List[String]): URIO[ZEnv, ExitCode] =
    CddbdBootstrap.appLogic.exitCode
}
