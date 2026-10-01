package inventoryservice

import cats.effect.{IO, IOApp}
import com.comcast.ip4s._
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits._
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
                  StockEventPublisher.resource[IO](config.kafka).use {
                    publisher =>
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
                          InventoryRoutes.reserveInventoryServerEndpoint[IO](
                            store,
                            logger,
                            publisher
                          ),
                          HealthRoutes.healthServerEndpoint[IO],
                          HealthRoutes.readyServerEndpoint[IO](store)
                        )
                      )
                      val tracedRoutes =
                        ServerTracing.middleware(tracer)(docsRoutes)
                      val routes =
                        ServerMetrics.middleware[IO](meter)(tracedRoutes)
                      EmberServerBuilder
                        .default[IO]
                        .withHost(host"0.0.0.0")
                        .withPort(port)
                        .withHttpApp(routes.orNotFound)
                        .build
                        .useForever
                  }
              }
            } yield ()
        }
      }
    } yield ()
}
