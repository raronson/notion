package com.ambiata.notion.distcopy

import com.ambiata.mundane.path._
import com.ambiata.mundane.path.Arbitraries._
import com.ambiata.poacher.hdfs._
import com.ambiata.saws.testing.Arbitraries._
import com.ambiata.saws.s3._

import org.scalacheck._, Arbitrary._
import org.specs2._


class MappingsSpec extends Specification with ScalaCheck { def is = s2"""

 Thrift conversion is consistent

  ${ prop((m: Mappings) => Mappings.fromThrift(m.toThrift) ==== m) }

 isEmpty  $isEmpty
 distinct $distinct

"""

  def isEmpty =
    prop((mappings: Mappings) => mappings.isEmpty === mappings.mappings.isEmpty)

  def distinct =
    prop((mappings: Mappings) => mappings.distinct === Mappings(mappings.mappings.distinct))

  implicit def MappingsArbitrary: Arbitrary[Mappings] =
    Arbitrary(Gen.listOf(arbitrary[Mapping]).flatMap(i => Mappings(i.toVector)))

  implicit def MappingArbitrary: Arbitrary[Mapping] = Arbitrary(for {
    s <- arbitrary[S3Address]
    p <- arbitrary[Path].map(HdfsPath.apply)
    m <- Gen.oneOf(Gen.const(UploadMapping(p, s)), Gen.const(DownloadMapping(s, p)))
  } yield m)

}
