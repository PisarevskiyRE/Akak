package actors

import akka.actor.typed.{ActorRef, Behavior}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}
import com.fasterxml.jackson.annotation.ObjectIdGenerators.StringIdGenerator


//  отказоустойчивость
//  аудит
object PersistentUserData {
  // commands - сообщения
  sealed trait Command

  object Command {
    case class CreateUserData(user: String, field: String, value: String, replyTo: ActorRef[Response]) extends Command
    case class UpdateUserData(id: String, field: String, value: String, replyTo: ActorRef[Response]) extends Command
    case class GetUserData(id: String, replyTo: ActorRef[Response]) extends Command
  }

  import Command._

  // events - события
  sealed trait Event

  case class UserDataCreated(userData: UserData) extends Event
  case class UserDataUpdated(field: String, value: String) extends Event


  // state - состояние
  case class UserData(id: String, user: String, field: String, value: String)


  // Response - ответ
  sealed trait Response
  object Response {
    case class UserDataCreatedResponse(id: String) extends Response
    case class UserDataUpdatedResponse(maybeUserData: Option[UserData]) extends Response
    case class GetUserDataResponse(maybeUserData: Option[UserData]) extends Response
  }
  import Response._


  //обработчик команд - из данных + соманда => данные + событие в эффекте
  val commandHandler: (UserData, Command) => Effect[Event, UserData] = (state, command) =>
    command match {
      // если пришла команда на создание
      case CreateUserData(user, field, value, replyTo) =>
        val id = state.id
        Effect
          .persist(UserDataCreated(UserData(id, user, field, value))) // записываем событие в касандру
          .thenReply(replyTo)(_ => UserDataCreatedResponse(id))

      // пришла команда на обновление
      case UpdateUserData(_, field, value, replyTo) =>
        val oldField = state.field

        if (field != oldField) // свойтво не найдено
          Effect.reply(replyTo)(UserDataUpdatedResponse(None))
        else
          Effect
          .persist(UserDataUpdated(field, value))
          .thenReply(replyTo)(newState => UserDataUpdatedResponse(Some(newState)))

      case GetUserData(_, replyTo) =>
        Effect.reply(replyTo)(GetUserDataResponse(Some(state)))
    }


  //обработчик событий - из данных + событие => глвые данные
  val eventHandler: (UserData, Event) => UserData = (state, event) =>
    event match {
      case UserDataCreated(userData) => userData
      case UserDataUpdated(field, value) =>
        state.copy(field = field,value = value)
    }



  def apply(id: String): Behavior[Command] =
    EventSourcedBehavior[Command, Event, UserData](
      persistenceId = PersistenceId.ofUniqueId(id),
      emptyState = UserData(id,"","",""), // не будет использоваться
      commandHandler = commandHandler,
      eventHandler = eventHandler
    )
}

