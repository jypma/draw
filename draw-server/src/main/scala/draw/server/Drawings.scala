package draw.server

import scala.collection.Searching.{Found, InsertionPoint}

import zio.stream.{SubscriptionRef, ZStream}
import zio.{Clock, IO, Ref, ZIO, ZLayer}

import draw.data.drawcommand.{ContinueScribble, CreateIcon, DeleteObject, DrawCommand, MoveObject, StartScribble, LabelObject}
import draw.data.drawevent.{DrawEvent, DrawingCreated, IconCreated, ObjectDeleted, ObjectMoved, ScribbleContinued, ScribbleStarted, ObjectLabelled}
import draw.data.point.Point

import Drawings.DrawingError
import java.util.UUID

trait Drawing {
  def perform(command: DrawCommand): ZIO[Any, DrawingError, Unit]
  def events: ZStream[Any, DrawingError, DrawEvent]
  def eventsAfter(sequenceNr: Long): ZStream[Any, DrawingError, DrawEvent]
  def version: ZIO[Any, DrawingError, Long]
}

trait Drawings {
  def getDrawing(id: UUID): IO[DrawingError, Drawing]
}

case class DrawingStorage(events: Seq[DrawEvent] = Seq.empty) {
  def size = events.size
  def add(event: DrawEvent) = copy(events = events :+ event.withSequenceNr(events.size + 1))
  def :+(event: DrawEvent) = add(event)
}

case class DrawingInMemory(storage: SubscriptionRef[DrawingStorage]) extends Drawing {

  override def perform(command: DrawCommand): ZIO[Any, DrawingError, Unit] = {
    for {
      now <- Clock.instant
      _ <- storage.get.filterOrFail(_.size < 10000)(DrawingError("Too many events"))
      _ <- command match {
        case DrawCommand(StartScribble(id, points, _), _) =>
          storage.update(_ :+ DrawEvent(
            0,
            ScribbleStarted(
              id, points.map(p => Point(p.x, p.y))
            ),
            Some(now.toEpochMilli())
          ))

        case DrawCommand(ContinueScribble(id, points, _), _) =>
          // TODO: Verify scribble exists
          storage.update(_ :+ DrawEvent(
            0,
            ScribbleContinued(
              id, points.map { p => Point(p.x, p.y) }
            ),
            Some(now.toEpochMilli())
          ))

        case DrawCommand(DeleteObject(id, _), _) =>
          // TODO: Verify scribble OR icon exists
          storage.update(_ :+ DrawEvent(
            0,
            ObjectDeleted(id),
            Some(now.toEpochMilli())
          ))

        case DrawCommand(MoveObject(id, Some(position), _), _) =>
          // TODO: Verify scribble OR icon exists
          storage.update(_ :+ DrawEvent(
            0,
            ObjectMoved(id, Some(position)),
            Some(now.toEpochMilli())
          ))

        case DrawCommand(CreateIcon(id, position, category, name, _), _) =>
          storage.update(_ :+ DrawEvent(
            0,
            IconCreated(id, Some(position), Some(category), Some(name)),
            Some(now.toEpochMilli())
          ))

        case DrawCommand(LabelObject(id, label, _), _) =>
          storage.update(_ :+ DrawEvent(
            0,
            ObjectLabelled(id, label),
            Some(now.toEpochMilli())
          ))

        case other =>
          ZIO.fail(DrawingError(s"Invalid or unsupported command: ${other}"))
      }
    } yield ()
  }

  override def events = eventsAfter(-1)

  override def eventsAfter(afterSequenceNr: Long) = {
    storage.changes.zipWithPrevious.flatMap { (prev, next) =>
      if (prev.isEmpty) {
        // First element -> emit all previous events
        if (afterSequenceNr == -1)
          ZStream.fromIterable(next.events)
        else {
          val toDrop = next.events.view.map(_.sequenceNr).search(afterSequenceNr) match {
            case Found(idx) => idx + 1
            case InsertionPoint(idx) => idx
          }
          ZStream.fromIterable(next.events.drop(toDrop))
        }
      } else {
        // Further elements -> this is a new event, present as the last element
        ZStream(next.events.last)
      }
    }
  }

  override def version = storage.get.map(_.events.size)
}

case class DrawingsInMemory(storage: Ref.Synchronized[Map[UUID,Drawing]]) extends Drawings {
  // TODO: Remove drawing from memory
  override def getDrawing(id: UUID) = {
    storage.updateSomeAndGetZIO {
      case map if map.size > 100 =>
        ZIO.fail(DrawingError("Too many drawings"))
      case map if !map.contains(id) => for {
        drawStorage <- SubscriptionRef.make(DrawingStorage())
        _ <- drawStorage.update(_ :+ DrawEvent(0, DrawingCreated()))
        drawing = DrawingInMemory(drawStorage)
      } yield map + (id -> drawing)
    }.map(_(id))
  }
}

object Drawings {
  case class DrawingError(message: String)

  val inMemory = ZLayer.scoped {
    for {
      storage <- Ref.Synchronized.make[Map[UUID,Drawing]](Map.empty)
    } yield DrawingsInMemory(storage)
  }
}