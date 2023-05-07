package actors



import actors.PersistentUserData.Command.CreateUserData
import akka.NotUsed
import akka.actor.{Scheduler, typed}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}
import akka.util.Timeout

import java.util.UUID
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._


object Users {

  // Команды
  import PersistentUserData.Command._
  import PersistentUserData.Command
  import PersistentUserData.Response
  import PersistentUserData.Response._

  //события
  sealed trait Event
  case class UserCreated(id: String) extends Event

  //состояния
  case class State(users: Map[String, ActorRef[Command]])

  //обработчик команд
  def commandHandler(context: ActorContext[Command]): (State, Command) => Effect[Event, State] = (state, command) =>
    command match {
      case createCmd@CreateUserData(_, _, _, _) =>
        val id = UUID.randomUUID().toString
        val newUserData = context.spawn(PersistentUserData(id), id)
        Effect
          .persist(UserCreated(id))
          .thenReply(newUserData)(_ => createCmd)

      case UpdateCmd @ UpdateUserData(id, _, _, replyTo) =>
        state.users.get(id) match {
          case Some(value) =>
            Effect
              .reply(value)(UpdateCmd)
          case None =>
            Effect
            .reply(replyTo)(UserDataUpdatedResponse(None)) // не найден
        }
      case getCmd @ GetUserData(id, replyTo) =>
        state.users.get(id) match {
          case Some(value) =>
            Effect
            .reply(value)(getCmd)
          case None =>
            Effect
              .reply(replyTo)(GetUserDataResponse(None)) // не найден
        }
    }



  //обработчки событий
  def eventHandler(context: ActorContext[Command]): (State, Event) => State = (state, event ) =>
    event match {
      case UserCreated(id) =>
        val user = context.child(id) // получаем актор юзера
          .getOrElse(context.spawn(PersistentUserData(id), id)) // или создаем если нет
          .asInstanceOf[ActorRef[Command]]

        state.copy(state.users + (id -> user))

    }



  //поведение
  def apply(): Behavior[Command] = Behaviors.setup { context =>

    EventSourcedBehavior[Command, Event, State](
      persistenceId = PersistenceId.ofUniqueId("users"),
      emptyState = State(Map()),
      commandHandler = commandHandler(context),
      eventHandler = eventHandler(context)
    )
  }
}




// потестим
object UsersPlayGround {

  import PersistentUserData.Response._
  import PersistentUserData.Response
  import PersistentUserData.Command._


  def main(args: Array[String]): Unit = {

    val rootBehavior: Behavior[NotUsed] = Behaviors.setup{ context =>
      val users = context.spawn(Users(),"users")

      val logger = context.log

      val responseHandler = context.spawn(Behaviors.receiveMessage[Response]{
        case UserDataCreatedResponse(id) =>
          logger.info(s"! -> Пользователь создан -> $id")
          Behaviors.same
        case GetUserDataResponse(maybeUserData) =>
          logger.info(s"! -> Пользователь -> $maybeUserData")
          Behaviors.same
      }, "replyHandler")


      import akka.actor.typed.scaladsl.AskPattern._


      implicit val timeout: Timeout = Timeout(2.second)
      implicit val scheduler: typed.Scheduler = context.system.scheduler
      implicit val ec: ExecutionContext = context.executionContext


     // users ! CreateUserData("User1", "color","000000",responseHandler)
     // users ! GetUserData("f6c127ee-30e9-4c72-9d35-21ddaf614a6d", responseHandler)


      Behaviors.empty
    }


    val system = ActorSystem(rootBehavior, "UserDemo")

  }
}


//f6c127ee-30e9-4c72-9d35-21ddaf614a6d