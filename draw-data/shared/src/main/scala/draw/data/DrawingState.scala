package draw.data

import draw.data.drawcommand.{ContinueScribble, CreateIcon, DeleteObject, DrawCommand, LabelObject, MoveObject, StartScribble, EditLink}
import draw.data.drawevent.DrawEvent
import draw.data.drawevent.ScribbleStarted
import draw.data.drawevent.IconCreated
import zio.lazagna.geom.Point
import draw.data.fromProtobuf
import draw.data.drawevent.ScribbleContinued
import draw.data.drawevent.ObjectMoved
import draw.data.drawevent.ObjectLabelled
import draw.data.drawevent.ObjectDeleted
import draw.data.drawevent.DrawEventBody
import draw.data.drawevent.DrawingCreated
import draw.data.drawevent.LinkCreated
import draw.data.drawevent.ObjectsLayedOut
import draw.data.drawevent.LinkEdited
import java.time.Instant
import draw.data.drawcommand.CreateLink
import draw.data.drawcommand.LayoutObjects
import java.util.UUID
import java.nio.ByteBuffer
import java.nio.ByteOrder
import com.google.protobuf.ByteString


case class DrawingState(
  lastSequenceNr: Long = 0,
  objects: Map[String, ObjectState[_]] = Map.empty,
  /* Key is object ID, value is link ID */
  objectLinks: DrawingState.Links = Map.empty,
  lastUser: UUID = null
) {
  import DrawingState._

  private def getMoveable(id: String): Moveable = objects(id).body.asInstanceOf[Moveable]

  private def alive = objects.view.filter(!_._2.deleted)

  private def set(state: ObjectState[_], isNew: Boolean, newLinks: Links, user: UUID, extraUpdates: Iterable[(String, ObjectState[_])] = Nil) = (
    (Seq(state), isNew),
    copy(
      // TEST: Deletes objects stick around in state (so we know they've been deleted asynchronously). It's OK, they won't be in pruned event storage.
      objects = objects + (state.id -> state) ++ extraUpdates,
      lastSequenceNr = state.sequenceNr,
      objectLinks = newLinks,
      lastUser = user
    )
  )

  private def unaffected(event: DrawEvent) =
    ((Nil, false), copy(lastSequenceNr = event.sequenceNr))

  // TEST: Links follow updates to their dependencies when they are moved
  private def update(id: String, event: DrawEvent, newLinks: Links = objectLinks) = {
    objects.get(id).map { state =>
      val newState = state.update(event, getMoveable)
      val linkIds = newLinks.getOrElse(id, Set.empty)
      val updatedLinks = linkIds.map { linkId => linkId -> objects(linkId).update(event, depId =>
        if (depId == id) newState.body.asInstanceOf[Moveable] else getMoveable(depId)) }
      set(newState, false, newLinks, userId(event), updatedLinks)
    }.getOrElse(unaffected(event))
  }

  // TEST: Links follow updates to their dependencies when they are moved
  private def updateAll(event: DrawEvent, moved: Seq[ObjectMoved]): ((Seq[ObjectState[_]], Boolean), DrawingState) = {
    var drawState = this
    val objStates = Seq.newBuilder[ObjectState[_]]
    for (move <- moved) {
      val ((objState, _), s) = drawState.update(event.copy(body = move))
      objStates.addAll(objState)
      drawState = s
    }
    ((objStates.result(), false), drawState)
  }

  /** Returns the new drawing state, new object state(s), and whether those objects are new */
  def update(event: DrawEvent): ((Seq[ObjectState[_]], Boolean), DrawingState) = {
    def create(id: String, body: ObjectStateBody, newLinks: Links = objectLinks) =
      set(ObjectState(id, event.sequenceNr, false, body), true, newLinks, userId(event))

    event.body match {
      case ScribbleStarted(id, points, _) =>
        create(id, ScribbleState(Point(0,0), points.map(fromProtobuf)))
      case IconCreated(id, optPos, category, name, width, height, _) =>
        create(id, IconState(
          optPos.map(fromProtobuf).getOrElse(Point(0,0)),
          category.zip(name).map((c,n) => SymbolRef(SymbolCategory(c), n)).getOrElse(SymbolRef.person), "",
        ).withBounds(width, height))
      case LinkCreated(id, src, dest, preferredDistance, preferredAngle, _) =>
        create(id, LinkState(src, dest, preferredDistance, preferredAngle,
          getMoveable(src), getMoveable(dest)),
          newLinks = objectLinks.add(src, id).add(dest, id))
      case LinkEdited(id, _, _, _) =>
        update(id, event)
      case ScribbleContinued(id, _, _) =>
        update(id, event)
      case ObjectMoved(id, _, _)  =>
        update(id, event)
      case ObjectLabelled(id, _, _, _, _, _) =>
        update(id, event)
      case ObjectDeleted(id, _) => alive.get(id).map(_.body) match {
        case Some(LinkState(src, dest, _, _, _, _)) =>
          update(id, event, newLinks = objectLinks.remove(src, id).remove(dest, id))
        case _ =>
          update(id, event, newLinks = objectLinks - id)
      }
      case _:DrawingCreated =>
        unaffected(event)
      case ObjectsLayedOut(moved, _) =>
        updateAll(event, moved)
      case _ =>
        println("??? Unhandled event: " + event)
        unaffected(event)
    }
  }

  /** Returns which events to emit when handling the given command. */
  def handle(now: Instant, user: UUID, command: DrawCommand): Seq[DrawEvent] = {
    def emit(body: DrawEventBody) = this.emit(now, user, Seq(body))

    command.body match {
      case StartScribble(id, points, _) =>
        emit(ScribbleStarted(id, points.map(p => Point(p.x, p.y))))
      case ContinueScribble(id, points, _) =>
        // TODO: Verify scribble exists
        emit(ScribbleContinued(id, points.map { p => Point(p.x, p.y) }))
      case DeleteObject(id, _) =>
        // TODO: Verify scribble OR icon exists
        // TEST: Verify links are deleted with object (and emitted events have different sequence numbers)
        val deleteLinks = objectLinks.getOrElse(id, Set.empty).map(ObjectDeleted(_)).toSeq
        this.emit(now, user, deleteLinks :+ ObjectDeleted(id))
      case MoveObject(id, Some(position), _) =>
        // TODO: Verify scribble OR icon exists
        emit(ObjectMoved(id, Some(position)))
      case CreateIcon(id, position, category, name, width, height, _) =>
        emit(IconCreated(id, Some(position), Some(category), Some(name), Some(width), Some(height)))
      case LabelObject(id, label, width, height, yOffset, _) =>
        // TODO: verify icon exists
        emit(ObjectLabelled(id, label, Some(width), Some(height), Some(yOffset)))
      case CreateLink(id, src, dest, preferredDistance, preferredAngle, _) =>
        // TODO: verify ids exist
        // TEST: Don't allow adding of link between already linked objects
        if (src == dest) {
          println("!!! Invalid link")
          Seq.empty
        } else if (alive.values.map(_.body).exists {
          case LinkState(s,d,_,_,_,_) if (s == src && d == dest) || (s == dest && d == src) => true
          case _ => false
        }) {
          println("!!! Duplicate link")
          // We already have this link
          Seq.empty
        } else {
          emit(LinkCreated(id, src, dest, preferredDistance, preferredAngle))
        }
      case EditLink(id, preferredDistance, preferredAngle, _)  =>
        objects.get(id) match {
          case Some(ObjectState(_,_,_,l:LinkState)) =>
            emit(LinkEdited(id, preferredDistance.orElse(l.preferredDistance),
              preferredAngle.orElse(l.preferredAngle)))
          case _ =>
            println("??? Unknown link " + id)
            Seq.empty
        }
      case LayoutObjects(moves, _) =>
        // TODO: verify IDs exist, and only emit for moves that have a position
        emit(ObjectsLayedOut(moves.map { m =>
          ObjectMoved(m.id, m.position)
        }))
      case other =>
        println("Unhandled command: " + other)
        Seq.empty
    }
  }

  /** Returns whether this drawing exists (has any events), or is completely new */
  def exists: Boolean = lastSequenceNr > 0

  /** Returns the events to emit in case this drawing is completely new (doesn't exist) */
  def handleCreate(now: Instant, user: UUID): Seq[DrawEvent] = emit(now, user, Seq(DrawingCreated()))

  private def emit(now: Instant, user: UUID, bodies: Seq[DrawEventBody]) = bodies.zipWithIndex.map { (body, idx) =>
    DrawEvent(lastSequenceNr + 1 + idx, body, Some(now.toEpochMilli()), Some(ByteString.copyFrom(toBytes(user))))
  }

  def links: Iterable[ObjectState[LinkState]] = objects.values.filter(_.body.isInstanceOf[LinkState]).asInstanceOf[Iterable[ObjectState[LinkState]]]
}

object DrawingState {
  type Links = Map[String, Set[String]]

  private[DrawingState] implicit class LinksOp(links: Links) {
    def add(key: String, value: String) =
      links.updated(key, links.getOrElse(key, Set.empty) + value)

    def remove(key: String, value: String) =
      links.updated(key, links.getOrElse(key, Set.empty) - value)
  }

  def toBytes(uuid: UUID): Array[Byte] = {
    val bb = ByteBuffer.wrap(new Array[Byte](16))
    bb.order(ByteOrder.BIG_ENDIAN)
    bb.putLong(uuid.getMostSignificantBits())
    bb.putLong(uuid.getLeastSignificantBits())
    bb.array()
  }

  def fromBytes(bytes: Array[Byte]): UUID = {
    val bb = ByteBuffer.wrap(bytes)
    bb.order(ByteOrder.BIG_ENDIAN)
    val high = bb.getLong()
    val low = bb.getLong()
    new UUID(high, low)
  }

  def userId(event: DrawEvent): UUID = {
    event.userId.map(_.toByteArray()).map(fromBytes).getOrElse(null)
  }
}
