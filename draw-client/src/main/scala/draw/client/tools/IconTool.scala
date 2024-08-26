package draw.client.tools

import zio.lazagna.Consumeable
import zio.lazagna.Consumeable.given
import zio.lazagna.dom.Attribute._
import zio.lazagna.dom.Element._
import zio.lazagna.dom.Element.svgtags._
import zio.lazagna.dom.Element.tags._
import zio.lazagna.dom.Events._
import zio.lazagna.dom.Modifier._
import zio.lazagna.dom.svg.SVGHelper
import zio.lazagna.dom.{Alternative, Children, Element, Modifier}
import zio.stream.SubscriptionRef
import zio.{Hub, Scope, UIO, ZIO}

import draw.client.{Drawing, SymbolIndex}
import draw.client.render.Hue
import draw.data.SymbolRef
import draw.data.drawcommand.{CreateIcon, DrawCommand}
import draw.data.drawevent.{BrightnessStyle}
import draw.data.point.Point
import org.scalajs.dom
import draw.client.render.DrawingRenderer
import draw.client.render.IconRenderer

object IconTool {
  private val iconSize = 64

  case class Props(icon: SymbolRef = SymbolRef.person, brightness: BrightnessStyle = BrightnessStyle.black, hue: Hue = Hue.red)

  def make(drawing: Drawing, dialogs: Children, keyboard: Children, index: SymbolIndex): UIO[Modifier[Unit]] = for {
    searchResult <- Hub.bounded[SymbolIndex.Result](1)
    props <- SubscriptionRef.make(Props())
    cursorPos <- SubscriptionRef.make[Option[dom.SVGPoint]](None)
  } yield {
    val brightnesses = Seq(
      ("Black", BrightnessStyle.black),
      ("Dark", BrightnessStyle.dark),
      ("Bright", BrightnessStyle.bright)
    )

    val selectDialog = dialogs.child { close =>
      div(
        cls := "dialog",
        div(
          cls := "icon-dialog kv-dialog",
          div(textContent := "Brightness:"),
          div(
            cls := "brightness",
            brightnesses.map { b =>
              span(
                input(typ := "radio", name := "brightness", id := s"brightness_${b._2.value}",
                  checked <-- props.map(_.brightness == b._2),
                  onChange(_.flatMap(_ =>
                    props.update(_.copy(brightness = b._2))
                  ))
                ),
                label(`for` := s"brightness_${b._2.value}", textContent := b._1)
              )
            }
          ),
          div(textContent := "Color:"),
          div(
            cls := "hue",
            DrawingRenderer.hues.map { hue =>
              span(
                input(typ := "radio", name := "hue", id := s"hue_${hue.degrees}",
                  checked <-- props.map(_.hue == hue),
                  onChange(_.flatMap(_ =>
                    props.update(_.copy(hue = hue))
                  ))
                ),
                label(`for` := s"hue_${hue.degrees}", style := s"background-color:hsl(${hue.degrees} 100% 50%)", title := hue.name)
              )
            }
          ),
          div(textContent := "Icon:"),
          div(
            style := "justify-self: stretch",
            div(
              cls := "results",
              Alternative.mountOneForked(searchResult) { s =>
                s.symbols.map { symbol =>
                  svg(
                    cls := "result",
                    tabindex := 0,
                    use(
                      svgTitle(textContent := symbol.name),
                      href := symbol.href,
                      cls := "icon",
                      width := 24,
                      height := 24
                    ),
                    onClick.merge(onKeyDown(_.filter(_.key == "Enter")))(_.flatMap { _ =>
                      props.update(_.copy(icon = symbol)) *> close
                    })
                      // TODO: "Enter" on the input itself switches focus to the first icon, and activates letter-overlay shortcuts for the first 36 matches.
                  )
                }
              }
            ),
            div(
              input(typ := "text", placeholder := "Search icon...", list := "icon-dialog-list", focusNow,
                onInput.asTargetValue(_.flatMap { text =>
                  index.lookup(text).flatMap(searchResult.publish)
                })
              ),
              // No datalist for now. Has crazy high CPU usage if replacing values after input...
              /*
               datalist(id := "icon-dialog-list",
               // Alternative.mountOne(searchResult.debounce(1.second)) { _.completions.map { s => option(value := s) } }
               // index.completionList.flatMap(_.map(s => option(value := s)))
               )
               */
            ),
          ),
          keyboard.child { _ =>
            DrawingTools.keyboardAction("Escape", "Close dialog", close)
          }
        )
      )
    }

    SVGHelper { helper =>
      Modifier.all(
        onMouseMove(_.flatMap { e =>
          cursorPos.set(Some(helper.screenToSvg(e)))
        }),
        keyboard.child { _ =>
          DrawingTools.keyboardAction("u", "Select icon", selectDialog)
        },
        onMouseDown(_
          .filter(_.button == 0)
          .flatMap { e =>
            for {
              p <- props.get
              id <- DrawingTools.makeUUID
              pos = helper.screenToSvg(e)
              bounds = helper.svgBoundingBox(helper.svg.querySelector(".icon-preview").asInstanceOf[dom.SVGLocatable])
              _ <- drawing.perform(DrawCommand(CreateIcon(id, Point(pos.x, pos.y), p.icon.category.name,
                p.icon.name, bounds.width, bounds.height, Some(p.brightness), Some(p.hue.degrees))))
            } yield {}
          }),
        g(
          cls := "icon-preview",
          use(
            x <-- cursorPos.map(p => (p.map(_.x).getOrElse(-100000.0) - iconSize / 2).toString),
            y <-- cursorPos.map(p => (p.map(_.y).getOrElse(-100000.0) - iconSize / 2).toString),
            width := iconSize,
            height := iconSize,
            href <-- props.map(_.icon.href),
            filter <-- props.map(p => IconRenderer.toFilter(p.brightness, p.hue.degrees))
          )
        )
      )
    }
  }
}
