// Copyright (c) 2020 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.ledger.participant.state.v1

import java.util.concurrent.CompletionStage

import com.daml.lf.data.Time.Timestamp

/** An interface to prune participant ledger updates to manage participant ledger space and GDPR-style right to be
  * forgotten. */
trait WriteParticipantPruningService {

  /** Prune the ledger specifying the point in time up to which participant ledger transactions can be removed.
    *
    * As this interface applies only to the local participant, returns a (completion stage of) Option[ParticipantPruned]
    * rather than SubmissionResult.
    *
    * Participants that don't hold participant-local state, return None.
    */
  def pruneByTime(before: Timestamp, submissionId: SubmissionId): CompletionStage[Option[ParticipantPruned]]

}
