package inventoryservice

import cats.effect.{IO, IOApp}
import cats.syntax.semigroupk._
import com.comcast.ip4s._
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits._
import purekafka.docs.{Docs => AsyncApiDocs}
import purerest.docs.Docs
import purerest.logging.Logging
import purerest.metrics.{Metrics, ServerMetrics}
import purerest.tracing.{ServerTracing, Tracing}

object Main extends IOApp.Simple {

  val run: IO[Unit] =
    for {
      config <- InventoryServiceConfig.load[IO]
      port <- IO.fromOption(Port.fromInt(config.port))(
        new IllegalArgumentException(
          s"Invalid inventory-service port: ${config.port}"
        )
      )
      _ <- Migrations.run[IO](config.postgres)
      _ <- Tracing.console[IO](config.serviceName).use { tracer =>
        Metrics.oteljava[IO](config.serviceName, config.metricsPort).use {
          meter =>
            for {
              logger <- Logging.create[IO](tracer, config.serviceName)
              _ <- logger.info(
                Map(
                  "port" -> config.port.toString,
                  "metrics_port" -> config.metricsPort.toString
                )
              )("inventory-service starting")
              _ <- InventoryStore.postgres[IO](config.postgres, meter).use {
                store =>
                  val docsRoutes = Docs.routes[IO](
                    "Inventory Service",
                    "1.0",
                    List(
                      InventoryRoutes.serverEndpoint[IO](store, logger),
                      InventoryRoutes
                        .getInventoryServerEndpoint[IO](store, logger),
                      InventoryRoutes
                        .updateInventoryServerEndpoint[IO](store, logger),
                      InventoryRoutes
                        .replaceInventoryServerEndpoint[IO](store, logger),
                      InventoryRoutes
                        .deleteInventoryServerEndpoint[IO](store, logger),
                      InventoryRoutes
                        .reserveInventoryServerEndpoint[IO](store, logger),
                      InventoryRoutes
                        .listInventoryServerEndpoint[IO](store, logger),
                      HealthRoutes.healthServerEndpoint[IO],
                      HealthRoutes.readyServerEndpoint[IO](store)
                    )
                  )
                  val asyncApiRoutes = AsyncApiDocs.routes[IO](
                    "Inventory Service",
                    "1.0",
                    AsyncApiChannels.channels
                  )
                  // asyncApiRoutes must come before docsRoutes: docsRoutes'
                  // Swagger UI bundle serves a wildcard /docs/{resource}
                  // route for its own static assets, which returns its own
                  // real 404 (not a http4s fallthrough None) for any
                  // /docs/* path it doesn't recognize - including
                  // /docs/asyncapi.json - short-circuiting <+> before
                  // asyncApiRoutes ever sees the request (found the hard
                  // way in order-service's own asyncapi-docs_20261017 track).
                  val tracedRoutes =
                    ServerTracing.middleware(tracer)(
                      asyncApiRoutes <+> docsRoutes
                    )
                  val meteredRoutes =
                    ServerMetrics.middleware[IO](meter)(tracedRoutes)
                  val routes = Cors.middleware[IO](meteredRoutes)
                  EmberServerBuilder
                    .default[IO]
                    .withHost(host"0.0.0.0")
                    .withPort(port)
                    .withHttpApp(routes.orNotFound)
                    .build
                    .useForever
              }
            } yield ()
        }
      }
    } yield ()
}
