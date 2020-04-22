// Copyright (c) 2020 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.ledger.api.auth.services

import com.daml.dec.DirectExecutionContext
import com.daml.ledger.api.auth.Authorizer
import com.daml.platform.api.grpc.GrpcApiService
import com.daml.platform.server.api.ProxyCloseable
import com.digitalasset.ledger.api.v1.admin.participant_pruning_service.{
  ParticipantPruningServiceGrpc,
  PruneByTimeRequest,
  PruneByTimeResponse
}
import com.digitalasset.ledger.api.v1.admin.participant_pruning_service.ParticipantPruningServiceGrpc.ParticipantPruningService
import io.grpc.ServerServiceDefinition

import scala.concurrent.Future

class ParticipantPruningServiceAuthorization(
    protected val service: ParticipantPruningService with AutoCloseable,
    private val authorizer: Authorizer)
    extends ParticipantPruningService
    with ProxyCloseable
    with GrpcApiService {

  override def bindService(): ServerServiceDefinition =
    ParticipantPruningServiceGrpc.bindService(this, DirectExecutionContext)

  override def close(): Unit = service.close()

  override def pruneByTime(request: PruneByTimeRequest): Future[PruneByTimeResponse] =
    authorizer.requireAdminClaims(service.pruneByTime)(request)

}
