package colossus
package protocols.http

import akka.util.{ByteString, ByteStringBuilder}

import parsing._
import Combinators._

object HttpParse {

  val NEWLINE = ByteString("\r\n")
  val NEWLINE_ARRAY = NEWLINE.toArray
  val N2 = (NEWLINE ++ NEWLINE).toArray

  def chunkedBody: Parser[ByteString] = chunkedBodyBuilder(new ByteStringBuilder)

  implicit val z: Zero[EncodedHttpHeader] = HttpHeader.FPHZero
  def header: Parser[EncodedHttpHeader] = line(HttpHeader.apply, true)
  def folder(header: EncodedHttpHeader, builder: HeadersBuilder): HeadersBuilder = builder.add(header)
  def headers: Parser[HeadersBuilder] = foldZero(header, new HeadersBuilder)(folder)

  private def chunkedBodyBuilder(builder: ByteStringBuilder): Parser[ByteString] = intUntil('\r', 16) <~ byte |> {
    case 0 => bytes(2) >> {_ => builder.result}
    case n => bytes(n.toInt) <~ bytes(2) |> {bytes => chunkedBodyBuilder(builder.append(bytes))}
  }


  case class HeadResult[T](head: T, contentLength: Option[Int], transferEncoding: Option[String] )


  trait LazyParsing {

    protected def parseErrorMessage: String

    def parsed[T](op: => T): T = try {
      op
    } catch {
      case p: ParseException => throw p
      case other : Throwable => throw new ParseException(parseErrorMessage + s": $other")
    }

    def fastIndex(data: Array[Byte], byte: Byte, start: Int = 0) = {
      var pos = start
      while (pos < data.size && data(pos) != byte) { pos += 1 }
      if (pos >= data.size) -1 else pos
    }

  }

  class HeadersBuilder {

    private var cl: Option[Int] = None
    private var te: Option[String] = None

    def contentLength = cl
    def transferEncoding = te

    private val build = new java.util.LinkedList[EncodedHttpHeader]()

    def add(header: EncodedHttpHeader): HeadersBuilder = {
      build.add(header)
      if (cl.isEmpty && header.matches("content-length")) {
        cl = Some(header.value.toInt)
      }
      if (te.isEmpty && header.matches("transfer-encoding")) {
        te = Some(header.value)
      }
      
      this
    }


    def buildHeaders: HttpHeaders = {
      new HttpHeaders(build.asInstanceOf[java.util.List[HttpHeader]]) //silly invariant java collections :/
    }
    
  }
}

