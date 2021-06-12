import de.netherspace.apps.dscvry.CddbdServer
import zio.console._
import zio.{ExitCode, URIO, ZEnv}

object Main extends zio.App {
  val ServerPort = 8880

  override def run(args: List[String]) = //: URIO[ZEnv, ExitCode] ?
    appLogic.exitCode

  val appLogic =
    for {
      _ <- putStrLn("Captain Placeholder")
      //_ <- new CddbdServer().bootstrap3(ServerPort).useForever
    } yield ()
}
