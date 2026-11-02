package inventoryservice

import cats.effect.Sync
import pureconfig.{ConfigReader, ConfigSource}
import purekafka.consumer.StreamSupervisorConfig
import purekafka.resilience.{
  CircuitBreakerConfig => KafkaCircuitBreakerConfig,
  ResilienceConfig => KafkaResilienceConfig,
  RetryConfig => KafkaRetryConfig
}

import scala.concurrent.duration._

final case class PostgresConfig(
    host: String,
    port: Int,
    database: String,
    user: String,
    password: String
) derives ConfigReader

given ConfigReader[KafkaCircuitBreakerConfig] = ConfigReader.derived
given ConfigReader[KafkaResilienceConfig] = ConfigReader.derived
given ConfigReader[StreamSupervisorConfig] = ConfigReader.derived

final case class KafkaConfig(
    bootstrapServers: String,
    dlqRetry: KafkaRetryConfig =
      KafkaRetryConfig(maxRetries = 3, baseDelay = 200.millis),
    dlqPublishResilience: KafkaResilienceConfig = KafkaResilienceConfig(
      retry = KafkaRetryConfig(maxRetries = 3, baseDelay = 100.millis),
      circuitBreaker = KafkaCircuitBreakerConfig(
        failureThreshold = 5,
        resetTimeout = 30.seconds
      )
    ),
    streamSupervisor: StreamSupervisorConfig =
      StreamSupervisorConfig(baseDelay = 1.second, maxDelay = 30.seconds)
) derives ConfigReader

final case class OperatorAuthConfig(
    token: Option[String],
    operatorId: String
) derives ConfigReader

final case class InventoryServiceConfig(
    port: Int,
    metricsPort: Int,
    serviceName: String,
    asyncApiHtmlDir: String,
    postgres: PostgresConfig,
    kafka: KafkaConfig,
    operatorAuth: OperatorAuthConfig
) derives ConfigReader

object InventoryServiceConfig {
  def load[F[_]: Sync]: F[InventoryServiceConfig] =
    Sync[F].delay(ConfigSource.default.loadOrThrow[InventoryServiceConfig])
}
