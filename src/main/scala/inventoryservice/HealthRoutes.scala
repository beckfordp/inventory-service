package inventoryservice

import cats.{Applicative, Monad}
import cats.effect.Async
import cats.syntax.all._
import org.http4s.HttpRoutes
import sttp.model.StatusCode
import sttp.tapir._
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.http4s.Http4sServerInterpreter

object HealthRoutes {

  private val healthEndpoint: PublicEndpoint[Unit, Unit, Unit, Any] =
    endpoint.get
      .in("health")
      .out(statusCode(StatusCode.Ok))
      .summary("Liveness check")
      .description("200 while the process is up.")
      .tag("health")

  private val readyEndpoint: PublicEndpoint[Unit, Unit, Unit, Any] =
    endpoint.get
      .in("health" / "ready")
      .out(statusCode(StatusCode.Ok))
      .errorOut(
        statusCode(StatusCode.ServiceUnavailable)
          .description("Postgres didn't answer a ping.")
      )
      .summary("Readiness check")
      .description("200 when Postgres answers a ping, else 503.")
      .tag("health")

  def healthServerEndpoint[F[_]: Applicative]: ServerEndpoint[Any, F] =
    healthEndpoint.serverLogicSuccess[F](_ => Applicative[F].unit)

  def readyServerEndpoint[F[_]: Monad](
      store: InventoryStore[F]
  ): ServerEndpoint[Any, F] =
    readyEndpoint.serverLogic[F] { _ =>
      store.ping.map(ready => if (ready) Right(()) else Left(()))
    }

  def serverEndpoints[F[_]: Monad](
      store: InventoryStore[F]
  ): List[ServerEndpoint[Any, F]] =
    List(healthServerEndpoint[F], readyServerEndpoint(store))

  def routes[F[_]: Async](store: InventoryStore[F]): HttpRoutes[F] =
    Http4sServerInterpreter[F]().toRoutes(serverEndpoints(store))
}
