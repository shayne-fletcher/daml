// Copyright (c) 2020 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.platform.store

sealed trait LedgerReadError {
  def message: String
}

sealed trait LedgerReadErrorStreaming extends Throwable with LedgerReadError

final case class PrunedLedgerOffsetNoLongerAvailable(message: String) extends LedgerReadErrorStreaming
