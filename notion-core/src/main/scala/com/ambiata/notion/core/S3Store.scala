package com.ambiata.notion.core

import com.ambiata.com.amazonaws.services.s3.AmazonS3Client
import com.ambiata.saws.core._
import com.ambiata.saws.s3._
import com.ambiata.mundane.control._
import com.ambiata.mundane.io._
import com.ambiata.mundane.data._
import com.ambiata.mundane.path._
import java.util.UUID
import java.io.{InputStream, OutputStream}
import java.io.{PipedInputStream, PipedOutputStream}
import scala.io.Codec
import scalaz.{Store => _, _}, Scalaz._, effect.IO, effect.Effect._
import scodec.bits.ByteVector

case class S3ReadOnlyStore(s3: S3Prefix, client: AmazonS3Client) extends ReadOnlyStore[RIO] {
  def list(prefix: Key): RIO[List[Key]] =
    run { (s3 / prefix.name).listAddress.map(_.flatMap(p =>
      p.removeCommonPrefix(s3).flatMap(_.split(S3Operations.DELIMITER).toList.traverse(Component.create))).map(c => new Key(c.toVector)))
    }

  def filter(prefix: Key, predicate: Key => Boolean): RIO[List[Key]] =
    list(prefix).map(_.filter(predicate))

  def find(prefix: Key, predicate: Key => Boolean): RIO[Option[Key]] =
    list(prefix).map(_.find(predicate))

  def exists(key: Key): RIO[Boolean] =
    run { (s3 | key.name).exists }

  def checksum(key: Key, algorithm: ChecksumAlgorithm): RIO[Checksum] =
    run { (s3 | key.name).withStream(in => Checksum.stream(in, algorithm)) }

  def copyTo(store: Store[RIO], src: Key, dest: Key): RIO[Unit] =
    unsafe.withInputStream(src) { in =>
      store.unsafe.withOutputStream(dest) { out =>
        Streams.pipe(in, out) }}

  val bytes: StoreBytesRead[RIO] = new StoreBytesRead[RIO] {
    def read(key: Key): RIO[ByteVector] =
      run { (s3 | key.name).getBytes.map(ByteVector.apply) }
  }

  val strings: StoreStringsRead[RIO] = new StoreStringsRead[RIO] {
    def read(key: Key, codec: Codec): RIO[String] =
      run { (s3 | key.name).getWithEncoding(codec) }
  }

  val utf8: StoreUtf8Read[RIO] = new StoreUtf8Read[RIO] {
    def read(key: Key): RIO[String] =
      strings.read(key, Codec.UTF8)
  }

  val lines: StoreLinesRead[RIO] = new StoreLinesRead[RIO] {
    def read(key: Key, codec: Codec): RIO[List[String]] =
      strings.read(key, codec).map(_.lines.toList)
  }

  val linesUtf8: StoreLinesUtf8Read[RIO] = new StoreLinesUtf8Read[RIO] {
    def read(key: Key): RIO[List[String]] =
      lines.read(key, Codec.UTF8)
  }

  val unsafe: StoreUnsafeRead[RIO] = new StoreUnsafeRead[RIO] {
    def withInputStream(key: Key)(f: InputStream => RIO[Unit]): RIO[Unit] =
      RIO.using(run { (s3 | key.name).getObject.map(_.getObjectContent: InputStream) })(f)
  }

  def run[A](thunk: => S3Action[A]): RIO[A] =
    thunk.execute(client)

}

