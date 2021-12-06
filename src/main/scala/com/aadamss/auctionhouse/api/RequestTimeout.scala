package com.aadamss.auctionhouse.api

import akka.util.Timeout
import com.typesafe.config.Config
import scala.concurrent.duration._

trait RequestTimeout {
  def requestTimeoutFromConfig(config: Config): Timeout = {
    val timeout = config.getString("akka.http.server.request-timeout")
    val duration = Duration(timeout)

    FiniteDuration(duration.length, duration.unit)
  }
}
