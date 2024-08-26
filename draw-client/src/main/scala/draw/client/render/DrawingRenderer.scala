package draw.client.render

import zio.lazagna.Consumeable.given
import zio.lazagna.dom.Attribute._
import zio.lazagna.dom.Element.svgtags._
import zio.lazagna.dom.Element.tags._
import zio.lazagna.dom.Element.{textContent, _}
import zio.lazagna.dom.svg.SVGHelper
import zio.lazagna.dom.{Alternative, Attribute, Children, Element, Modifier}
import zio.lazagna.{Consumeable, Setup}
import zio.stream.SubscriptionRef
import zio.{ZIO, ZLayer, durationInt}

import draw.client.Drawing
import draw.client.tools.DrawingTools
import draw.data.{IconState, LinkState, ScribbleState}
import zio.lazagna.geom.Point
import org.scalajs.dom

import Drawing._

trait DrawingRenderer {
  def render: Modifier[Any]
}

object DrawingRenderer {
  private val hueCount = 11
  val hues = (0 until hueCount).map(h => (h * 360.0 / hueCount).toInt).map(Hue.find)

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

  val live = ZLayer.fromZIO {
    for {
      drawingTools <- ZIO.service[DrawingTools]
      drawing <- ZIO.service[Drawing]
      renderState <- ZIO.service[RenderState]
      initialView = if (drawing.initialVersion <= 1) 1 else 0
      currentView <- SubscriptionRef.make(initialView)
      awaiting <- SubscriptionRef.make(Set.empty[String])
    } yield new DrawingRenderer {
      println("Rendering up to event " + drawing.initialVersion + " in background.")
      val start = System.currentTimeMillis()

      def render = {
        val svgMain = div(
          svg(
            svgStyleTag(), // No direct style here, stuff is added here when exporting.
            cls <-- drawingTools.currentToolName.map(t => s"main tool-${t}"),
            viewBox <-- drawing.viewport.map(_.toSvgViewBox()),
            overflow := "hidden",
            tabindex := 0, // To enable keyboard events
            focusNow, // To allow dragging immediately
            defs(
              marker(
                id := "arrow",
                viewBox := "0 0 20 20",
                refX := 5,
                refY := 5,
                markerWidth := 6,
                markerHeight := 6,
                orient := "auto-start-reverse",
                path(
                  d := "M 0 0 L 10 5 L 0 10 z",
                  stroke := "none",
                  fill := "context-stroke"
                )
              ),
              hues.map { hue =>
                Modifier.all(
                  filterTag(
                    id := s"bright_${hue.degrees}",
                    feColorMatrix(values := "-1 0 0 1 0   0 -1 0 1 0   0 0 -1 1 0  -0.21 -0.72 -0.07 2 0", result := "fbSourceGraphic") /* invert source */,
                    //feColorMatrix(values := "1 0 0 1 0   0 1 0 1 0   0 0 1 1 0   0 0 0 1 0", result := "fbSourceGraphic"),
                    feFlood(floodColor := s"hsl(${hue.degrees},var(--bright-saturation),var(--bright-lightness))", floodOpacity := 1, in := "fbSourceGraphic", result := "flood1"), /* flood with target color */
                    feBlend(in := "flood1", in2 := "fbSourceGraphic", mode := "multiply", result := "blend1"), /* multiply with original */
                    feComposite(in := "blend1", in2 := "fbSourceGraphic", operator := "in") /* crop to original alpha */
                  ),
                  filterTag(
                    id := s"dark_${hue.degrees}",
                    feColorMatrix(values := "-1 0 0 1 0   0 -1 0 1 0   0 0 -1 1 0  -0.21 -0.72 -0.07 2 0", result := "fbSourceGraphic") /* invert source */,
                    //feColorMatrix(values := "1 0 0 1 0   0 1 0 1 0   0 0 1 1 0   0 0 0 1 0", result := "fbSourceGraphic"),
                    feFlood(floodColor := s"hsl(${hue.degrees},var(--dark-saturation),var(--dark-lightness))", floodOpacity := 1, in := "fbSourceGraphic", result := "flood1"), /* flood with target color */
                    feBlend(in := "flood1", in2 := "fbSourceGraphic", mode := "multiply", result := "blend1"), /* multiply with original */
                    feComposite(in := "blend1", in2 := "fbSourceGraphic", operator := "in") /* crop to original alpha */
                  )

                )
              }
            ),
            SVGHelper { helper =>
              val deps = ZLayer.succeed(renderState) ++ ZLayer.succeed(helper) ++ ZLayer.succeed(drawing)
              g(
                cls := "drawing",
                for {
                  scribbleR <- ScribbleRenderer.make.provide(deps)
                  iconR <- IconRenderer.make.provide(deps)
                  linkR <- LinkRenderer.make.provide(deps, ZLayer.succeed(iconR))
                  children <- Children.make
                  _ <- drawing.initialObjectStates.mapZIO { initial =>
                    val renderer = (initial.body match {
                      case _:ScribbleState => scribbleR
                      case _:IconState => iconR
                      case _:LinkState => linkR
                    }).asInstanceOf[ObjectRenderer[initial.Body]]

                    awaiting.update(_ + initial.id) *> // FIXME only if event < drawing.initialVersion
                    children.child { destroy =>
                      g(
                        cls <-- renderState.selectionIds.map { s => if (s.contains(initial.id)) "selected" else "" }.changes,
                        renderer.render(initial).flatMap { case (elem, pipeline)  =>
                          drawing.objectState(initial)
                            .via(pipeline)
                            .tap{_ => awaiting.update{_ - initial.id}}
                            .tap(s => renderState.notifyRendered(s, elem) *> ZIO.when(s.deleted)(destroy))
                            .takeUntil(_.deleted)
                            .consume
                        }
                      )
                    }
                  }.consume
                  res <- children.render
                } yield res
              )
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

        var eventCountDebug = 0
        var switchedReady = false
        Modifier.all(
          (ZIO.unit.delay(5.seconds) *> awaiting.update { w =>
            println(s"Warning: Not finished rendering ${w}, giving up.")
            Set.empty
          }).fork.unit,
          Alternative.showOne(currentView.merge(drawing.connectionStatus.collect {
            case Drawing.Disconnected => 2
          }), alternatives, Some(initialView)),
          awaiting.changes.zipLatest(renderState.latestSequenceNr).tap { (notready, seqNr) =>
            eventCountDebug += 1
            if (switchedReady || (seqNr < drawing.initialVersion) || !notready.isEmpty) ZIO.unit else {
              switchedReady = true
              val time = System.currentTimeMillis() - start
              println(s"Processed ${eventCountDebug} events, until sequence nr ${drawing.initialVersion}, in ${time}ms")
              currentView.set(1)
            }
          }.takeUntil((notready, seqNr) => seqNr >= drawing.initialVersion && notready.isEmpty).consume
        )
      }
    }
  }
}
