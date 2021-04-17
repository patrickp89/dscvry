import de.netherspace.apps.dscvry.CddbdServer

import java.util.concurrent.Executors

object Main extends App {
  val ServerPort = 8880
  val executor = Executors.newCachedThreadPool()

  new CddbdServer().bootstrap(ServerPort).map(
    f => executor.submit(new Runnable {
      override def run(): Unit = f.apply()
    })
  )
}
