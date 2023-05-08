package http

import actors.PersistentUserData.Response._
import actors.PersistentUserData.Response
import actors.PersistentUserData.Command
import actors.PersistentUserData.Command._
import akka.actor.typed.ActorSystem
import akka.actor.typed.ActorRef
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.Location
import akka.http.scaladsl.server.Directives._
import akka.actor.typed.scaladsl.AskPattern._
import akka.http.scaladsl.server.Route
import akka.util.Timeout
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import http.Validation._
import io.circe.generic.auto._

import scala.util._


import scala.concurrent.Future
import scala.concurrent.duration._
import cats.implicits._
import cats.data.Validated.{Invalid, Valid}

case class UserDataCreationRequest(user: String, field: String, value: String) {
  def toCommand(replyTo: ActorRef[Response]): Command = CreateUserData(user, field, value, replyTo)
}

object UserDataCreationRequest {
  implicit val validator: Validator[UserDataCreationRequest] = new Validator[UserDataCreationRequest] {
    override def validate(request: UserDataCreationRequest): ValidationResult[UserDataCreationRequest] = {
      val userValidation = validateRequired(request.user, "user")
      val filedValidation = validateRequired(request.field, "field")
      val valueValidation = validateRequired(request.value, "value")

      (userValidation, filedValidation, valueValidation).mapN(UserDataCreationRequest.apply)
    }
  }
}



case class UserDataUpdateRequest(field: String, value: String) {
  def toCommand(id: String, replyTo: ActorRef[Response]): Command = UpdateUserData(id, field, value, replyTo)
}

object UserDataUpdateRequest {
  implicit val validator: Validator[UserDataUpdateRequest] = new Validator[UserDataUpdateRequest] {
    override def validate(request: UserDataUpdateRequest): ValidationResult[UserDataUpdateRequest] = {
      val filedValidation = validateRequired(request.field, "field")
      val valueValidation = validateRequired(request.value, "value")

      (filedValidation, valueValidation).mapN(UserDataUpdateRequest.apply)
    }
  }
}





case class FailureResponse(reason: String)


class UserRoutes(users: ActorRef[Command])(implicit system: ActorSystem[_]) {

  implicit val timeout: Timeout = Timeout(5.seconds)

  def createUserData(request: UserDataCreationRequest): Future[Response] =
    users.ask(replyTo => request.toCommand(replyTo))

  def getUserData(id: String): Future[Response] =
    users.ask(replyTo => GetUserData(id, replyTo))

  def updateUserData(id: String, request: UserDataUpdateRequest): Future[Response] =
    users.ask(replyTo => request.toCommand(id, replyTo))


  def validateRequest[R: Validator](requets: R)(routeIfValid: Route): Route =
    validateEntity(requets) match {
      case Valid(_) =>
        routeIfValid
      case Invalid(failures) =>
        complete(StatusCodes.BadRequest, FailureResponse(failures.toList.map(_.errorMessage).mkString(", ")))
    }
  /*
    POST /users/
      Payload: запрос на создание пользователя
      Response:
        201 Создан
        location: /users/uuid

    GET /users/uuid
      Response:
        200 OK
          JSON детали юзера
        404 не найден

    PUT /users/uuid
      Payload: (field, value) as JSON
      Response:
        - 200 OK
          Payload: свойства пользователя JSON
        - 404 не найден
        - *400 не верный запрос
   */

  val routes =
    pathPrefix("users") {
      pathEndOrSingleSlash {
        post {
          // если парсится в UserDataCreationRequest
          // переводим реквест в команду актора
          // и отправляем, проверяем ответ и отправляет отвект http
          entity(as[UserDataCreationRequest]) { request =>

            validateRequest(request) {

              onSuccess(createUserData(request)) {
                  case UserDataCreatedResponse(id) =>
                  respondWithHeader(Location(s"/users/$id")) {
                    complete(StatusCodes.Created)
                }
              }
            }
          }
        }
      } ~
      path(Segment) { id =>
        get {
          /*
          - отправить команду актору юзеров
          - разобрать ответ
           */
          onSuccess(getUserData(id)) {
            case GetUserDataResponse(Some(userData)) =>
              complete(userData) //200 ОК
            case GetUserDataResponse(None) =>
              complete(StatusCodes.NotFound, FailureResponse(s"Пользовать $id не найден"))
          }
        } ~
        put {
          entity(as[UserDataUpdateRequest]) { request =>
            /*
          - трансформируем запрос в команду
          - отправляем команду актору
          - смотрим ответ
          */
            validateRequest(request) {

              onSuccess(updateUserData(id, request)) {
                //- отпрвяем ответ по HTTP
                case UserDataUpdatedResponse(Success(userData)) =>
                  complete(userData)
                case UserDataUpdatedResponse(Failure(ex)) =>
                  complete(StatusCodes.BadRequest, FailureResponse(s"${ex.getMessage}"))
              }
            }
          }
        }
      }
    }
}
