package colossus
package encoding

sealed trait EncodeResult
object EncodeResult {
  case object Complete extends EncodeResult
  case object Incomplete extends EncodeResult
}

object Encoders {

  def sized(size: Long)(encoder: DataOutBuffer => Unit) = SizedProcEncoder(size, encoder)

  def unsized(encoder: => DataBuffer) = BlockEncoder(encoder)
}

trait DataOutBuffer {

  def available: Long

  /**
   * Copy as much as `src` into this buffer as possible.  
   */
  def copy(src: DataBuffer)

  /**
   * Attempt to write the entire contents of bytes into this buffer.  If there
   * is not enough available space an exeption is thrown
   */
  def write(bytes: ByteString)

}

case class ByteOutBuffer(underlying: ByteBuffer) extends DataOutBuffer {

  def available = underlying.remaining

  def copy(src: DataBuffer) {
    val oldLimit = src.limit()
    val newLimit = if (src.remaining > underlying.remaining) {
      oldLimit - (src.remaining - underlying.remaining)
    } else {
      oldLimit
    }
    src.limit(newLimit)
    underlying.put(src)
    src.limit(oldLimit)
  }

  def write(bytes: ByteString) {
    underlying.put(bytes.asByteBuffer)
  }

}

class DynamicBuffer extends DataOutBuffer {
  
  private val builder = new ByteStringBuilder

  def available = Long.MaxValue //maybe limit this somehow?
  def copy(from: DataBuffer) {
    builder ++= from.takeAll
  }
  def write(bytes: ByteString) {
    builder ++= bytes
  }

  def result = builder.result
}

trait Encoder {

  def writeInto(buffer: DataOutBuffer) : EncodeResult

}

class MultiEncoder(encoders: List[Encoder]) {

  var remaining = encoders

  def writeInto(buffer: DataOutBuffer) = {
    var full = false
    while (!full && !remaining.isEmpty) {
      remaining.head.writeInto(buffer) match {
        case Complete => {
          remaining = remaining.tail
        }
        case Incomplete => {
          full = true
        }
      }
    }
    if (full) Incomplete else Complete
  }

}

case class Encoding(encoders: List[Encoder])

case class BlockEncoder(data: DataBuffer) extends Encoder {

  def writeInto(buffer: DataOutBuffer): EncodeResult = {
    buffer.copy(data)
    if (data.hasUnreadData) {
      Incomplete
    } else {
      Complete
    }
  }

}

//this encoder wraps an encoding function that requires exactly `size` bytes.  If
//the DataOutBuffer given to this encoder is too small (either becuase it's
//close to being full or is simply not large enough for the raw data), this will
//then create a Dynamic buffer, let the function write to that, and convert
//itself into a BlockEncoder
class SizedProcEncoder(size: Long, encoder: DataOutBuffer => Unit) extends Encoder {
  
  private var overflowEncoder: Option[BlockEncoder] = None

  def writeInfo(buffer: DataOutBuffer): EncodeResult = overflowEncoder match {
    case Some(enc) => enc.writeInto(buffer)
    case None => if (buffer.available < size) {
      val data = new DynamicBuffer
      encoder(data)
      overflowEncoder = Some(BlockEncoder(DataBuffer(data.result)))
      Incomplete
    } else {
      encoder(buffer)
      Complete
    }
  }
}