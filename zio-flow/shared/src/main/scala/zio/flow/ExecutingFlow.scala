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

import zio.flow.internal.DurablePromise
import zio.schema.Schema

final case class ExecutingFlow[+E, +A](id: FlowId, result: DurablePromise[_, _])

object ExecutingFlow {
  implicit def schema[E, A]: Schema[ExecutingFlow[E, A]] =
    Schema.CaseClass2[FlowId, DurablePromise[Either[Throwable, E], A], ExecutingFlow[E, A]](
      Schema.Field("id", Schema[FlowId]),
      Schema.Field("result", Schema[DurablePromise[Either[Throwable, E], A]]),
      { case (id, promise) => ExecutingFlow(id, promise) },
      (ef: ExecutingFlow[E, A]) => ef.id,
      (ef: ExecutingFlow[E, A]) => ef.result.asInstanceOf[DurablePromise[Either[Throwable, E], A]]
    )
}
