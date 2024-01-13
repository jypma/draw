package zio.lazagna.dom

import org.scalajs.dom
import zio.ZIO
import zio.Scope

case class Element[E <: dom.Element](target: E, children: Seq[Modifier]) extends Modifier {
  override def mount(parent: dom.Element): ZIO[Scope, Nothing, Unit] = {
    mount(parent, None)
  }

  private[lazagna] def mount(parent: dom.Element, after: Option[dom.Element]): ZIO[Scope, Nothing, Unit] = {
    val mountChildren = ZIO.collectAll(children.map(_.mount(target)))
    ZIO.acquireRelease {
      ZIO.succeed {
        after.map { a =>
          parent.insertBefore(target, a.nextSibling)
        }.getOrElse {
          parent.appendChild(target)
        }
      }
    } { _ =>
      ZIO.succeed {
        parent.removeChild(target)
      }
    }.unit <* mountChildren
  }

  private[lazagna] def moveAfter(parent: dom.Element, after: dom.Element): ZIO[Scope, Nothing, Unit] = {
    ZIO.succeed {
      parent.insertBefore(target, after.nextSibling)
    }
  }
}

object Element {
  val textContent = TextContent
  val children = Children

  def thisElementAs(fn: dom.Element => Modifier): Modifier = new Modifier {
    override def mount(parent: dom.Element): ZIO[Scope, Nothing, Unit] = {
      fn.apply(parent).mount(parent)
    }
  }

  case class CreateFn(name: String) {
    def apply(children: Modifier*) = Element(dom.document.createElement(name), children.toSeq)
  }

  object tags {
    val div = CreateFn("div")
  }

  object svgtags {
    case class CreateFn[E <: dom.Element](name: String) {
      def apply(children: Modifier*) = Element[E](dom.document.createElementNS("http://www.w3.org/2000/svg", name).asInstanceOf[E], children.toSeq)
    }

    val svg = CreateFn[dom.svg.SVG]("svg")
    val rect = CreateFn[dom.svg.RectElement]("rect")
    val circle = CreateFn[dom.svg.Circle]("circle")
    val g = CreateFn[dom.svg.G]("g")
  }
}
