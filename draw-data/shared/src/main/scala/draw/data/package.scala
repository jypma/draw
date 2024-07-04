package draw

package object data {
  implicit def toProtobuf(p: zio.lazagna.geom.Point): draw.data.point.Point = draw.data.point.Point(p.x, p.y)
  implicit def fromProtobuf(p: draw.data.point.Point): zio.lazagna.geom.Point = zio.lazagna.geom.Point(p.x, p.y)
}
