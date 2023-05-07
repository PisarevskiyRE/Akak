package app

import actors.PersistentUserData.Command
import actors.Users
import akka.NotUsed
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.AskPattern._
import akka.http.scaladsl.Http
import akka.util.Timeout
import http.UserRoutes

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

import scala.util.{Try, Success, Failure}

object UsersApp {

  def startHttpServer(users: ActorRef[Command])(implicit system: ActorSystem[_]): Unit = {
    implicit val ec: ExecutionContext = system.executionContext
    val router = new UserRoutes(users)
    val routes = router.routes

    val httpBindingFuture = Http().newServerAt("localhost",8080).bind(routes)
    httpBindingFuture.onComplete{
      case Success(binding) =>
        val address = binding.localAddress
        system.log.info(s"Стартуем ${address.getHostString} - ${address.getPort}")

      case Failure(ex) =>
        system.log.info(s"ОШИБКА ${ex}")
        system.terminate()

    }
  }


  def main(args: Array[String]): Unit = {

    trait RootCommand
    case class RetrieveUsersActor(replyTo: ActorRef[ActorRef[Command]]) extends RootCommand


    val rootBehavior: Behavior[RootCommand] = Behaviors.setup{ context =>
      val usersActor = context.spawn(Users(),"users")
      Behaviors.receiveMessage{
        case RetrieveUsersActor(replyTo) =>
          replyTo ! usersActor
          Behaviors.same
      }
    }

    implicit val system: ActorSystem[RootCommand] = ActorSystem(rootBehavior, "UsersSystem")
    implicit val timeout: Timeout = Timeout(5.seconds)
    implicit val ec: ExecutionContext = system.executionContext


    val userDataActorFuture: Future[ActorRef[Command]] = system.ask(replyTo => RetrieveUsersActor(replyTo))
    userDataActorFuture.foreach(startHttpServer)


  }
}