case class S3Store(s3: S3Prefix, client: AmazonS3Client) extends Store[RIO] with ReadOnlyStore[RIO] {
  val root: S3Prefix =
    s3

  val readOnly: ReadOnlyStore[RIO] =
    S3ReadOnlyStore(s3, client)

  def list(prefix: Key): RIO[List[Key]] =
    readOnly.list(prefix)

  def filter(prefix: Key, predicate: Key => Boolean): RIO[List[Key]] =
    readOnly.filter(prefix, predicate)

  def find(prefix: Key, predicate: Key => Boolean): RIO[Option[Key]] =
    readOnly.find(prefix, predicate)

  def exists(key: Key): RIO[Boolean] =
    readOnly.exists(key)

  def checksum(key: Key, algorithm: ChecksumAlgorithm): RIO[Checksum] =
    readOnly.checksum(key, algorithm)

  def delete(key: Key): RIO[Unit] =
    run { (s3 | key.name).toS3Pattern.determineAddress.flatMap(_.delete) }

  def deleteAll(prefix: Key): RIO[Unit] =
    list(prefix).flatMap(_.traverseU(delete)).void

  def move(in: Key, out: Key): RIO[Unit] =
    copy(in, out) >> delete(in)

  def moveTo(store: Store[RIO], src: Key, dest: Key): RIO[Unit] =
    copyTo(store, src, dest) >> delete(src)

  def copy(in: Key, out: Key): RIO[Unit] = {
    val inAddr = s3 | in.name
    val outAddr = s3 | out.name
    run(for {
      e <- outAddr.exists
      _ <- S3Action.when(e, S3Action.fail(s"Can not copy to $out as it already exists!"))
      _ <- inAddr.copy(outAddr)
    } yield ())
  }

  def copyTo(store: Store[RIO], src: Key, dest: Key): RIO[Unit] =
    readOnly.copyTo(store, src, dest)

  val bytes: StoreBytes[RIO] = new StoreBytes[RIO] {
    def read(key: Key): RIO[ByteVector] =
      readOnly.bytes.read(key)

    def write(key: Key, data: ByteVector): RIO[Unit] =
      run { (s3 | key.name).putBytes(data.toArray).void }
  }

  val strings: StoreStrings[RIO] = new StoreStrings[RIO] {
    def read(key: Key, codec: Codec): RIO[String] =
      readOnly.strings.read(key, codec)

    def write(key: Key, data: String, codec: Codec): RIO[Unit] =
      run { (s3 | key.name).putWithEncoding(data, codec).void }
  }

  val utf8: StoreUtf8[RIO] = new StoreUtf8[RIO] {
    def read(key: Key): RIO[String] =
      readOnly.utf8.read(key)

    def write(key: Key, data: String): RIO[Unit] =
      strings.write(key, data, Codec.UTF8)
  }

  val lines: StoreLines[RIO] = new StoreLines[RIO] {
    def read(key: Key, codec: Codec): RIO[List[String]] =
      readOnly.lines.read(key, codec)

    def write(key: Key, data: List[String], codec: Codec): RIO[Unit] =
      strings.write(key, Lists.prepareForFile(data), codec)
  }

  val linesUtf8: StoreLinesUtf8[RIO] = new StoreLinesUtf8[RIO] {
    def read(key: Key): RIO[List[String]] =
      readOnly.linesUtf8.read(key)

    def write(key: Key, data: List[String]): RIO[Unit] =
      lines.write(key, data, Codec.UTF8)
  }

  val unsafe: StoreUnsafe[RIO] = new StoreUnsafe[RIO] {
    def withInputStream(key: Key)(f: InputStream => RIO[Unit]): RIO[Unit] =
      readOnly.unsafe.withInputStream(key)(f)

    def withOutputStream(key: Key)(f: OutputStream => RIO[Unit]): RIO[Unit] = for {
      e <- exists(key)
      _ <- RIO.when(e, RIO.fail(s"Can not overwrite key ${key}"))
      _ <- S3OutputStream.stream((s3 / key.name).toS3Pattern, client) >>= (o => RIO.using(o.pure[RIO])(oo => f(oo)))
    } yield ()
  }

  def run[A](thunk: => S3Action[A]): RIO[A] =
    thunk.execute(client)
}

object S3Store {
  def createReadOnly(s3: S3Prefix): S3Action[ReadOnlyStore[RIO]] =
    S3Action.client.map(c => S3ReadOnlyStore(s3, c))

  def create(s3: S3Prefix): S3Action[Store[RIO]] =
    S3Action.client.map(c => S3Store(s3, c))

}
