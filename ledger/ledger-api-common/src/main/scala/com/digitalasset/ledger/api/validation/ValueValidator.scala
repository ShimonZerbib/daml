// Copyright (c) 2021 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.ledger.api.validation

import com.daml.error.{ContextualizedErrorLogger, NoLogging}
import com.daml.lf.data._
import com.daml.lf.value.Value.{ContractId, ValueUnit}
import com.daml.ledger.api.domain
import com.daml.ledger.api.v1.value.Value.Sum
import com.daml.ledger.api.v1.{value => api}
import com.daml.lf.value.{Value => Lf}
import com.daml.platform.server.api.validation.ErrorFactories._
import com.daml.platform.server.api.validation.FieldValidations.{requirePresence, _}
import io.grpc.StatusRuntimeException

import scalaz.syntax.bifunctor._
import scalaz.std.either._

trait ValueValidator {

  private[validation] def validateRecordFields(
      recordFields: Seq[api.RecordField]
  )(implicit
      contextualizedErrorLogger: ContextualizedErrorLogger
  ): Either[StatusRuntimeException, ImmArray[(Option[Ref.Name], domain.Value)]] =
    recordFields
      .foldLeft[Either[StatusRuntimeException, BackStack[(Option[Ref.Name], domain.Value)]]](
        Right(BackStack.empty)
      )((acc, rf) => {
        for {
          fields <- acc
          v <- requirePresence(rf.value, "value")
          value <- validateValue(v)
          label <- if (rf.label.isEmpty) Right(None) else requireIdentifier(rf.label).map(Some(_))
        } yield fields :+ label -> value
      })
      .map(_.toImmArray)

  def validateRecord(rec: api.Record)(implicit
      contextualizedErrorLogger: ContextualizedErrorLogger
  ): Either[StatusRuntimeException, Lf.ValueRecord] =
    for {
      recId <- validateOptionalIdentifier(rec.recordId)
      fields <- validateRecordFields(rec.fields)
    } yield Lf.ValueRecord(recId, fields)

  private val validNumericString =
    """[+-]?\d{1,38}(\.\d{0,37})?""".r.pattern

  def validateValue(v0: api.Value)(implicit
      contextualizedErrorLogger: ContextualizedErrorLogger
  ): Either[StatusRuntimeException, domain.Value] = v0.sum match {
    case Sum.ContractId(cId) =>
      ContractId
        .fromString(cId)
        .bimap(invalidArgument(definiteAnswer = Some(false)), Lf.ValueContractId(_))
    case Sum.Numeric(value) =>
      def err =
        invalidArgument(definiteAnswer = Some(false))(s"""Could not read Numeric string "$value"""")
      if (validNumericString.matcher(value).matches())
        Numeric
          .fromUnscaledBigDecimal(new java.math.BigDecimal(value))
          .left map (_ => err) map Lf.ValueNumeric
      else
        Left(err)

    case Sum.Party(party) =>
      Ref.Party
        .fromString(party)
        .left
        .map(invalidArgument(definiteAnswer = Some(false)))
        .map(Lf.ValueParty)
    case Sum.Bool(b) => Right(Lf.ValueBool(b))
    case Sum.Timestamp(micros) =>
      Time.Timestamp
        .fromLong(micros)
        .left
        .map(invalidArgument(definiteAnswer = Some(false)))
        .map(Lf.ValueTimestamp)
    case Sum.Date(days) =>
      Time.Date
        .fromDaysSinceEpoch(days)
        .left
        .map(invalidArgument(definiteAnswer = Some(false)))
        .map(Lf.ValueDate)
    case Sum.Text(text) => Right(Lf.ValueText(text))
    case Sum.Int64(value) => Right(Lf.ValueInt64(value))
    case Sum.Record(rec) =>
      validateRecord(rec)
    case Sum.Variant(api.Variant(variantId, constructor, value)) =>
      for {
        validatedVariantId <- validateOptionalIdentifier(variantId)
        validatedConstructor <- requireName(constructor, "constructor")
        v <- requirePresence(value, "value")
        validatedValue <- validateValue(v)
      } yield Lf.ValueVariant(validatedVariantId, validatedConstructor, validatedValue)
    case Sum.Enum(api.Enum(enumId, value)) =>
      for {
        validatedEnumId <- validateOptionalIdentifier(enumId)
        validatedValue <- requireName(value, "value")
      } yield Lf.ValueEnum(validatedEnumId, validatedValue)
    case Sum.List(api.List(elems)) =>
      elems
        .foldLeft[Either[StatusRuntimeException, BackStack[domain.Value]]](Right(BackStack.empty))(
          (valuesE, v) =>
            for {
              values <- valuesE
              validatedValue <- validateValue(v)
            } yield values :+ validatedValue
        )
        .map(elements => Lf.ValueList(elements.toFrontStack))
    case _: Sum.Unit => Right(ValueUnit)
    case Sum.Optional(o) =>
      o.value.fold[Either[StatusRuntimeException, domain.Value]](Right(Lf.ValueNone))(
        validateValue(_).map(v => Lf.ValueOptional(Some(v)))
      )
    case Sum.Map(map0) =>
      val map = map0.entries
        .foldLeft[Either[StatusRuntimeException, FrontStack[(String, domain.Value)]]](
          Right(FrontStack.empty)
        ) { case (acc, api.Map.Entry(key, value0)) =>
          for {
            tail <- acc
            v <- requirePresence(value0, "value")
            validatedValue <- validateValue(v)
          } yield (key -> validatedValue) +: tail
        }
      for {
        entries <- map
        map <- SortedLookupList
          .fromImmArray(entries.toImmArray)
          .left
          .map(invalidArgument(definiteAnswer = Some(false)))
      } yield Lf.ValueTextMap(map)

    case Sum.GenMap(genMap0) =>
      val genMap = genMap0.entries
        .foldLeft[Either[StatusRuntimeException, BackStack[(domain.Value, domain.Value)]]](
          Right(BackStack.empty)
        ) { case (acc, api.GenMap.Entry(key0, value0)) =>
          for {
            stack <- acc
            key <- requirePresence(key0, "key")
            value <- requirePresence(value0, "value")
            validatedKey <- validateValue(key)
            validatedValue <- validateValue(value)
          } yield stack :+ (validatedKey -> validatedValue)
        }
      genMap.map(entries => Lf.ValueGenMap(entries.toImmArray))

    case Sum.Empty => Left(missingField("value", definiteAnswer = Some(false)))
  }

  private[validation] def validateOptionalIdentifier(
      variantIdO: Option[api.Identifier]
  )(implicit
      contextualizedErrorLogger: ContextualizedErrorLogger
  ): Either[StatusRuntimeException, Option[Ref.Identifier]] =
    variantIdO.map(validateIdentifier(_).map(Some.apply)).getOrElse(Right(None))

}

object ValueValidator extends ValueValidator

/** Implementation of self-service error codes brings logging-on-creation to
  * error factories and validators, used in the Ledger API where automatic logging is desired.
  * For places where the ValueValidator is needed without logging(e.g. daml-script, navigator), use this implementation instead.
  */
object NoLoggingValueValidator extends ValueValidator {
  def validateRecord(rec: api.Record): Either[StatusRuntimeException, Lf.ValueRecord] =
    ValueValidator.validateRecord(rec)(NoLogging)

  def validateValue(v0: api.Value): Either[StatusRuntimeException, Lf] =
    ValueValidator.validateValue(v0)(NoLogging)
}
