package inventoryservice

import cats.effect.Sync
import pureconfig.{ConfigReader, ConfigSource}

final case class PostgresConfig(
    host: String,
    port: Int,
    database: String,
    user: String,
    password: String
) derives ConfigReader

final case class KafkaConfig(
    bootstrapServers: String
) derives ConfigReader

final case class InventoryServiceConfig(
    port: Int,
    metricsPort: Int,
    serviceName: String,
    postgres: PostgresConfig,
    kafka: KafkaConfig
) derives ConfigReader

object InventoryServiceConfig {
  def load[F[_]: Sync]: F[InventoryServiceConfig] =
    Sync[F].delay(ConfigSource.default.loadOrThrow[InventoryServiceConfig])
}
