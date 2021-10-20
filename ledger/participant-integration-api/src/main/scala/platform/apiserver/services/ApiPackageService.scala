// Copyright (c) 2021 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.platform.apiserver.services

import com.daml.daml_lf_dev.DamlLf.{Archive, HashFunction}
import com.daml.error.{DamlContextualizedErrorLogger, ErrorCodesVersionSwitcher}
import com.daml.ledger.api.domain.LedgerId
import com.daml.ledger.api.v1.package_service.PackageServiceGrpc.PackageService
import com.daml.ledger.api.v1.package_service.{HashFunction => APIHashFunction, _}
import com.daml.ledger.participant.state.index.v2.IndexPackagesService
import com.daml.lf.data.Ref
import com.daml.logging.LoggingContext.withEnrichedLoggingContext
import com.daml.logging.{ContextualizedLogger, LoggingContext}
import com.daml.platform.api.grpc.GrpcApiService
import com.daml.platform.server.api.validation.{ErrorFactories, PackageServiceValidation}
import io.grpc.{BindableService, ServerServiceDefinition}

import scala.concurrent.{ExecutionContext, Future}

private[apiserver] final class ApiPackageService private (
    backend: IndexPackagesService,
    errorCodesVersionSwitcher: ErrorCodesVersionSwitcher,
    loggingContext: LoggingContext,
)(implicit executionContext: ExecutionContext)
    extends PackageService
    with GrpcApiService {

  private implicit val logger: ContextualizedLogger = ContextualizedLogger.get(this.getClass)

  private val errorFactories = ErrorFactories(errorCodesVersionSwitcher)

  override def listPackages(request: ListPackagesRequest): Future[ListPackagesResponse] = {
    doListPackages(request)(loggingContext)
  }

  override def getPackage(request: GetPackageRequest): Future[GetPackageResponse] = {
    doGetPackage(request)(loggingContext)
  }

  override def getPackageStatus(
      request: GetPackageStatusRequest
  ): Future[GetPackageStatusResponse] = {
    doGetPackageStatus(request)(loggingContext)
  }

  override def close(): Unit = ()

  override def bindService(): ServerServiceDefinition =
    PackageServiceGrpc.bindService(this, executionContext)

  private def doListPackages(
      request: ListPackagesRequest
  )(implicit loggingContext: LoggingContext): Future[ListPackagesResponse] = {
    logger.info(s"Received request to list packages: $request")
    backend
      .listLfPackages()
      .map(p => ListPackagesResponse(p.keys.toSeq))
      .andThen(logger.logErrorsOnCall[ListPackagesResponse])
  }

  private def doGetPackage(
      request: GetPackageRequest
  )(implicit loggingContext: LoggingContext): Future[GetPackageResponse] =
    withEnrichedLoggingContext("packageId" -> request.packageId) { implicit loggingContext =>
      logger.info(s"Received request for a package: $request")
      withValidatedPackageId(request.packageId, request) { packageId =>
        backend
          .getLfArchive(packageId)
          .flatMap {
            case None =>
              Future.failed[GetPackageResponse](
                errorFactories.packageNotFound(packageId = packageId)(
                  createContextualizedErrorLogger
                )
              )
            case Some(archive) => Future.successful(toGetPackageResponse(archive))
          }
          .andThen(logger.logErrorsOnCall[GetPackageResponse])
      }
    }

  private def doGetPackageStatus(
      request: GetPackageStatusRequest
  )(implicit loggingContext: LoggingContext): Future[GetPackageStatusResponse] =
    withEnrichedLoggingContext("packageId" -> request.packageId) { implicit loggingContext =>
      logger.info(s"Received request for a package status: $request")
      withValidatedPackageId(request.packageId, request) { packageId =>
        backend
          .listLfPackages()
          .map { packages =>
            val result = if (packages.contains(packageId)) {
              PackageStatus.REGISTERED
            } else {
              PackageStatus.UNKNOWN
            }
            GetPackageStatusResponse(result)
          }
          .andThen(logger.logErrorsOnCall[GetPackageStatusResponse])
      }
    }

  private def withValidatedPackageId[T, R](packageId: String, request: R)(
      block: Ref.PackageId => Future[T]
  )(implicit loggingContext: LoggingContext): Future[T] =
    Ref.PackageId
      .fromString(packageId)
      .fold(
        errorMessage =>
          Future.failed[T](
            errorFactories.malformedPackageId(request = request, message = errorMessage)(
              createContextualizedErrorLogger,
              logger,
              loggingContext,
            )
          ),
        packageId => block(packageId),
      )

  private def toGetPackageResponse(archive: Archive): GetPackageResponse = {
    val hashFunction = archive.getHashFunction match {
      case HashFunction.SHA256 => APIHashFunction.SHA256
      case _ => APIHashFunction.Unrecognized(-1)
    }
    GetPackageResponse(
      hashFunction = hashFunction,
      archivePayload = archive.getPayload,
      hash = archive.getHash,
    )
  }

  private def createContextualizedErrorLogger(implicit
      loggingContext: LoggingContext
  ): DamlContextualizedErrorLogger =
    new DamlContextualizedErrorLogger(logger, loggingContext, None)
}

private[platform] object ApiPackageService {
  def create(
      ledgerId: LedgerId,
      backend: IndexPackagesService,
      errorCodesVersionSwitcher: ErrorCodesVersionSwitcher,
  )(implicit
      executionContext: ExecutionContext,
      loggingContext: LoggingContext,
  ): PackageService with GrpcApiService = {
    val service = new ApiPackageService(
      backend = backend,
      errorCodesVersionSwitcher = errorCodesVersionSwitcher,
      loggingContext = loggingContext,
    )
    new PackageServiceValidation(
      service = service,
      ledgerId = ledgerId,
    ) with BindableService {
      override def bindService(): ServerServiceDefinition =
        PackageServiceGrpc.bindService(this, executionContext)
    }
  }
}
