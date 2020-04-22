// Copyright (c) 2020 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.platform.apiserver.services.admin

import java.time.{DateTimeException, Instant}
import java.util.UUID

import com.daml.api.util.TimeProvider
import com.daml.dec.{DirectExecutionContext => DE}
import com.daml.ledger.api.v1.ledger_offset.{LedgerOffset => LedgerOffsetProto}
import com.daml.ledger.participant.state.index.v2.IndexParticipantPruningService
import com.daml.ledger.participant.state.v1.{
  ParticipantPruned,
  SubmissionId,
  WriteParticipantPruningService
}
import com.daml.lf.data.Time.Timestamp
import com.daml.logging.{ContextualizedLogger, LoggingContext}
import com.daml.platform.ApiOffset
import com.daml.platform.api.grpc.GrpcApiService
import com.daml.platform.server.api.validation.ErrorFactories
import com.digitalasset.ledger.api.v1.admin.participant_pruning_service.{
  ParticipantPruningServiceGrpc,
  PruneByTimeRequest,
  PruneByTimeResponse
}
import io.grpc.{ServerServiceDefinition, StatusRuntimeException}

import scala.compat.java8.FutureConverters
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

final class ApiParticipantPruningService private (
    readBackend: IndexParticipantPruningService,
    writeBackend: WriteParticipantPruningService,
    cannotPruneMoreRecentlyThan: FiniteDuration,
    timeProvider: TimeProvider)(implicit logCtx: LoggingContext)
    extends ParticipantPruningServiceGrpc.ParticipantPruningService
    with GrpcApiService {

  private val logger = ContextualizedLogger.get(this.getClass)

  override def close(): Unit = ()

  override def bindService(): ServerServiceDefinition =
    ParticipantPruningServiceGrpc.bindService(this, DE)

  override def pruneByTime(request: PruneByTimeRequest): Future[PruneByTimeResponse] = {
    implicit val ec: ExecutionContext = DE
    val submissionId =
      if (request.submissionId.isEmpty)
        SubmissionId.assertFromString(UUID.randomUUID().toString)
      else
        SubmissionId.assertFromString(request.submissionId)

    val pruneBeforeTimestamp: Either[StatusRuntimeException, Timestamp] = for {
      pruneBeforeProto <- request.pruneBefore.toRight(
        ErrorFactories.invalidArgument("prune_before not specified"))

      pruneBeforeInstant <- Try(
        Instant
          .ofEpochSecond(pruneBeforeProto.seconds, pruneBeforeProto.nanos.toLong)).toEither.left
        .map {
          case t: ArithmeticException =>
            ErrorFactories.invalidArgument(
              s"arithmetic overflow converting prune_before to instant: ${t.getMessage}")
          case t: DateTimeException =>
            ErrorFactories.invalidArgument(
              s"timestamp exceeds min or max of instant: ${t.getMessage}")
          case t => ErrorFactories.internal(t.toString)
        }

      pruneBefore <- Timestamp
        .fromInstant(pruneBeforeInstant)
        .left
        .map(e => ErrorFactories.invalidArgument(s"failed to convert prune_before to timestamp $e"))

      now = timeProvider.getCurrentTime

      maxPruningLimit <- Try(
        now
          .minusMillis(cannotPruneMoreRecentlyThan.toMillis)
          .minusNanos(cannotPruneMoreRecentlyThan.toNanos)).toEither.left.map(t =>
        ErrorFactories.internal(
          s"failed to adjust current time by pruning safety duration ${t.toString}"))

      _ <- Either.cond(
        !maxPruningLimit.isBefore(pruneBeforeInstant),
        (),
        ErrorFactories.invalidArgument(
          s"prune_before $pruneBeforeInstant is too recent. You can prune at most at $maxPruningLimit")
      )

    } yield pruneBefore

    pruneBeforeTimestamp.fold(
      Future.failed,
      pruneBefore =>
        LoggingContext.withEnrichedLoggingContext("submissionId" -> submissionId) {
          implicit logCtx =>
            logger.info(s"About to prune participant at ${pruneBefore.toString}")

            (for {
              ledgerPruned <- FutureConverters.toScala(
                writeBackend.pruneByTime(pruneBefore, submissionId))

              pruneByTimeResponse <- ledgerPruned.fold[Future[PruneByTimeResponse]](
                Future.failed(ErrorFactories.participantPruningPointNotFound(
                  s"Cannot prune participant at ${pruneBefore.toString}"))) {
                case pp @ ParticipantPruned(prunedUpToInclusiveOffset, _) =>
                  readBackend
                    .pruneByOffset(prunedUpToInclusiveOffset)
                    .map(_ =>
                      PruneByTimeResponse(
                        prunedOffset = Some(LedgerOffsetProto(LedgerOffsetProto.Value.Absolute(
                          ApiOffset.toApiString(pp.prunedUpToInclusiveOffset)))),
                        stateRetainedUntilOffset = pp.stateRetainedUntilOffset.map(offset =>
                          LedgerOffsetProto(
                            LedgerOffsetProto.Value.Absolute(ApiOffset.toApiString(offset))))
                    ))
              }
            } yield pruneByTimeResponse).andThen(logger.logErrorsOnCall[PruneByTimeResponse])(DE)
      }
    )
  }
}

object ApiParticipantPruningService {
  def createApiService(
      readBackend: IndexParticipantPruningService,
      writeBackend: WriteParticipantPruningService,
      cannotPruneMoreRecentlyThan: FiniteDuration,
      timeProvider: TimeProvider
  )(implicit logCtx: LoggingContext)
    : ParticipantPruningServiceGrpc.ParticipantPruningService with GrpcApiService =
    new ApiParticipantPruningService(
      readBackend,
      writeBackend,
      cannotPruneMoreRecentlyThan,
      timeProvider)

}
