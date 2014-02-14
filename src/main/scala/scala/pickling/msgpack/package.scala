package scala.pickling

import scala.reflect.runtime.universe.Mirror
import scala.language.implicitConversions

/**
 * @author Taro L. Saito
 */
package object msgpack {

  implicit val msgpackFormat = new MsgPackPickleFormat
  implicit def toMsgPackPickle(value:Array[Byte]) : MsgPackPickle = MsgPackPickle(value)

  private[msgpack] def toHEX(b:Array[Byte]) = b.map(x => f"$x%02x").mkString
}

package msgpack {

  import scala.pickling.binary.{ByteArrayBuffer, ByteArray, BinaryPickleFormat}
  import xerial.core.log.Logger


  case class MsgPackPickle(value:Array[Byte]) extends Pickle {
    type ValueType = Array[Byte]
    type PickleFormatType = MsgPackPickleFormat
    override def toString = s"""MsgPackPickle(${toHEX(value)})"""
  }


  class MsgPackPickleBuilder(format:MsgPackPickleFormat, out:MsgPackWriter) extends PBuilder with PickleTools with Logger {
    import format._
    import MsgPackCode._

    private var byteBuffer: MsgPackWriter = out


    private[this] def mkByteBuffer(knownSize: Int): Unit = {
      if (byteBuffer == null) {
        byteBuffer = if (knownSize != -1) new MsgPackOutputArray(knownSize) else new MsgPackOutputBuffer
      }
    }


    /**
     * Pack and write an integer value then returns written byte size
     * @param d
     * @return byte size written
     */
    private def packInt(d:Int) : Int = {
      if (d < -(1 << 5)) {
        if (d < -(1 << 15)) {
          // signed 32
          byteBuffer.writeByteAndInt(F_INT32, d)
          5
        } else if (d < -(1 << 7)) {
          // signed 16
          byteBuffer.writeByteAndShort(F_INT16, d.toShort)
          3
        } else {
          // signed 8
          byteBuffer.writeByteAndByte(F_INT8, d.toByte)
          2
        }
      } else if (d < (1 << 7)) {
        // fixnum
        byteBuffer.writeByte(d.toByte)
        1
      } else {
        if (d < (1 << 8)) {
          // unsigned 8
          byteBuffer.writeByteAndByte(F_UINT8, d.toByte)
          2
        } else if (d < (1 << 16)) {
          // unsigned 16
          byteBuffer.writeByteAndShort(F_UINT16, d.toShort)
          3
        } else {
          // unsigned 32
          byteBuffer.writeByteAndInt(F_UINT32, d)
          5
        }
      }
    }

    private def packLong(d:Long) : Int = {
      if (d < -(1L << 5)) {
        if (d < -(1L << 15)) {
          if(d < -(1L << 31)) {
            // signed 64
            byteBuffer.writeByteAndLong(F_INT64, d)
            9
          }
          else {
            // signed 32
            byteBuffer.writeByteAndInt(F_INT32, d.toInt)
            5
          }
        } else if (d < -(1 << 7)) {
          // signed 16
          byteBuffer.writeByteAndShort(F_INT16, d.toShort)
          3
        } else {
          // signed 8
          byteBuffer.writeByteAndByte(F_INT8, d.toByte)
          2
        }
      } else if (d < (1L << 7)) {
        // fixnum
        byteBuffer.writeByte(d.toByte)
        1
      } else {
        if (d < (1L << 8)) {
          // unsigned 8
          byteBuffer.writeByteAndByte(F_UINT8, d.toByte)
          2
        } else if (d < (1L << 16)) {
          // unsigned 16
          byteBuffer.writeByteAndShort(F_UINT16, d.toShort)
          3
        } else if (d < (1L << 32)) {
          // unsigned 32
          byteBuffer.writeByteAndInt(F_UINT32, d.toInt)
          5
        }
        else {
          // unsigned 64
          byteBuffer.writeByteAndLong(F_UINT64, d)
          9
        }
      }

    }

    private def packString(s:String) = {
      val bytes = s.getBytes("UTF-8")
      val len = bytes.length
      if(len < (1 << 5)) {
        byteBuffer.writeByte((F_FIXSTR_PREFIX | (len & 0x1F)).toByte)
      } else if(len < (1 << 7)) {
        byteBuffer.writeByte(F_STR8)
        byteBuffer.writeByte((len & 0xFF).toByte)
      }
      else if(len < (1 << 15)) {
        byteBuffer.writeByte(F_STR16)
        byteBuffer.writeByte(((len >> 8) & 0xFF).toByte)
        byteBuffer.writeByte((len & 0xFF).toByte)
      }
      else {
        byteBuffer.writeByte(F_STR32)
        byteBuffer.writeByte(((len >> 24) & 0xFF).toByte)
        byteBuffer.writeByte(((len >> 16) & 0xFF).toByte)
        byteBuffer.writeByte(((len >> 8) & 0xFF).toByte)
        byteBuffer.writeByte((len & 0xFF).toByte)
      }
      byteBuffer.write(bytes, 0, len)
      1 + len
    }

    private def packByteArray(b:Array[Byte]) = {
      val len = b.length
      val wroteBytes =
        if(len < (1 << 4)) {
          byteBuffer.writeByte((F_ARRAY_PREFIX | len).toByte)
          1
        }
        else if(len < (1 << 16)) {
          byteBuffer.writeByte(F_ARRAY16)
          byteBuffer.writeByte(((len >>> 8) & 0xFF).toByte)
          byteBuffer.writeByte((len & 0xFF).toByte)
          3
        }
        else {
          byteBuffer.writeByte(F_ARRAY32)
          byteBuffer.writeByte(((len >>> 24) & 0xFF).toByte)
          byteBuffer.writeByte(((len >>> 16) & 0xFF).toByte)
          byteBuffer.writeByte(((len >>> 8) & 0xFF).toByte)
          byteBuffer.writeByte((len & 0xFF).toByte)
          5
        }
      byteBuffer.write(b, 0, len)
      wroteBytes + len
    }



    def beginEntry(picklee: Any) = withHints { hints =>

      debug(s"hints: $hints")
      mkByteBuffer(hints.knownSize)

      if(picklee == null)
        byteBuffer.writeByte(F_NULL)
      else if (hints.oid != -1) {
        // Has an object ID
        val oid = hints.oid
        // TODO Integer compaction
        byteBuffer.writeByte(F_FIXEXT4)
        byteBuffer.writeByte(F_EXT_OBJREF)
        byteBuffer.writeInt(hints.oid)
      } else {
        if(!hints.isElidedType) {
          // Type name is present
          val tpeBytes = hints.tag.key.getBytes("UTF-8")
          debug(s"encode type name: ${hints.tag.key} length:${tpeBytes.length}, ${toHEX(tpeBytes)}")
          tpeBytes.length match {
            case l if l < (1 << 7) =>
              byteBuffer.writeByte(F_EXT8)
              byteBuffer.writeByte((l & 0xFF).toByte)
            case l if l < (1 << 15) =>
              byteBuffer.writeByte(F_EXT16)
              byteBuffer.writeByte(((l >>> 8) & 0xFF).toByte)
              byteBuffer.writeByte((l & 0xFF).toByte)
            case l =>
              byteBuffer.writeByte(F_EXT32)
              byteBuffer.writeByte(((l >>> 24) & 0xFF).toByte)
              byteBuffer.writeByte(((l >>> 16) & 0xFF).toByte)
              byteBuffer.writeByte(((l >>> 8) & 0xFF).toByte)
              byteBuffer.writeByte((l & 0xFF).toByte)
          }
          byteBuffer.writeByte(F_EXT_TYPE_NAME)
          byteBuffer.write(tpeBytes)
        }

        hints.tag.key match {
          case KEY_NULL =>
            byteBuffer.writeByte(F_NULL)
          case KEY_BYTE =>
            byteBuffer.writeByte(picklee.asInstanceOf[Byte])
          case KEY_SHORT =>
            byteBuffer.writeShort(picklee.asInstanceOf[Short])
          case KEY_CHAR =>
            byteBuffer.writeChar(picklee.asInstanceOf[Char])
          case KEY_INT =>
            packInt(picklee.asInstanceOf[Int])
          case KEY_FLOAT =>
            byteBuffer.writeFloat(picklee.asInstanceOf[Float])
          case KEY_LONG =>
            packLong(picklee.asInstanceOf[Long])
          case KEY_DOUBLE =>
            byteBuffer.writeDouble(picklee.asInstanceOf[Double])
          case KEY_SCALA_STRING | KEY_JAVA_STRING =>
            packString(picklee.asInstanceOf[String])
          case KEY_ARRAY_BYTE =>
            packByteArray(picklee.asInstanceOf[Array[Byte]])
          case _ =>
            if(hints.isElidedType) {
              byteBuffer.writeByte(F_FIXEXT1)
              byteBuffer.writeByte(F_EXT_ELIDED_TAG)
              byteBuffer.writeByte(0) // dummy
            }
        }
      }

      this
    }

    @inline def putField(name: String, pickler: PBuilder => Unit) = {
      pickler(this)
      this
    }

    def endEntry() : Unit = {

     /* do nothing */

    }

    def beginCollection(length: Int) : PBuilder = {
      if(length < (1 << 4))
        byteBuffer.writeByte((F_ARRAY_PREFIX | length).toByte)
      else if(length < (1 << 16)) {
        byteBuffer.writeByte(F_ARRAY16)
        byteBuffer.writeShort(length.toShort)
      }
      else {
        byteBuffer.writeByte(F_ARRAY32)
        byteBuffer.writeInt(length)
      }
      this
    }

    def putElement(pickler: (PBuilder) => Unit) = {
      pickler(this)
      this
    }

    def endCollection() : Unit = {
      // do nothing
    }

    def result() = {
      MsgPackPickle(byteBuffer.result())
    }
  }


  class MsgPackPickleReader(arr:Array[Byte], val mirror:Mirror, format: MsgPackPickleFormat) extends PReader with PickleTools with Logger {
    import format._
    import MsgPackCode._

    private val in = new MsgPackByteArrayReader(arr)
    private var pos = 0
    private var _lastTagRead: FastTypeTag[_] = null
    private var _lastTypeStringRead: String  = null

    private def lastTagRead: FastTypeTag[_] =
      if (_lastTagRead != null)
        _lastTagRead
      else {
        // assume _lastTypeStringRead != null
        _lastTagRead = FastTypeTag(mirror, _lastTypeStringRead)
        _lastTagRead
      }

    def beginEntry() : FastTypeTag[_] = {
      beginEntryNoTag()
      lastTagRead
    }

    def beginEntryNoTag() : String = {
      val res : Any = withHints { hints =>
        if(hints.isElidedType && nullablePrimitives.contains(hints.tag.key)) {
          debug(s"Decode elided type")
          val la1 = in.lookahead
          la1 match {
            case F_NULL =>
              in.readByte
              FastTypeTag.Null
            case F_FIXEXT4 =>
              in.lookahead(1) match {
                case F_EXT_OBJREF =>
                  in.readByte
                  in.readByte
                  FastTypeTag.Ref
                case _ => hints.tag
              }
            case _ => hints.tag
          }
        }
        else if(hints.isElidedType && primitives.contains(hints.tag.key)) {
          debug(s"Decode primitive type: ${hints.tag}")
          hints.tag
        }
        else {
          debug(s"Decode obj with a type")
          val la1 = in.lookahead
          debug(f"la1: $la1%02x")
          la1 match {
            case F_NULL =>
              in.readByte
              FastTypeTag.Null
            case F_EXT8 =>
              val dataSize = in.lookahead(1) & 0xFF
              debug(s"dataSize: $dataSize")
              in.lookahead(2) match {
                case F_EXT_TYPE_NAME =>
                  in.readByte
                  in.readByte
                  in.readByte
                  val typeBytes = in.read(dataSize)
                  debug(s"typeBytes: ${toHEX(typeBytes)}")
                  val typeName = new String(typeBytes, "UTF-8")
                  debug(s"type name: $typeName, byteSize:${typeBytes.length}")
                  typeName
              }
            case F_FIXEXT1 =>
              in.lookahead(1) match {
                case F_EXT_ELIDED_TAG =>
                  in.readByte; in.readByte
                  FastTypeTag.Ref
                case _ =>
                  // TODO
                  ""
              }
            case F_FIXEXT4 =>
              in.lookahead(1) match {
                case F_EXT_OBJREF =>
                  ""
              }
            case _ =>
              debug(f"la1: $la1%02x")
              ""
          }


        }
      }

      if (res.isInstanceOf[String]) {
        _lastTagRead = null
        _lastTypeStringRead = res.asInstanceOf[String]
        _lastTypeStringRead
      } else {
        _lastTagRead = res.asInstanceOf[FastTypeTag[_]]
        _lastTagRead.key
      }
    }


    def atPrimitive = primitives.contains(lastTagRead.key)

    def readPrimitive() : Any = {
      var newpos = pos
      val res = lastTagRead.key match {
        case KEY_NULL => null
        case KEY_REF =>  null // TODO
        case KEY_BYTE =>
          newpos = pos + 1
          in.readByte
        case KEY_INT =>
          in.decodeInt
        case KEY_SCALA_STRING | KEY_JAVA_STRING =>
          in.decodeString
      }
      res
    }

    def atObject = !atPrimitive

    def readField(name: String) : MsgPackPickleReader = this

    def endEntry() : Unit = { /* do nothing */ }

    def beginCollection() : PReader = this

    def readLength() : Int = {
      val len = in.decodeInt
      len
    }

    def readElement() : PReader = this

    def endCollection() : Unit = { /* do nothing */ }
  }

  object MsgPackCode {
    val F_NULL : Byte = 0xC0.toByte
    val F_EXT_OBJREF : Byte = 1.toByte
    val F_EXT_ELIDED_TAG : Byte = 2.toByte
    val F_EXT_TYPE_NAME : Byte = 3.toByte

    val F_EXT8 = 0xC7.toByte
    val F_EXT16 = 0xC8.toByte
    val F_EXT32 = 0xC9.toByte

    val F_UINT8 : Byte = 0xCC.toByte
    val F_UINT16 : Byte = 0xCD.toByte
    val F_UINT32 : Byte = 0xCE.toByte
    val F_UINT64 : Byte = 0xCF.toByte

    val F_INT8 : Byte = 0xD0.toByte
    val F_INT16 : Byte = 0xD1.toByte
    val F_INT32 : Byte = 0xD2.toByte
    val F_INT64 : Byte = 0xD3.toByte

    val F_FIXEXT1 : Byte = 0xD4.toByte
    val F_FIXEXT2 : Byte = 0xD5.toByte
    val F_FIXEXT4 : Byte = 0xD6.toByte
    val F_FIXEXT8 : Byte = 0xD7.toByte
    val F_FIXEXT16 : Byte = 0xD8.toByte

    val F_ARRAY_PREFIX : Byte = 0x90.toByte
    val F_ARRAY16 : Byte = 0xDC.toByte
    val F_ARRAY32 : Byte = 0xDD.toByte

    val F_FIXSTR_PREFIX : Byte = 0xA0.toByte
    val F_STR8 : Byte = 0xD9.toByte
    val F_STR16 : Byte = 0xDA.toByte
    val F_STR32 : Byte = 0xDB.toByte
  }

  class MsgPackPickleFormat extends PickleFormat {

    val KEY_NULL    = FastTypeTag.Null.key
    val KEY_BYTE    = FastTypeTag.Byte.key
    val KEY_SHORT   = FastTypeTag.Short.key
    val KEY_CHAR    = FastTypeTag.Char.key
    val KEY_INT     = FastTypeTag.Int.key
    val KEY_LONG    = FastTypeTag.Long.key
    val KEY_BOOLEAN = FastTypeTag.Boolean.key
    val KEY_FLOAT   = FastTypeTag.Float.key
    val KEY_DOUBLE  = FastTypeTag.Double.key
    val KEY_UNIT    = FastTypeTag.Unit.key

    val KEY_SCALA_STRING = FastTypeTag.ScalaString.key
    val KEY_JAVA_STRING  = FastTypeTag.JavaString.key

    val KEY_ARRAY_BYTE    = FastTypeTag.ArrayByte.key
    val KEY_ARRAY_SHORT   = FastTypeTag.ArrayShort.key
    val KEY_ARRAY_CHAR    = FastTypeTag.ArrayChar.key
    val KEY_ARRAY_INT     = FastTypeTag.ArrayInt.key
    val KEY_ARRAY_LONG    = FastTypeTag.ArrayLong.key
    val KEY_ARRAY_BOOLEAN = FastTypeTag.ArrayBoolean.key
    val KEY_ARRAY_FLOAT   = FastTypeTag.ArrayFloat.key
    val KEY_ARRAY_DOUBLE  = FastTypeTag.ArrayDouble.key

    val KEY_REF = FastTypeTag.Ref.key


    val primitives = Set(KEY_NULL, KEY_REF, KEY_BYTE, KEY_SHORT, KEY_CHAR, KEY_INT, KEY_LONG, KEY_BOOLEAN, KEY_FLOAT, KEY_DOUBLE, KEY_UNIT, KEY_SCALA_STRING, KEY_JAVA_STRING, KEY_ARRAY_BYTE, KEY_ARRAY_SHORT, KEY_ARRAY_CHAR, KEY_ARRAY_INT, KEY_ARRAY_LONG, KEY_ARRAY_BOOLEAN, KEY_ARRAY_FLOAT, KEY_ARRAY_DOUBLE)
    val nullablePrimitives = Set(KEY_NULL, KEY_SCALA_STRING, KEY_JAVA_STRING, KEY_ARRAY_BYTE, KEY_ARRAY_SHORT, KEY_ARRAY_CHAR, KEY_ARRAY_INT, KEY_ARRAY_LONG, KEY_ARRAY_BOOLEAN, KEY_ARRAY_FLOAT, KEY_ARRAY_DOUBLE)

    type PickleType = MsgPackPickle
    type OutputType = MsgPackWriter

    def createReader(pickle: PickleType, mirror: Mirror) = new MsgPackPickleReader(pickle.value, mirror, this)

    def createBuilder() = new MsgPackPickleBuilder(this, null)
    def createBuilder(out: MsgPackWriter) = new MsgPackPickleBuilder(this, out)
  }


}
