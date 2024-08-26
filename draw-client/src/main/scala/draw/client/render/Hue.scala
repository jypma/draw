package draw.client.render

import scala.collection.Searching.Found
import scala.collection.Searching.InsertionPoint

case class Hue(name: String, factor: Double) {
  def degrees: Int = (factor * 360).toInt
}

object Hue {
  private val hues = Seq(
    Hue("Red",0.000),
    Hue("Scarlet",0.021),
    Hue("Vermilion",0.042),
    Hue("Blaze Orange",0.063),
    Hue("Orange",0.083),
    Hue("Golden Poppy",0.104),
    Hue("Amber",0.125),
    Hue("Broom",0.146),
    Hue("Yellow",0.167),
    Hue("Bitter Lemon",0.188),
    Hue("Lime",0.208),
    Hue("Green Grape",0.229),
    Hue("Chartreuse",0.250),
    Hue("Limeade",0.271),
    Hue("Harlequin",0.292),
    Hue("Screamin' Green",0.313),
    Hue("Green",0.333),
    Hue("Free Speech Green",0.354),
    Hue("Irish Green",0.375),
    Hue("Irish Spring Green",0.396),
    Hue("New Spring Green",0.417),
    Hue("Spring Aquamarine",0.438),
    Hue("Aquamarine",0.458),
    Hue("Bright Turquoise",0.479),
    Hue("Cyan",0.500),
    Hue("Cyanish Capri",0.521),
    Hue("Capri",0.542),
    Hue("Cornflower",0.563),
    Hue("Azure",0.583),
    Hue("Blue Ribbon",0.604),
    Hue("Cerulean",0.625),
    Hue("Bluish Cerulean",0.646),
    Hue("Blue",0.667),
    Hue("Bluish Ultramarine",0.688),
    Hue("Ultramarine",0.708),
    Hue("Electric Violet",0.729),
    Hue("Violet",0.750),
    Hue("Grape",0.771),
    Hue("Purple",0.792),
    Hue("Psychedelic Purple",0.813),
    Hue("Magenta",0.833),
    Hue("Hot Magenta",0.854),
    Hue("Cerise",0.875),
    Hue("Hollywood Cerise",0.896),
    Hue("Rose",0.917),
    Hue("Razzmatazz",0.938),
    Hue("Crimson",0.958),
    Hue("Torch Red",0.979),
  )

  private val hueValues = hues.map(_.factor)

  val red: Hue = find(0)

  def find(hue: Int): Hue = {
    val f:Double = (hue % 360) / 360.0
    hueValues.search(f) match {
      case Found(foundIndex) =>
        hues(foundIndex)
      case InsertionPoint(insertionPoint) if insertionPoint > 0 =>
        val mid = hues(insertionPoint).factor - hues(insertionPoint - 1).factor
        hues(insertionPoint - (if (f > mid) 0 else 1))
      case _ =>
        hues(0)
    }
  }
}
