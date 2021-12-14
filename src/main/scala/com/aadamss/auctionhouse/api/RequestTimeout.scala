package com.aadamss.auctionhouse.api

import akka.util.Timeout
import com.typesafe.config.Config
import scala.concurrent.duration._

/** Gives access to the request timeout config. */
trait RequestTimeout {
  def requestTimeoutFromConfig(config: Config): Timeout = {
    val timeout = config.getString("akka.http.server.request-timeout")
    val duration = Duration(timeout)

    FiniteDuration(duration.length, duration.unit)
  }
}
