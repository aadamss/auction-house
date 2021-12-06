package com.aadamss.auctionhouse

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.Http.ServerBinding
import akka.stream.Materializer
import akka.util.Timeout
import api.RequestTimeout
import com.aadamss.auctionhouse.routes.RestRoutes
import com.typesafe.config.{Config, ConfigFactory}
import scala.concurrent.{ExecutionContextExecutor, Future}

object Main extends App with RestRoutes with RequestTimeout {

  implicit val config: Config = ConfigFactory.load()

  val host = config.getString("http.host")
  val port = config.getInt("http.port")

  implicit val requestTimeout: Timeout = requestTimeoutFromConfig(config)
  implicit val system: ActorSystem = ActorSystem()
  implicit val executionContext: ExecutionContextExecutor = system.dispatcher
  implicit val materializer: Materializer = Materializer(ActorSystem())

  val bindServer: Future[ServerBinding] =
    Http().newServerAt(host, port).bind(routes)

  bindServer map { serverBinding =>
    println(s"API is bound to ${serverBinding.localAddress} ")
  } recover { case exception: Exception =>
    println(exception, s"Error binding to $host:$port!")
    system.terminate()
  }
}
