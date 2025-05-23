// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.domain.sequencing.sequencer.block.bftordering.core.modules.consensus.iss.data.memory

import com.digitalasset.canton.discard.Implicits.DiscardOps
import com.digitalasset.canton.domain.sequencing.sequencer.block.bftordering.core.modules.consensus.iss.data.EpochStore.{
  Block,
  Epoch,
  EpochInProgress,
}
import com.digitalasset.canton.domain.sequencing.sequencer.block.bftordering.core.modules.consensus.iss.data.memory.GenericInMemoryEpochStore.{
  CompletedBlock,
  EpochStatus,
  pbftEventSortData,
}
import com.digitalasset.canton.domain.sequencing.sequencer.block.bftordering.core.modules.consensus.iss.data.{
  EpochStore,
  Genesis,
  OrderedBlocksReader,
}
import com.digitalasset.canton.domain.sequencing.sequencer.block.bftordering.framework.Env
import com.digitalasset.canton.domain.sequencing.sequencer.block.bftordering.framework.data.NumberIdentifiers.{
  BlockNumber,
  EpochNumber,
  ViewNumber,
}
import com.digitalasset.canton.domain.sequencing.sequencer.block.bftordering.framework.data.SignedMessage
import com.digitalasset.canton.domain.sequencing.sequencer.block.bftordering.framework.data.ordering.iss.EpochInfo
import com.digitalasset.canton.domain.sequencing.sequencer.block.bftordering.framework.data.ordering.{
  CommitCertificate,
  OrderedBlock,
  OrderedBlockForOutput,
}
import com.digitalasset.canton.domain.sequencing.sequencer.block.bftordering.framework.modules.ConsensusSegment.ConsensusMessage
import com.digitalasset.canton.domain.sequencing.sequencer.block.bftordering.framework.modules.ConsensusSegment.ConsensusMessage.{
  Commit,
  NewView,
  PbftNetworkMessage,
  PrePrepare,
  Prepare,
  ViewChange,
}
import com.digitalasset.canton.domain.sequencing.sequencer.block.bftordering.framework.pekko.PekkoModuleSystem.{
  PekkoEnv,
  PekkoFutureUnlessShutdown,
}
import com.digitalasset.canton.lifecycle.FutureUnlessShutdown
import com.digitalasset.canton.tracing.TraceContext

import scala.collection.MapView
import scala.collection.concurrent.TrieMap
import scala.util.{Failure, Success, Try}

