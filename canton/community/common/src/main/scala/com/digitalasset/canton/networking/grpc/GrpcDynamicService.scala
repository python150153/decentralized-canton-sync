// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.networking.grpc

import cats.syntax.either.*
import com.digitalasset.canton.discard.Implicits.DiscardOps
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.*

import java.util.concurrent.atomic.AtomicReference
import scala.jdk.CollectionConverters.*

/** Stand up a gRPC service stub which will direct to an instance set dynamically at runtime if available,
  * or return a UNAVAILBLE status if not set.
  *
  * Use setInstance and clear to update the backing service instance.
  *
  * There will be a performance penalty as the method on the backing service is looked up on each call,
  * however this is likely fine for services that are rarely used like admin commands.
  *
  * Currently intentionally works with typeless [[io.grpc.ServiceDescriptor]] instances to allow use with
  * stubs generated by [[StaticGrpcServices]]. Validation could be added to ensure full names of services and methods match.
  */
class GrpcDynamicService(
    descriptor: ServiceDescriptor,
    serviceUnavailableMessage: String,
    protected val loggerFactory: NamedLoggerFactory,
) extends NamedLogging {
  // make sure the descriptors and companion we've been given likely match
  private val serviceDescriptorRef = new AtomicReference[Option[ServerServiceDefinition]](None)

  def setInstance(
      descriptor: ServerServiceDefinition
  )(implicit traceContext: TraceContext): Unit =
    serviceDescriptorRef.getAndSet(Some(descriptor)) foreach { _ =>
      logger.warn("Service was already set")
    }

  def clear(): Unit =
    serviceDescriptorRef.set(None)

  val serviceDescriptor: ServerServiceDefinition = {
    val builder = ServerServiceDefinition.builder(descriptor)

    descriptor.getMethods.asScala.foreach { method =>
      builder
        .addMethod(ServerMethodDefinition.create(method, mkCall(method)))
        .discard[ServerServiceDefinition.Builder]
    }

    builder.build()
  }

  /** Creates a call handler that immediately closes the server call with the generated status. */
  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  private def mkCall[Req, Res](method: MethodDescriptor[_, _]): ServerCallHandler[Req, Res] =
    (call: ServerCall[Req, Res], metadata: Metadata) => {
      val methodE = for {
        descriptor <- serviceDescriptorRef
          .get()
          .toRight(serviceUnavailableMessage)
          .leftMap(Status.UNAVAILABLE.withDescription)
        method <- Option(descriptor.getMethod(method.getFullMethodName))
          .toRight(
            s"Service available but method [${method.getBareMethodName}] not found"
          ) // this likely indicates that the incorrect service is wired
          .leftMap(Status.INTERNAL.withDescription)
      } yield method

      methodE.fold(
        failingHandler(call, _),
        { method =>
          val handler = method.getServerCallHandler.asInstanceOf[ServerCallHandler[Req, Res]]
          handler.startCall(call, metadata)
        },
      )
    }

  private def failingHandler[Req, Res](
      call: ServerCall[Req, Res],
      status: Status,
  ): ServerCall.Listener[Req] =
    new ServerCall.Listener[Req] {
      call.close(status, new Metadata())
    }
}
