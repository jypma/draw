package draw.client.render

import zio.lazagna.Consumeable
import zio.lazagna.Consumeable._
import zio.lazagna.Consumeable.given
import zio.lazagna.dom.Attribute._
import zio.lazagna.dom.Element.svgtags._
import zio.lazagna.dom.Element.tags._
import zio.lazagna.dom.Element.{textContent, _}
import zio.lazagna.dom.svg.SVGHelper
import zio.lazagna.dom.{Alternative, Attribute, Children, Modifier}
import zio.stream.SubscriptionRef
import zio.{ZIO, ZLayer}

import draw.client.Drawing
import draw.client.tools.DrawingTools
import draw.data.{IconState, LinkState, ObjectState, ScribbleState}
import draw.geom.Point
import org.scalajs.dom

import Drawing._

trait DrawingRenderer {
  def render: Modifier
}

object DrawingRenderer {
  case class ObjectTarget(id: String, position: Point)
  object ObjectTarget {
    def apply(target: dom.Element): Option[ObjectTarget] = {
      val id = target.id match {
        case s if s.startsWith("scribble") => Some(s.substring("scribble".length))
        case s if s.startsWith("icon") => Some(s.substring("icon".length))
        case s if s.startsWith("link") => Some(s.substring("link".length))
        case _ => None
      }
      id.map(i => ObjectTarget(i, Point(
        if (target.hasAttribute("data-x")) target.getAttribute("data-x").toDouble else 0,
        if (target.hasAttribute("data-y")) target.getAttribute("data-y").toDouble else 0)))
    }
  }

  //TODO: replace with actual ObjectState once we have render callbacks in place
  val dataX = Attribute("data-x")
  val dataY = Attribute("data-y")

  /** Finds the element, or any of its parents, that has an ID, but only if one of those ancestors also has a
    * CSS class (indicating that it's a valid selection target) */
  private def getTargetParent(e: dom.Element, className: String): Option[dom.Element] = {
    var elem = e
    var count = 3
    var isselectTarget = e.classList.contains(className)
    while (elem.id == "" && count > 0) {
      elem = elem.parentNode.asInstanceOf[dom.Element]
      if (elem.classList.contains(className)) {
        isselectTarget = true
      }
      count -= 1
    }
    Option.when(isselectTarget)(elem)
  }

  /** Returns information about an object that might have been clicked to select it */
  def getSelectTargetObject(event: dom.MouseEvent): Option[ObjectTarget] = {
    //TODO: get from actual ObjectState once we capture that
    getTargetObject(event, "selectTarget")
  }

  /** Returns information about an object that might have been clicked to edit it */
  def getEditTargetObject(event: dom.MouseEvent): Option[ObjectTarget] = {
    //TODO: get from actual ObjectState once we capture that
    getTargetObject(event, "editTarget")
  }

  private def getTargetObject(event: dom.MouseEvent, className: String): Option[ObjectTarget] = {
    Some(event)
      .map(_.target)
      .collect { case elem: dom.Element =>
        elem }
      .map { e => getTargetParent(e, className) }
      .collect { case Some(e:dom.Element) => e }
      .flatMap(ObjectTarget.apply(_))
  }

  val iconSize = 64

  val live = ZLayer.fromZIO {
    for {
      drawingTools <- ZIO.service[DrawingTools]
      drawing <- ZIO.service[Drawing]
      initialView = if (drawing.initialVersion <= 1) 1 else 0
      currentView <- SubscriptionRef.make(initialView)
    } yield new DrawingRenderer {
      val start = System.currentTimeMillis()
      var eventCountDebug = 0
      var switchedReady = false

      def render = {
        val svgMain = div(
          svg(
            svgStyleTag(), // No direct style here, stuff is added here when exporting.
            cls <-- drawingTools.currentToolName.map(t => s"main tool-${t}"),
            viewBox <-- drawing.viewport.map(_.toSvgViewBox),
            overflow := "hidden",
            tabindex := 0, // To enable keyboard events
            focusNow, // To allow dragging immediately
            SVGHelper { helper =>
              Modifier.unwrap {
                for {
                  scribbleR <- ScribbleRenderer.make
                  iconR <- IconRenderer.make
                  linkR <- LinkRenderer.make.provide(ZLayer.succeed(helper), ZLayer.succeed(drawing))
                  children <- Children.make
                  _ <- drawing.initialObjectStates.tap(switchWhenReady).mapZIO { initial =>
                    val furtherEvents = drawing.objectState(initial.id).tap(switchWhenReady).takeUntil(_.deleted).map(_.body)
                    children.child { destroy =>
                      g(
                        cls <-- drawing.selection.map { s => if (s.contains(initial.id)) "selected" else "" },
                        Modifier.run(drawing.objectState(initial.id).filter(_.deleted).mapZIO(_ => destroy).take(1).consume),
                        initial.body match {
                          case _:ScribbleState =>
                            scribbleR.render(initial.asInstanceOf[ObjectState[ScribbleState]], furtherEvents.asInstanceOf[Consumeable[ScribbleState]])

                          case _:IconState =>
                            iconR.render(initial.asInstanceOf[ObjectState[IconState]], furtherEvents.asInstanceOf[Consumeable[IconState]])

                          case _:LinkState =>
                            linkR.render(initial.asInstanceOf[ObjectState[LinkState]], furtherEvents.asInstanceOf[Consumeable[LinkState]])
                        }
                      )
                    }
                  }.consume
                } yield g(
                  children.render
                )
              }
            },
            drawingTools.renderHandlers
          )
        )

        val alternatives = Map(
          0 -> div(
            cls := "loading",
            textContent := "Loading..."
          ),
          1 -> svgMain,
          2 -> div(
            cls := "disconnected",
            textContent := "The server has disconnected. Please reload this page to reconnect."
          )
        )

        Alternative.showOne(currentView.merge(drawing.connectionStatus.collect {
          case Drawing.Disconnected => 2
        }), alternatives, Some(initialView))
      }

      def switchWhenReady(state: ObjectState[_]) = {
        eventCountDebug += 1
        if (switchedReady || (state.sequenceNr < drawing.initialVersion)) ZIO.unit else {
          switchedReady = true
          val time = System.currentTimeMillis() - start
          println(s"Processed ${eventCountDebug} events, until sequence nr ${drawing.initialVersion}, in ${time}ms")
          currentView.set(1)
        }
      }
    }
  }
}