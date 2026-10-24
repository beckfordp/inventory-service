package inventoryservice

import cats.effect.IO
import munit.{CatsEffectSuite, FunSuite}
import org.http4s.implicits._
import org.http4s.{Method, Request, Status}
import purekafka.docs.{
  AsyncApiChannel,
  ChannelDirection,
  Docs => AsyncApiDocs,
  PublisherKind
}

class AsyncApiChannelsSuite extends FunSuite {

  test(
    "AsyncApiChannel constructs for both of inventory-service's registered event types"
  ) {
    val stockReserved =
      AsyncApiChannel[StockReservedEvent](
        topic = "inventory-events",
        eventType = "StockReserved"
      )
    val stockReservationFailed =
      AsyncApiChannel[StockReservationFailedEvent](
        topic = "inventory-events",
        eventType = "StockReservationFailed"
      )

    assertEquals(stockReserved.topic, "inventory-events")
    assertEquals(stockReservationFailed.topic, "inventory-events")
  }

  test(
    "AsyncApiChannels.channels registers both event types on inventory-events, as Publish(Cdc)"
  ) {
    val topicsAndEventTypes =
      AsyncApiChannels.channels.map(c => c.topic -> c.eventType).toSet

    assertEquals(
      topicsAndEventTypes,
      Set(
        "inventory-events" -> "StockReserved",
        "inventory-events" -> "StockReservationFailed"
      )
    )

    for (channel <- AsyncApiChannels.channels)
      assertEquals(
        channel.direction,
        ChannelDirection.Publish(PublisherKind.Cdc): ChannelDirection,
        s"expected ${channel.eventType} to be Publish(Cdc) - inventory-service's own outbox publishes it via Debezium CDC"
      )
  }
}

class AsyncApiRoutesSuite extends CatsEffectSuite {

  test("GET /docs/asyncapi.json returns 200 with both event types") {
    val routes =
      AsyncApiDocs.routes[IO](
        "Inventory Service",
        "1.0",
        AsyncApiChannels.channels
      )
    for {
      response <- routes.orNotFound.run(
        Request[IO](Method.GET, uri"/docs/asyncapi.json")
      )
      body <- response.bodyText.compile.string
    } yield {
      assertEquals(response.status, Status.Ok)
      assert(clue(body).contains("StockReserved"))
      assert(clue(body).contains("StockReservationFailed"))
    }
  }
}
