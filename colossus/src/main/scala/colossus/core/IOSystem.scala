package colossus

import akka.util.Timeout
import akka.agent.Agent
import colossus.core.Worker.ConnectionSummary

import core._

import akka.actor._
import metrics._

import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicLong

import scala.concurrent.{ExecutionContext, Future}

object IOSystem {

  def apply(config: IOSystemConfig, metrics: MetricSystem)(implicit system: ActorSystem): IOSystem = {
    new IOSystem(config, metrics, system, workerManagerFactory)
  }

  def apply(name: String, numWorkers: Int, metrics: MetricSystem)
    (implicit system: ActorSystem): IOSystem = apply(IOSystemConfig(name, numWorkers), metrics)


  def apply(name: String, numWorkers: Int)(implicit system: ActorSystem): IOSystem = apply(name, numWorkers, MetricSystem.deadSystem)

  def apply(name: String, metrics: MetricSystem)(implicit  system: ActorSystem): IOSystem = {
    val numWorkers = Runtime.getRuntime.availableProcessors()
    apply(name, numWorkers, metrics)
  }

  def apply(name: String = "iosystem", numWorkers: Option[Int] = None)(implicit sys: ActorSystem = ActorSystem(name)): IOSystem = {
    val workers = numWorkers.getOrElse (Runtime.getRuntime.availableProcessors())
    val metrics = MetricSystem(MetricAddress.Root / name)
    apply(name, workers, metrics)
  }

  type WorkerAgent = Agent[IndexedSeq[WorkerRef]]

  private[colossus] val workerManagerFactory = (agent: WorkerAgent, sys: IOSystem) => {
    sys.actorSystem.actorOf(Props(
      classOf[WorkerManager], 
      agent,
      sys  
    ), name = s"${sys.config.name}-manager")
  }

}

/**
 * Configuration used to specify an IOSystem's parameters.
 * @param name The name of the IOSystem
 * @param numWorkers The amount of Worker actors to spawn.  Must be greater than 0.
 */
case class IOSystemConfig(name: String, numWorkers: Int){
  require (numWorkers >= 0) //FIX - we currently need to allow 0 workers for tests
}

/*NOTE: We should look into converting this case class into a trait with a private impl */
/**
 * An IO System is basically a collection of worker actors.  You can attach
 * either servers or clients to an IOSystem.
 *
 * You may have multiple IOSystems per ActorSystem and actors attached to
 * different IOSystems have no rules about cross communication.  The main thing
 * to keep in mind is that all actors in a single IOSystem will share event
 * loops.
 */
class IOSystem private[colossus](
  val config: IOSystemConfig,
  val  metrics: MetricSystem,
  val actorSystem: ActorSystem,
  managerFactory: (IOSystem.WorkerAgent, IOSystem) => ActorRef
) {
  import IOCommand._

  import akka.pattern.ask
  import colossus.core.WorkerManager.RegisteredServers
  import actorSystem.dispatcher

  //TODO : there's a race condition here that could occur if you try to bind a
  //item from here just after starting the IOSystem.
  private val workers: Agent[IndexedSeq[WorkerRef]] = Agent(Vector())
  private var workerMod = 0
  private def nextWorker = {
    val w = workers()
    workerMod += 1
    w(workerMod % w.size)
  }
  private val idGenerator = new AtomicLong(1)

  private[colossus] def generateId() = idGenerator.incrementAndGet()

  /**
   * Generate Contexts by round-robining the workers.  Normally the Workers
   * themselves will generate contexts upon receiving new connections from
   * servers, but this is used mostly for attaching client connections from
   * outside a worker or for creating tasks.  We need the context generated
   * before sending the message to the worker so we can return the proxy actor
   * immediately.
   */
  private[colossus] def generateContext() = new Context(generateId(), nextWorker)


  /**
   * Name of the IOSystem as specified in the IOSystemConfig
   * @return
   */
  def name = config.name

  /**
   * MetricAddress of this IOSystem as seen from the MetricSystem
   */
  val namespace = MetricAddress.Root / config.name

  //ENSURE THIS IS THE LAST THING INITIALIZED!!!
  private[colossus] val workerManager : ActorRef = managerFactory(workers, this)

  // >[*]< SUPER HACK ALERT >[*]<
  while (config.numWorkers > 0 && workers().size == 0) {
    Thread.sleep(25)
  }

  /**
   * Sends a message to the underlying WorkerManager.
   * @param msg Msg to send
   * @param sender The sender of the message, defaults to ActorRef.noSender
   * @return
   */
  def ! (msg: Any)(implicit sender: ActorRef = ActorRef.noSender) = workerManager ! msg

  /**
   * Shuts down the IO System
   */
  def shutdown() {
    workerManager ! WorkerManager.Shutdown
  }
  /**
   * Shuts down the entire actor system
   */
  def apocalypse() {
    workerManager ! WorkerManager.Apocalypse
  }

  def registeredServers(implicit to : Timeout, ec : ExecutionContext) : Future[Seq[ServerRef]] = {
    (workerManager ? WorkerManager.ListRegisteredServers).mapTo[RegisteredServers].map(_.servers)
  }

  def connectionSummary(implicit to : Timeout, ec :ExecutionContext) : Future[ConnectionSummary] = {
    (workerManager ? WorkerManager.GetConnectionSummary).mapTo[ConnectionSummary]
  }

  /**
   * Bind a new WorkerItem that uses a proxy actor, returning the actor
   */
  def bindWithProxy(item: Context => WorkerItem with ProxyActor): ActorRef = {
    val context = generateContext()
    context.worker ! IOCommand.BindWithContext(context, item)
    context.proxy
  }


  /**
   * Connect to the specified address and use the specified function to create a Handler to attach to it.
   * @param address The address with which to connect.
   * @param handler Function which takes in the bound WorkerRef and creates a ClientConnectionHandler which is connected
   *                to the handler.
   */
  def connect(address: InetSocketAddress, handler: Context => ClientConnectionHandler) {
    workerManager ! BindAndConnectWorkerItem(address, handler)
  }
}

/**
 * An IO Command is sent to an IO System, which then routes the command to a
 * random worker using a round-robin router.  If you want more determinism over
 * how items are distributed across workers, just use multiple IO Systems
 *
 * TODO: It may be a better idea to move these into WorkerCommand with a tag
 * trait to show they can be accepted by the IOSystem, and then also add
 * methods onto the IOSystem to eliminate confusion
 */
sealed trait IOCommand

object IOCommand {

  /**
   * Bind a WorkerItem to a worker and then begin establishing an outgoing
   * connection whose events will be sent to the item.
   *
   * Notice that this is different from Worker.Connect, which must be sent to a
   * specific worker and can only be used on a WorkerItem already bound to that
   * worker
   */
  case class BindAndConnectWorkerItem(address: InetSocketAddress, item: Context => WorkerItem) extends IOCommand

  /**
   * Bind a [WorkerItem] to a Worker.  Multiple Bind messages are round
   * robined across workers in an IOSystem
   */
  case class BindWorkerItem(item: Context => WorkerItem) extends IOCommand

  /**
   * Bind a worker item using a context generated by the IOSystem.  The
   * WorkerItem is still created by the worker
   */
  case class BindWithContext(context: Context, item: Context => WorkerItem) extends IOCommand

}
