package inventoryservice

import cats.Monad
import org.http4s.{HttpRoutes, Method}
import org.http4s.server.middleware.CORS
import org.typelevel.ci._

/** CORS policy: allow all origins (no single frontend origin is pinned),
  * restricted to the methods/headers this service's routes actually use,
  * credentials disallowed (required anyway alongside an allow-all-origins
  * policy — browsers reject credentialed requests against a wildcard origin).
  */
object Cors {

  def middleware[F[_]: Monad](routes: HttpRoutes[F]): HttpRoutes[F] =
    CORS.policy.withAllowOriginAll
      .withAllowMethodsIn(
        Set(Method.GET, Method.POST, Method.PATCH, Method.PUT, Method.DELETE)
      )
      .withAllowHeadersIn(Set(ci"Content-Type"))
      .withAllowCredentials(false)
      .apply(routes)
}
