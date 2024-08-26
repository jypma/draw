package draw.client.render

import zio.ZIO
import zio.lazagna.dom.Attribute._
import zio.lazagna.dom.Element.svgtags._
import zio.lazagna.dom.Element.{textContent, _}
import zio.lazagna.dom.MultiUpdate
import zio.lazagna.dom.svg.SVGHelper
import draw.data.drawevent.{BrightnessStyle}

import draw.data.{IconState, ObjectState}

import IconState.mediumSize

trait IconRenderer extends ObjectRenderer[IconState] {
}

object IconRenderer {
  def toFilter(brightness: BrightnessStyle, hue: Int): String = {
    brightness match {
      case BrightnessStyle.dark => s"url(#dark_${hue})"
      case BrightnessStyle.bright => s"url(#bright_${hue})"
      case _ => ""
    }
  }

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
            width := mediumSize,
            height := mediumSize,
            x := -mediumSize / 2,
            y := -mediumSize / 2,
            state { s => filter.set(toFilter(s.body.brightnessStyle, s.body.hue)) }
          ),
        ),
        text(
          cls := "label",
          x := 0,
          y := mediumSize / 2,
          state { s =>
            textContent := s.body.label
          }
        ),
      ).map((_, state.pipeline))
    }
  }
}