abstract class GenericInMemoryEpochStore[E <: Env[E]]
    extends EpochStore[E]
    with OrderedBlocksReader[E] {
  private val epochs: TrieMap[EpochNumber, EpochStatus] = TrieMap.empty
  private val blocks: TrieMap[BlockNumber, CompletedBlock] = TrieMap.empty

  // The maps below are for messages for in-progress blocks.
  private val prePreparesMap: TrieMap[BlockNumber, TrieMap[ViewNumber, SignedMessage[PrePrepare]]] =
    TrieMap.empty
  private val preparesMap: TrieMap[BlockNumber, TrieMap[ViewNumber, Seq[SignedMessage[Prepare]]]] =
    TrieMap.empty
  private val viewChangesMap: TrieMap[BlockNumber, TrieMap[ViewNumber, SignedMessage[ViewChange]]] =
    TrieMap.empty
  private val newViewsMap: TrieMap[BlockNumber, TrieMap[ViewNumber, SignedMessage[NewView]]] =
    TrieMap.empty

  protected def createFuture[T](action: String)(value: () => Try[T]): E#FutureUnlessShutdownT[T]

  private def addSingleMessageToMap[M <: PbftNetworkMessage](
      storeType: String,
      map: TrieMap[BlockNumber, TrieMap[ViewNumber, SignedMessage[M]]],
  )(
      message: SignedMessage[M]
  ): Try[Unit] = {
    map
      .putIfAbsent(
        message.message.blockMetadata.blockNumber,
        new TrieMap[ViewNumber, SignedMessage[M]](),
      )
      .discard
    putIfAbsent(
      store = map(message.message.blockMetadata.blockNumber),
      key = message.message.viewNumber,
      value = message,
      storeType = s"$storeType(${message.message.blockMetadata.blockNumber})",
    )
  }
  private def putIfAbsent[K, V](
      store: TrieMap[K, V],
      key: K,
      value: V,
      storeType: String,
  ): Try[Unit] = store.putIfAbsent(key, value) match {
    case None =>
      Success(())
    case Some(v) =>
      if (v == value) Success(())
      else
        Failure(
          new RuntimeException(
            s"Updating existing entry in $storeType is illegal: key:$key, oldValue: $v, newValue: $value"
          )
        )
  }

  override def startEpoch(
      epoch: EpochInfo
  )(implicit traceContext: TraceContext): E#FutureUnlessShutdownT[Unit] =
    createFuture(startEpochActionName(epoch)) { () =>
      putIfAbsent(
        store = epochs,
        key = epoch.number,
        value = EpochStatus(epoch, isInProgress = true),
        storeType = "epochs",
      )
    }

  override def completeEpoch(
      epochNumber: EpochNumber
  )(implicit traceContext: TraceContext): E#FutureUnlessShutdownT[Unit] =
    createFuture(completeEpochActionName(epochNumber)) { () =>
      // delete all in-progress messages after an epoch ends and before we start adding new messages in the new epoch
      prePreparesMap.clear()
      preparesMap.clear()
      viewChangesMap.clear()
      newViewsMap.clear()
      Success(
        epochs
          .updateWith(epochNumber) {
            case Some(EpochStatus(epoch, _)) => Some(EpochStatus(epoch, isInProgress = false))
            case None => None
          }
          .discard
      )
    }

  override def latestEpoch(includeInProgress: Boolean)(implicit
      traceContext: TraceContext
  ): E#FutureUnlessShutdownT[EpochStore.Epoch] = createFuture(latestCompletedEpochActionName) {
    () =>
      Try {
        val epochInfo = epochs
          .filter { case (_, EpochStatus(_, isInProgress)) =>
            includeInProgress || (!includeInProgress && !isInProgress)
          }
          .maxByOption { case (epochNumber, _) => epochNumber }
          .map { case (_, EpochStatus(epochInfo, _)) => epochInfo }
          .getOrElse(Genesis.GenesisEpochInfo)
        val commits =
          blocks
            .get(epochInfo.lastBlockNumber)
            .fold[Seq[SignedMessage[Commit]]](Seq.empty)(_.commits)
        Epoch(epochInfo, commits)
      }
  }

  override def addPrePrepare(
      prePrepare: SignedMessage[PrePrepare]
  )(implicit traceContext: TraceContext): E#FutureUnlessShutdownT[Unit] =
    createFuture(addPrePrepareActionName(prePrepare)) { () =>
      addSingleMessageToMap("pre-prepares", prePreparesMap)(prePrepare)
    }

  override def addPrepares(
      prepares: Seq[SignedMessage[Prepare]]
  )(implicit traceContext: TraceContext): E#FutureUnlessShutdownT[Unit] =
    createFuture(addPreparesActionName) { () =>
      prepares.headOption.fold[Try[Unit]](Success(())) { head =>
        preparesMap
          .putIfAbsent(
            head.message.blockMetadata.blockNumber,
            TrieMap[ViewNumber, Seq[SignedMessage[Prepare]]](),
          )
          .discard
        putIfAbsent(
          store = preparesMap(head.message.blockMetadata.blockNumber),
          key = head.message.viewNumber,
          value = prepares,
          storeType = s"prepares(${head.message.blockMetadata.blockNumber})",
        )
      }
    }

  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  override def addViewChangeMessage[M <: ConsensusMessage.PbftViewChangeMessage](
      viewChangeMessage: SignedMessage[M]
  )(implicit traceContext: TraceContext): E#FutureUnlessShutdownT[Unit] =
    createFuture(addViewChangeMessageActionName(viewChangeMessage)) { () =>
      viewChangeMessage.message match {
        case _: ViewChange =>
          addSingleMessageToMap("view-changes", viewChangesMap)(
            viewChangeMessage.asInstanceOf[SignedMessage[ViewChange]]
          )
        case _: NewView =>
          addSingleMessageToMap("new-views", newViewsMap)(
            viewChangeMessage.asInstanceOf[SignedMessage[NewView]]
          )
      }
    }

  override def addOrderedBlock(
      prePrepare: SignedMessage[PrePrepare],
      commitMessages: Seq[SignedMessage[Commit]],
  )(implicit
      traceContext: TraceContext
  ): E#FutureUnlessShutdownT[Unit] = {
    val epochNumber = prePrepare.message.blockMetadata.epochNumber
    val blockNumber = prePrepare.message.blockMetadata.blockNumber
    createFuture(addOrderedBlockActionName(epochNumber, blockNumber)) { () =>
      // we can drop the in progress messages for this block
      preparesMap.remove(blockNumber).discard
      prePreparesMap.remove(blockNumber).discard

      putIfAbsent(
        store = blocks,
        key = blockNumber,
        value = CompletedBlock(prePrepare, commitMessages),
        storeType = "orderedBlocks",
      )
    }
  }

  override def loadEpochProgress(activeEpochInfo: EpochInfo)(implicit
      traceContext: TraceContext
  ): E#FutureUnlessShutdownT[EpochInProgress] =
    createFuture(loadEpochProgressActionName(activeEpochInfo)) { () =>
      Try {
        val blocksInEpoch = blocks.view
          .filter { case (_, CompletedBlock(prePrepare, _)) =>
            prePrepare.message.blockMetadata.epochNumber == activeEpochInfo.number
          }
          .values
          .toSeq
          .map { case CompletedBlock(prePrepare, commits) =>
            Block(
              prePrepare.message.blockMetadata.epochNumber,
              prePrepare.message.blockMetadata.blockNumber,
              CommitCertificate(prePrepare, commits),
            )
          }
          .sortWith(_.blockNumber < _.blockNumber)

        val preparesForIncompleteBlocks = preparesMap.view
          .filter { case (blockNumber, _) =>
            blockNumber >= activeEpochInfo.startBlockNumber
          }
          .values
          .flatMap(_.values)
          .flatten
          .toList
          .sortBy(pbftEventSortData)

        def messagesForIncompleteBlocks[M <: PbftNetworkMessage](
            mapView: MapView[BlockNumber, TrieMap[ViewNumber, SignedMessage[M]]]
        ): List[SignedMessage[PbftNetworkMessage]] = mapView
          .filter { case (blockNumber, _) =>
            blockNumber >= activeEpochInfo.startBlockNumber
          }
          .values
          .flatMap(_.values)
          .toList
          .sortBy(pbftEventSortData)

        val prePreparesForIncompleteBlocks = messagesForIncompleteBlocks(prePreparesMap.view)
        val viewChangesForIncompleteSegments = messagesForIncompleteBlocks(viewChangesMap.view)
        val newViewsForIncompleteSegments = messagesForIncompleteBlocks(newViewsMap.view)

        val pbftMessagesForIncompleteBlocks =
          viewChangesForIncompleteSegments ++ newViewsForIncompleteSegments ++
            (prePreparesForIncompleteBlocks: List[
              SignedMessage[PbftNetworkMessage]
            ]) ++ preparesForIncompleteBlocks
        EpochInProgress(blocksInEpoch, pbftMessagesForIncompleteBlocks)
      }
    }

  override def loadPrePreparesForCompleteBlocks(
      startEpochNumberInclusive: EpochNumber,
      endEpochNumberInclusive: EpochNumber,
  )(implicit
      traceContext: TraceContext
  ): E#FutureUnlessShutdownT[Seq[SignedMessage[PrePrepare]]] =
    createFuture(loadPrePreparesActionName(startEpochNumberInclusive, endEpochNumberInclusive)) {
      () =>
        val prePrepares = blocks.view
          .filter { case (_, CompletedBlock(prePrepare, _)) =>
            val epochNumber = prePrepare.message.blockMetadata.epochNumber
            epochNumber >= startEpochNumberInclusive && epochNumber <= endEpochNumberInclusive
          }
          .values
          .map(_.prePrepare)
          .toSeq
        Success(prePrepares)
    }

  override def loadOrderedBlocks(
      initialBlockNumber: BlockNumber
  )(implicit traceContext: TraceContext): E#FutureUnlessShutdownT[Seq[OrderedBlockForOutput]] =
    createFuture(loadOrderedBlocksActionName(initialBlockNumber)) { () =>
      blocks.view
        .filter { case (blockNumber, _) =>
          blockNumber >= initialBlockNumber
        }
        .values
        .foldLeft[Try[Seq[OrderedBlockForOutput]]](Success(Seq.empty)) {
          case (partialResult, next) =>
            partialResult.flatMap { orderedBlocks =>
              val CompletedBlock(prePrepare, _) = next
              val epochNumber = prePrepare.message.blockMetadata.epochNumber
              val blockNumber = prePrepare.message.blockMetadata.blockNumber
              epochs.get(epochNumber) match {
                case Some(EpochStatus(epochInfo, _)) =>
                  val isBlockLastInEpoch =
                    epochInfo.lastBlockNumber == blockNumber
                  Success(
                    orderedBlocks :+
                      OrderedBlockForOutput(
                        OrderedBlock(
                          prePrepare.message.blockMetadata,
                          prePrepare.message.block.proofs,
                          prePrepare.message.canonicalCommitSet,
                        ),
                        prePrepare.from,
                        isBlockLastInEpoch,
                        OrderedBlockForOutput.Mode.FromConsensus,
                      )
                  )
                case None =>
                  Failure(
                    new RuntimeException(
                      s"Epoch $epochNumber not found for block $blockNumber"
                    )
                  )
              }
            }
        }
    }
}

private object GenericInMemoryEpochStore {

  final case class EpochStatus(epochInfo: EpochInfo, isInProgress: Boolean)

  final case class CompletedBlock(
      prePrepare: SignedMessage[PrePrepare],
      commits: Seq[SignedMessage[Commit]],
  )

  def pbftEventSortData(pbftEvent: SignedMessage[PbftNetworkMessage]): (BlockNumber, ViewNumber) =
    (pbftEvent.message.blockMetadata.blockNumber, pbftEvent.message.viewNumber)
}

final class InMemoryEpochStore extends GenericInMemoryEpochStore[PekkoEnv] {
  override protected def createFuture[T](action: String)(
      value: () => Try[T]
  ): PekkoFutureUnlessShutdown[T] =
    PekkoFutureUnlessShutdown(action, FutureUnlessShutdown.fromTry(value()))
}
