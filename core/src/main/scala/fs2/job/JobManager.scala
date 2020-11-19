/*
 * Copyright 2020 Precog Data
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

package fs2
package job

import cats.effect.{Concurrent, Resource, Timer}
import cats.implicits._

import fs2.concurrent.{Queue, SignallingRef}

import scala.concurrent.duration._
import scala.util.{Left, Right}
import scala.{Boolean, Int, List, Option, None, Nothing, Some, StringContext, Unit}

import java.lang.IllegalStateException
import java.util.concurrent.{ConcurrentHashMap}

/**
 * A coordination mechanism for parallel job management. This
 * structure provides mechanisms for aggregated notifications
 * from jobs, deterministic cancelation, "fire and forget"
 * submission, and sidecar-style cancelation of direct streams.
 */
final class JobManager[F[_]: Concurrent: Timer, I, N] private (
    notificationsQ: Queue[F, Option[(I, N)]],
    eventQ: Queue[F, Option[Event[I]]],
    dispatchQ: Queue[F, Stream[F, Nothing]]) {

  import CompatConverters.All._
  import JobManager._

  private[this] val meta: ConcurrentHashMap[I, Context[F]] = new ConcurrentHashMap[I, Context[F]]

  val notifications: Stream[F, (I, N)] = notificationsQ.dequeue.unNoneTerminate
  val events: Stream[F, Event[I]] = eventQ.dequeue.unNoneTerminate

  /**
   * Submits a job for parallel execution at the earliest possible moment.
   * If the job submission queue is full, this function will asynchronously
   * block until space is available. Note that a job is visible as Pending
   * immediately upon submission, even when space is unavailable.
   *
   * Attempting to submit a job with the same id as a pre-existing job will
   * produce false.
   */
  def submit[R](job: Job[F, I, N, R]): F[Boolean] = {
    val run = job.run
      .map(_.swap.toOption)
      .unNone
      .tupleLeft(job.id)
      .evalMap(t => notificationsQ.enqueue1(Some(t)))
      .drain

    val putStatusF = Concurrent[F] delay {
      val attempt = Context[F](Status.Pending, None)

      Option(meta.putIfAbsent(job.id, attempt)).isEmpty
    }

    putStatusF flatMap { s =>
      if (s)
        epochMillisNow.flatMap(ts =>
          dispatchQ.enqueue1(managementMachinery(job.id, run, ts, false)).as(true))
      else
        Concurrent[F].pure(false)
    }
  }

  /**
   * Like submit, but produces a managed stream equal to the job's
   * run stream. Notifications are stripped and routed to the
   * shared notifications source. The resulting stream is subject
   * to remote cancelation, the same as any submitted job.
   *
   * Attempting to tap a job with a pre-existing id will produce
   * an error.
   */
  def tap[R](job: Job[F, I, N, R]): Stream[F, R] = {
    // TODO failure isn't quite deterministic here when job already exists
    val run = job.run evalMap {
      case Left(n) => notificationsQ.enqueue1(Some((job.id, n))).as(None: Option[R])
      case Right(r) => Concurrent[F].pure(Some(r): Option[R])
    }

    Stream.eval(epochMillisNow).flatMap(ts =>
      managementMachinery(job.id, run.unNone, ts, true))
  }

  /**
   * Returns the currently-running jobs by ID.
   */
  def jobIds: F[List[I]] =
    Concurrent[F].delay(meta.keys.asScala.toList)

  /**
   * Returns the last n notifications emitted by jobs. Returns None if
   * notifications are not available.
   */
  def lastNotifications(n: Int): F[Option[List[(I, N)]]] =
    maybeDequeueN(notificationsQ, n)

  /**
   * Returns the last n `Event`s. Returns None if events are
   * not available.
   */
  def lastEvents(n: Int): F[Option[List[Event[I]]]] =
    maybeDequeueN(eventQ, n)

  /**
   * Cancels the job by id. If the job does not exist, this call
   * will be ignored.
   */
  def cancel(id: I): F[Unit] = {
    Concurrent[F].delay(meta.get(id)) flatMap {
      case Context(Status.Running, Some(cancelF)) =>
        cancelF

      case old @ Context(Status.Pending, _) =>
        val killer = Context[F](Status.Canceled, None)
        Concurrent[F].delay(meta.replace(id, old, killer)) flatMap { success =>
          if (success)
            Concurrent[F].unit
          else
            cancel(id)
        }

      case _ => Concurrent[F].unit
    }
  }

  /**
   * Returns the status of a given job id, if known.
   */
  def status(id: I): F[Option[Status]] =
    Concurrent[F].delay(Option(meta.get(id)).map(_.status))

  private def shutdown: F[Unit] = for {
    _ <- Concurrent[F].delay(meta.clear())
    // terminate queues concurrently to always shutdown immediately
    _ <- Concurrent[F].start(notificationsQ.enqueue1(None))
    _ <- Concurrent[F].start(eventQ.enqueue1(None))
  } yield ()

  private def maybeDequeueN[A](q: Queue[F, Option[A]], n: Int): F[Option[List[A]]] =
    q.tryDequeueChunk1(n).map(_.map(_.toList.unite))

  private[this] def managementMachinery[A](
      id: I,
      in: Stream[F, A],
      startingTime: Timestamp,
      ignoreAbsence: Boolean): Stream[F, A] = {
    Stream force {
      SignallingRef[F, Boolean](false) map { s =>
        val unregisterJob =
          Concurrent[F].delay(meta.remove(id)).void

        // None indicates the job should not be started, otherwise returns the
        // context expected prior to termination
        lazy val frontF: F[Option[Context[F]]] =
          Concurrent[F].delay(Option(meta.get(id))) flatMap {
            case Some(old @ Context(Status.Pending, _)) =>
              val running = Context(Status.Running, Some(s.set(true)))

              val casF = Concurrent[F] delay {
                meta.replace(id, old, running)
              }

              casF flatMap { result =>
                if (result)
                  Concurrent[F].pure(Some(running))
                else
                  frontF
              }

            case Some(Context(Status.Canceled, _)) =>
              unregisterJob.as(None)

            case Some(Context(Status.Running, _)) =>
              Concurrent[F].raiseError(new IllegalStateException(s"A job with id '$id' is already running!"))    // TODO

            case None =>
              if (ignoreAbsence) {
                val attempt = Context(Status.Running, Some(s.set(true)))

                val casF = Concurrent[F] delay {
                  Option(meta.putIfAbsent(id, attempt)).isEmpty
                }

                casF flatMap { result =>
                  if (result)
                    Concurrent[F].pure(Some(attempt))
                  else
                    frontF
                }
              } else {
                Concurrent[F].pure(None)
              }
          }

        def notifyTerminated(f: FiniteDuration => Event[I]): F[Unit] =
          for {
            ts <- epochMillisNow
            duration = ts.epoch - startingTime.epoch
            _ <- unregisterJob
            _ <- eventQ.enqueue1(Some(f(duration)))
          } yield ()

        val completeF =
          notifyTerminated(Event.Completed(id, startingTime, _))

        val reported = in ++ Stream.eval_(completeF)

        val handled =
          reported.handleErrorWith(ex =>
            Stream.eval_(notifyTerminated(Event.Failed(id, startingTime, _, ex))))

        // Conditional `remove` so we only cleanup if the job is still ours
        Stream.bracket(frontF)(_.traverse_(c => Concurrent[F].delay(meta.remove(id, c))))
          .flatMap(_.fold[Stream[F, A]](Stream.empty)(_ => handled.interruptWhen(s)))
      }
    }
  }

  private def epochMillisNow: F[Timestamp] =
    Timer[F].clock.realTime(MILLISECONDS)
      .map(FiniteDuration(_, MILLISECONDS))
      .map(Timestamp(_))
}

object JobManager {
  def apply[F[_]: Concurrent: Timer, I, N](
      jobLimit: Int = 100,
      notificationsLimit: Int = 10,
      eventsLimit: Int = 10,
      jobConcurrency: Int = 100)
      : Resource[F, JobManager[F, I, N]] = {

    val s = for {
      notificationsQ <- Stream.eval(Queue.bounded[F, Option[(I, N)]](notificationsLimit))
      eventQ <- Stream.eval(Queue.circularBuffer[F, Option[Event[I]]](eventsLimit))
      dispatchQ <- Stream.eval(Queue.bounded[F, Stream[F, Nothing]](jobLimit))

      initF = Concurrent[F] delay {
        new JobManager[F, I, N](
          notificationsQ,
          eventQ,
          dispatchQ)
      }

      jm <- Stream.bracketWeak(initF)(_.shutdown)
      back <- Stream.emit(jm).concurrently(dispatchQ.dequeue.parJoin(jobConcurrency))
    } yield back

    s.compile.resource.lastOrError
  }

  private final case class Context[F[_]](status: Status, cancel: Option[F[Unit]])
}
