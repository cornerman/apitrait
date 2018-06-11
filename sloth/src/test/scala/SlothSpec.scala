package test.sloth

import org.scalatest._
import scala.concurrent.Future
import scala.util.control.NonFatal
import sloth._
import cats.implicits._

import chameleon.ext.boopickle._
import boopickle.Default._
import java.nio.ByteBuffer
import scala.concurrent.ExecutionContext.Implicits.global
// import chameleon.ext.circe._
// import io.circe._, io.circe.syntax._, io.circe.generic.auto._

object Pickling {
  type PickleType = ByteBuffer
  // type PickleType = String
}
import Pickling._

trait EmptyApi
object EmptyApi extends EmptyApi

//shared
trait Api[Result[_]] {
  def simple: Result[Int]
  def fun(a: Int, b: String = "drei"): Result[Int]
  def fun2(a: Int, b: String): Result[Int]
  def multi(a: Int)(b: Int): Result[Int]
}

//server
object ApiImplFuture extends Api[Future] {
  def simple: Future[Int] = Future.successful(1)
  def fun(a: Int, b: String): Future[Int] = Future.successful(a)
  def fun2(a: Int, b: String): Future[Int] = Future.successful(a)
  def multi(a: Int)(b: Int): Future[Int] = Future.successful(a)
}
//or
case class ApiResult[T](event: String, result: Future[T])
object ApiImplResponse extends Api[ApiResult] {
  def simple: ApiResult[Int] = ApiResult("peter", Future.successful(1))
  def fun(a: Int, b: String): ApiResult[Int] = ApiResult("hans", Future.successful(a))
  def fun2(a: Int, b: String): ApiResult[Int] = ApiResult("hans", Future.successful(a))
  def multi(a: Int)(b: Int): ApiResult[Int] = ApiResult("hans", Future.successful(a + b))
}
//or
object TypeHelper { type ApiResultFun[T] = Int => ApiResult[T] }
import TypeHelper._
object ApiImplFunResponse extends Api[ApiResultFun] {
  def simple: ApiResultFun[Int] = i => ApiResult("peter", Future.successful(i))
  def fun(a: Int, b: String): ApiResultFun[Int] = i => ApiResult("hans", Future.successful(a + i))
  def fun2(a: Int, b: String): ApiResultFun[Int] = i => ApiResult("hans", Future.successful(a + i))
  def multi(a: Int)(b: Int): ApiResultFun[Int] = i => ApiResult("hans", Future.successful(a + b + i))
}


// multiple result
trait Multi {
  type Single[T]
  type Stream[T]
}
trait MixedApi[M <: Multi] {
  def simple: M#Single[Int]
  // def other: Apply[Option, Int]
}
object TApply {
  type Applier[R[_], T] = R[T]
}
sealed trait ApiFT[T]
sealed trait ApiF[R[_], T] extends ApiFT[T]
object ApiF {
  case class F[T](future: Future[T]) extends ApiF[Future, T]
  case class O[T](option: Option[T]) extends ApiF[Option, T]
}
object ApiFT {
  implicit val functor = cats.derived.semi.functor[ApiFT]
}

object ApiMulti extends Multi {
  type Single[T] = ApiF.F[T]
  type Stream[T] = ApiF.O[T]
}

object MixedApiImpl extends MixedApi[ApiMulti.type] {
  // def simple: ApiMulti.Single[Int] = Future.successful(1)
  def simple: ApiMulti.Single[Int] = ApiF.F(Future.successful(1))
  // def other: ApiF[Option, Int] = ApiF.O(Option(2))
}

class SlothSpec extends AsyncFreeSpec with MustMatchers {
  import cats.derived.auto.functor._

  "run simple" in {
    object Backend {
      val router = Router[PickleType, ApiFT]
        // .route(EmptyApi)
        .route[MixedApi[ApiMulti.type]](MixedApiImpl)
    }

    object Frontend {
      object Transport extends RequestTransport[PickleType, Future] {
        override def apply(request: Request[PickleType]): Future[PickleType] =
          Backend.router(request).toEither match {
            case Right(ApiF.F(future)) => future
            case Right(ApiF.O(option)) => option.map(Future.successful).getOrElse(Future.failed(new Exception))
            case Left(err) => Future.failed(new Exception(err.toString))
          }
      }

      val client = Client[PickleType, Future, ClientException](Transport)
      val api = client.wire[Api[Future]]
      val emptyApi = client.wire[EmptyApi]
    }

    Frontend.api.fun(1).map(_ mustEqual 1)
  }

 // "run different result types" in {
 //    import cats.data.EitherT

 //    sealed trait ApiError
 //    case class SlothClientError(failure: ClientFailure) extends ApiError
 //    case class SlothServerError(failure: ServerFailure) extends ApiError
 //    case class UnexpectedError(msg: String) extends ApiError

 //    implicit def clientFailureConvert = new ClientFailureConvert[ApiError] {
 //      def convert(failure: ClientFailure) = SlothClientError(failure)
 //    }

 //    type ClientResult[T] = EitherT[Future, ApiError, T]

 //    object Backend {
 //      val router = Router[PickleType, ApiResult]
 //        .route[Api[ApiResult]](ApiImplResponse)
 //    }

 //    object Frontend {
 //      object Transport extends RequestTransport[PickleType, ClientResult] {
 //        override def apply(request: Request[PickleType]): ClientResult[PickleType] = EitherT(
 //          Backend.router(request).toEither match {
 //            case Right(ApiResult(event@_, result)) =>
 //              result.map(Right(_)).recover { case NonFatal(t) => Left(UnexpectedError(t.getMessage)) }
 //            case Left(err) => Future.successful(Left(SlothServerError(err)))
 //          })
 //      }

 //      val client = Client[PickleType, ClientResult, ApiError](Transport)
 //      val api = client.wire[Api[ClientResult]]
 //    }

 //    Frontend.api.fun2(1, "AAAA")
 //    Frontend.api.multi(11)(3)
 //    Frontend.api.fun(1).value.map(_.right.get mustEqual 1)
 //  }

 // "run different result types with fun" in {

 //    object Backend {
 //      val router = Router[PickleType, ApiResultFun]
 //        .route[Api[ApiResultFun]](ApiImplFunResponse)
 //    }

 //    object Frontend {
 //      object Transport extends RequestTransport[PickleType, Future] {
 //        override def apply(request: Request[PickleType]): Future[PickleType] =
 //          Backend.router(request).toEither.fold(err => Future.failed(new Exception(err.toString)), _(10).result)
 //      }

 //      val client = Client[PickleType, Future, ClientException](Transport)
 //      val api = client.wire[Api[Future]]
 //    }

 //    Frontend.api.fun(1).map(_ mustEqual 11)
 //  }
}
