package zio.flow

import zio.schema.Schema

import scala.util.Try

class RemoteEitherSyntax[A, B](val self: Remote[Either[A, B]]) {

  final def handleEither[C](left: Remote[A] => Remote[C], right: Remote[B] => Remote[C]): Remote[C] =
    Remote.FoldEither(self, left, right)

  final def handleEitherM[R, E, C](
    left: Remote[A] => ZFlow[R, E, C],
    right: Remote[B] => ZFlow[R, E, C]
  ): ZFlow[R, E, C] = ZFlow.unwrap(handleEither(left.andThen(Remote(_)), right.andThen(Remote(_))))

  final def merge(implicit ev: Either[A, B] <:< Either[B, B]): Remote[B] =
    Remote.FoldEither[B, B, B](self.widen[Either[B, B]], identity(_), identity(_))

  final def isLeft: Remote[Boolean] =
    handleEither(_ => Remote(true), _ => Remote(false))

  final def isRight: Remote[Boolean] =
    handleEither(_ => Remote(false), _ => Remote(true))

  final def swap: Remote[Either[B, A]] = Remote.SwapEither(self)

  final def contains[B1 >: B](elem: Remote[B1]): Remote[Boolean] =
    handleEither(_ => Remote(false), Remote.Equal(_, elem))

  final def forall(f: Remote[B] => Remote[Boolean]): Remote[Boolean] = handleEither(_ => Remote(true), f)

  final def exists(f: Remote[B] => Remote[Boolean]): Remote[Boolean] = handleEither(_ => Remote(false), f)

  final def getOrElse(or: => Remote[B]): Remote[B] = handleEither(_ => or, identity(_))

  final def orElse[A1 >: A, B1 >: B](or: => Remote[Either[A1, B1]]): Remote[Either[A1, B1]] =
    handleEither(_ => or, _ => self)

  final def filterOrElse[A1 >: A](p: Remote[B] => Remote[Boolean], zero: => Remote[A1])(implicit
    bSchema: Schema[B] // FIXME Actually easily retrieved from Remote[B]
  ): Remote[Either[A1, B]] =
    handleEither(
      _ => self,
      a =>
        Remote.Branch(
          p(a),
          self,
          Remote.Either0(Left((zero, bSchema)))
        )
    )

  final def toOption: Remote[Option[B]] = handleEither(_ => Remote(None), Remote.Some0(_))

  def toTry(implicit ev: A <:< Throwable): Try[B] = ???
}

object RemoteEitherSyntax {

  def collectAll[E, A](
    values: Remote[List[Either[E, A]]]
  )(implicit eSchema: Schema[E], aSchema: Schema[A]): Remote[Either[E, List[A]]] = {

    def combine(
      eitherList: RemoteEitherSyntax[E, List[A]],
      either: RemoteEitherSyntax[E, A]
    ): Remote[Either[E, List[A]]] =
      eitherList.handleEither(
        _ => eitherList.self,
        remoteList => combine2(either, remoteList).self
      )

    def combine2[U, V](either: RemoteEitherSyntax[U, V], remoteList: Remote[List[V]])(implicit
      uSchema: Schema[U],
      vSchema: Schema[V]
    ): RemoteEitherSyntax[U, List[V]] =
      either.handleEither(
        u => Remote.Either0(Left((u, Schema.list(vSchema)))),
        v => Remote.Either0(Right((uSchema, Remote.Cons(remoteList, v))))
      )

    // FIXME Expecting Schema for Either, but got a Schema for Transformm.
    values.fold(Remote(Right(Nil)): Remote[Either[E, List[A]]])((el, e) =>
      combine(el, e)
    )
  }
}
