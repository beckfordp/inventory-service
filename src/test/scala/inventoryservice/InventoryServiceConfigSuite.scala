// SPDX-License-Identifier: MIT

package inventoryservice

import cats.effect.IO
import munit.CatsEffectSuite
import pureconfig.ConfigSource

class InventoryServiceConfigSuite extends CatsEffectSuite {

  private val validHocon =
    """
      |port = 8080
      |metrics-port = 9090
      |service-name = "inventory-service"
      |async-api-html-dir = "target/asyncapi-docs"
      |postgres {
      |  host = "localhost"
      |  port = 5432
      |  database = "inventory"
      |  user = "inventory"
      |  password = "inventory"
      |}
      |kafka {
      |  bootstrap-servers = "localhost:9092"
      |  dlq-retry { max-retries = 3, base-delay = 200ms }
      |  dlq-publish-resilience {
      |    retry { max-retries = 3, base-delay = 100ms }
      |    circuit-breaker { failure-threshold = 5, reset-timeout = 30s }
      |  }
      |  stream-supervisor { base-delay = 1s, max-delay = 30s }
      |}
      |operator-auth {
      |  token = "dev-operator-token"
      |  operator-id = "dev-operator"
      |}
      |""".stripMargin

  test("loads a fully-specified config") {
    val result =
      ConfigSource.string(validHocon).load[InventoryServiceConfig]
    assertEquals(
      result,
      Right(
        InventoryServiceConfig(
          port = 8080,
          metricsPort = 9090,
          serviceName = "inventory-service",
          asyncApiHtmlDir = "target/asyncapi-docs",
          postgres = PostgresConfig(
            host = "localhost",
            port = 5432,
            database = "inventory",
            user = "inventory",
            password = "inventory"
          ),
          kafka = KafkaConfig(bootstrapServers = "localhost:9092"),
          operatorAuth =
            OperatorAuthConfig(Some("dev-operator-token"), "dev-operator")
        )
      )
    )
  }

  test("fails to load when a required field is missing") {
    val missingPassword =
      """
        |port = 8080
        |metrics-port = 9090
        |postgres {
        |  host = "localhost"
        |  port = 5432
        |  database = "inventory"
        |  user = "inventory"
        |}
        |""".stripMargin

    assert(
      ConfigSource
        .string(missingPassword)
        .load[InventoryServiceConfig]
        .isLeft
    )
  }

  test("load[F] reads the shipped application.conf defaults") {
    InventoryServiceConfig.load[IO].map { config =>
      assertEquals(config.port, 8080)
      assertEquals(config.metricsPort, 9090)
      assertEquals(config.serviceName, "inventory-service")
      assertEquals(config.asyncApiHtmlDir, "target/asyncapi-docs")
      assertEquals(
        config.postgres,
        PostgresConfig(
          "localhost",
          5432,
          "inventory",
          "inventory",
          "inventory"
        )
      )
    }
  }
}
