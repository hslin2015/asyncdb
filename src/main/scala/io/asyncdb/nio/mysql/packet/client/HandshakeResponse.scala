package io.asyncdb
package nio
package mysql
package packet
package client

import java.nio.charset.Charset
import java.security.MessageDigest

import cats.data.NonEmptyList

case class HandshakeResponse(
  username: String,
  charset: Int,
  seed: Array[Byte],
  authenticationMethod: String,
  password: Option[String] = None,
  database: Option[String] = None
)

object HandshakeResponse {
  val Padding = Array.fill[Byte](23)(0)
  implicit val handshakeResponseWriter: Writer[HandshakeResponse] = {
    Codec.writer[HandshakeResponse] { hr =>
      val buf = BufferWriter.apply(1024)
      buf.writeInt(Cap.baseCap.mask)
      buf.writeInt(MaxPacketSize)
      buf.writeByte(hr.charset.toByte)
      buf.writeBytes(Padding)
      Unsafe.writeNullEndedString(
        buf,
        hr.username,
        CharsetMap.of(hr.charset.toShort)
      )
      hr.password match {
        case Some(p) =>
          val auth = Authentication.generateAuthentication(
            CharsetMap.of(hr.charset.toShort),
            p,
            hr.seed,
            hr.authenticationMethod
          )
          buf.writeByte(auth.length)
          buf.writeBytes(auth)
        case _ => buf.writeByte(0)
      }
      hr.database.foreach { db =>
        Unsafe.writeNullEndedString(buf, db, CharsetMap.of(hr.charset.toShort))
      }
      Unsafe.writeNullEndedString(
        buf,
        hr.authenticationMethod,
        CharsetMap.of(hr.charset.toShort)
      )
      NonEmptyList.of(Packet.toPacket(buf.value))
    }
  }
}

object Authentication {
  val Native = "mysql_native_password"
  val Old    = "mysql_old_password"

  def generateAuthentication(
    charset: Charset,
    password: String,
    seed: Array[Byte],
    authType: String
  ) = {
    authType match {
      case Native => scramble411(charset, password, seed)
      case _      => scramble411(charset, password, seed) // 老模式是否需要支持
    }
  }

  private def scramble411(
    charset: Charset,
    password: String,
    seed: Array[Byte]
  ) = {
    val messageDigest = MessageDigest.getInstance("SHA-1")
    val initialDigest = messageDigest.digest(password.getBytes(charset))
    messageDigest.reset()
    val finalDigest = messageDigest.digest(initialDigest)
    messageDigest.reset()
    messageDigest.update(seed)
    messageDigest.update(finalDigest)
    val result = messageDigest.digest()
    (0 to result.length - 1)
      .map(i => result(i) = (result(i) ^ initialDigest(i)).toByte)
    result
  }
}
