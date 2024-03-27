package draw.client.render

import zio.lazagna.Consumeable
import zio.lazagna.Consumeable._
import zio.lazagna.dom.Attribute._
import zio.lazagna.dom.Element.svgtags._
import zio.lazagna.dom.Element.{textContent, _}
import zio.lazagna.dom.MultiUpdate
import zio.lazagna.dom.svg.SVGHelper
import zio.{ZIO}

import draw.data.{IconState, ObjectState}
import draw.geom.Rectangle
import org.scalajs.dom

import DrawingRenderer.iconSize

trait IconRenderer extends ObjectRenderer[IconState] {
  /** Returns the icon and (optional) label bounding boxes, updating as the icon is edited */
  def getBoundingBoxes(id: String): Consumeable[(Rectangle, Option[Rectangle])]
}

object IconRenderer {
  def make = for {
    rendered <- ZIO.service[RenderState]
    helper <- ZIO.service[SVGHelper]
  } yield new IconRenderer {
    override def render(initial: ObjectState[IconState]) = MultiUpdate.make[ObjectState[IconState]].flatMap { state =>
      g(
        id := s"icon${initial.id}",
        cls := "icon editTarget",
        state { s =>
          val p = s.body.position
          transform.set(s"translate(${p.x},${p.y})")
        },
        g(
          cls := "selectTarget",
          use(
            svgTitle(textContent := initial.body.symbol.name),
            href := initial.body.symbol.href,
            cls := "icon",
            width := iconSize,
            height := iconSize,
            x := -iconSize / 2,
            y := -iconSize / 2
          ),
        ),
        text(
          cls := "label",
          x := 0,
          y := iconSize / 2,
          state { s =>
            textContent := s.body.label
          }
        ),
      ).map((_, state.pipeline))
    }

    def getBoundingBoxes(id: String) = rendered.objectState(id).collect {
      case RenderedObject(_, icon, main) =>
        val label = Option(icon.querySelector(".label"))
          .filter(!_.innerHTML.isEmpty)
          .map(e => helper.svgBoundingBox(e.asInstanceOf[dom.SVGLocatable], 0))
        (main, label)
    }.changes
  }
}
