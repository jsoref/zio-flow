/*
 * Copyright 2021-2022 John A. De Goes and the ZIO Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package zio.flow

import zio.ZIO
import zio.flow.internal._
import zio.schema.DynamicValue
import zio.stm.TMap

import java.io.IOException
import java.util.UUID

trait RemoteContext {
  def setVariable(name: RemoteVariableName, value: DynamicValue): ZIO[Any, ExecutorError, Unit]
  def getVariable(name: RemoteVariableName): ZIO[Any, ExecutorError, Option[DynamicValue]]
  def getLatestTimestamp(name: RemoteVariableName): ZIO[Any, ExecutorError, Option[Timestamp]]
  def dropVariable(name: RemoteVariableName): ZIO[Any, ExecutorError, Unit]
}

object RemoteContext {
  def generateFreshVariableName: RemoteVariableName =
    RemoteVariableName.unsafeMake(s"p_${UUID.randomUUID()}")

  def setVariable(name: RemoteVariableName, value: DynamicValue): ZIO[RemoteContext, ExecutorError, Unit] =
    ZIO.serviceWithZIO(_.setVariable(name, value))
  def getVariable(name: RemoteVariableName): ZIO[RemoteContext, ExecutorError, Option[DynamicValue]] =
    ZIO.serviceWithZIO(_.getVariable(name))
  def dropVariable(name: RemoteVariableName): ZIO[RemoteContext, ExecutorError, Unit] =
    ZIO.serviceWithZIO(_.dropVariable(name))

  private final case class InMemory(
    store: TMap[RemoteVariableName, DynamicValue]
  ) extends RemoteContext {
    override def setVariable(name: RemoteVariableName, value: DynamicValue): ZIO[Any, ExecutorError, Unit] =
      store.put(name, value).commit

    override def getVariable(name: RemoteVariableName): ZIO[Any, ExecutorError, Option[DynamicValue]] =
      store
        .get(name)
        .commit

    override def getLatestTimestamp(name: RemoteVariableName): ZIO[Any, ExecutorError, Option[Timestamp]] =
      store.get(name).commit.map(_.map(_ => Timestamp(0L)))

    override def dropVariable(name: RemoteVariableName): ZIO[Any, ExecutorError, Unit] =
      store.delete(name).commit
  }

  def inMemory: ZIO[Any, Nothing, RemoteContext] =
    (for {
      vars <- TMap.empty[RemoteVariableName, DynamicValue]
    } yield InMemory(vars)).commit

  private final case class Persistent(
    virtualClock: VirtualClock,
    remoteVariableStore: RemoteVariableKeyValueStore,
    executionEnvironment: ExecutionEnvironment,
    scope: RemoteVariableScope,
    scopeMap: TMap[RemoteVariableName, RemoteVariableScope]
  ) extends RemoteContext {

    override def setVariable(name: RemoteVariableName, value: DynamicValue): ZIO[Any, ExecutorError, Unit] =
      scopeMap.getOrElse(name, scope).commit.flatMap { variableScope =>
        virtualClock.current.flatMap { timestamp =>
          val serializedValue = executionEnvironment.serializer.serialize(value)
          remoteVariableStore
            .put(
              name,
              if (scope.transactionId.isDefined) scope else variableScope,
              serializedValue,
              timestamp
            )
            .unit
        }
      }

    override def getVariable(name: RemoteVariableName): ZIO[Any, ExecutorError, Option[DynamicValue]] =
      virtualClock.current.flatMap { timestamp =>
        remoteVariableStore
          .getLatest(name, scope, Some(timestamp))
          .flatMap {
            case Some((serializedValue, variableScope)) =>
              scopeMap.put(name, variableScope).commit.zipRight {
                ZIO
                  .fromEither(executionEnvironment.deserializer.deserialize[DynamicValue](serializedValue))
                  .map(Some(_))
                  .orDieWith(msg => new IOException(s"Failed to deserialize remote variable $name: $msg"))
              }
            case None =>
              ZIO.none
          }
      }

    override def getLatestTimestamp(name: RemoteVariableName): ZIO[Any, ExecutorError, Option[Timestamp]] =
      remoteVariableStore
        .getLatestTimestamp(name, scope)
        .flatMap {
          case Some((timestamp, variableScope)) =>
            scopeMap.put(name, variableScope).commit.as(Some(timestamp))
          case None => ZIO.none
        }

    override def dropVariable(name: RemoteVariableName): ZIO[Any, ExecutorError, Unit] =
      remoteVariableStore.delete(name, scope)
  }

  def persistent(
    scope: RemoteVariableScope
  ): ZIO[
    RemoteVariableKeyValueStore with ExecutionEnvironment with VirtualClock with DurableLog,
    Nothing,
    RemoteContext
  ] =
    for {
      virtualClock         <- ZIO.service[VirtualClock]
      remoteVariableStore  <- ZIO.service[RemoteVariableKeyValueStore]
      executionEnvironment <- ZIO.service[ExecutionEnvironment]
      scopeMap             <- TMap.empty[RemoteVariableName, RemoteVariableScope].commit
    } yield Persistent(virtualClock, remoteVariableStore, executionEnvironment, scope, scopeMap)
}
