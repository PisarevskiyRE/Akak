package http

import cats.data.ValidatedNel
import cats.implicits.catsSyntaxValidatedId


object Validation {

  trait Required[A] extends (A => Boolean)

  implicit val requiredField: Required[String] = _.nonEmpty

  def required[A](value: A)(implicit req: Required[A]): Boolean = req(value)


  type ValidationResult[A] = ValidatedNel[ValidationFailure, A]

  trait ValidationFailure {
    def errorMessage: String
  }


  case class EmptyField(fieldName: String) extends ValidationFailure {
    override def errorMessage: String = s"$fieldName пустое"
  }



  def validateRequired[A: Required](value: A, fieldName: String) : ValidationResult[A] =
    if (required(value)) value.validNel
    else EmptyField(fieldName).invalidNel


  trait Validator[A] {
    def validate(value: A): ValidationResult[A]
  }

  def validateEntity[A](value: A)(implicit validator: Validator[A]): ValidationResult[A] =
    validator.validate(value)

}
