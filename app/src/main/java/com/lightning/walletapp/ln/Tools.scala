package com.lightning.walletapp.ln

import fr.acinq.bitcoin.Protocol.{One, Zeroes}
import fr.acinq.bitcoin.{Crypto, LexicographicalOrdering}
import com.lightning.walletapp.ln.wire.UpdateAddHtlc
import com.lightning.walletapp.ln.Tools.runAnd
import fr.acinq.bitcoin.Crypto.PrivateKey
import language.implicitConversions
import crypto.RandomGenerator
import scodec.bits.ByteVector
import scala.util.Try
import java.util


object \ {
  // Matching Tuple2 via arrows with much less noise
  def unapply[A, B](t2: (A, B) /* Got a tuple */) = Some(t2)
}

object Tools {
  type Bytes = Array[Byte]
  val random = new RandomGenerator
  val emptyChanges = Changes(Vector.empty, Vector.empty, Vector.empty)
  val nextDummyHtlc = UpdateAddHtlc(Zeroes, id = -1, LNParams.minCapacityMsat, One, expiry = 144 * 3)
  def randomPrivKey = PrivateKey(ByteVector.view(random getBytes 32), compressed = true)
  def log(consoleMessage: String): Unit = android.util.Log.d("LN", consoleMessage)
  def wrap(run: => Unit)(go: => Unit) = try go catch none finally run
  def bin2readable(bin: Bytes) = new String(bin, "UTF-8")
  def none: PartialFunction[Any, Unit] = { case _ => }
  def runAnd[T](result: T)(action: Any): T = result

  def toMap[T, K, V](source: Seq[T], keyFun: T => K, valFun: T => V): Map[K, V] = {
    val premap = for (mapElement <- source) yield keyFun(mapElement) -> valFun(mapElement)
    premap.toMap
  }

  def sign(data: ByteVector, pk: PrivateKey) = Try {
    Crypto encodeSignature Crypto.sign(data, pk)
  } getOrElse ByteVector.empty

  def fromShortId(id: Long) = {
    val blockHeight = id.>>(40).&(0xFFFFFF).toInt
    val txIndex = id.>>(16).&(0xFFFFFF).toInt
    val outputIndex = id.&(0xFFFF).toInt
    (blockHeight, txIndex, outputIndex)
  }

  def toShortIdOpt(blockHeight: Long, txIndex: Long, outputIndex: Long): Option[Long] = {
    val result = blockHeight.&(0xFFFFFFL).<<(40) | txIndex.&(0xFFFFFFL).<<(16) | outputIndex.&(0xFFFFL)
    if (txIndex < 0) None else Some(result)
  }

  def toLongId(txid: ByteVector, fundingOutputIndex: Int) = {
    require(fundingOutputIndex < 65536, "Index is larger than 65535")
    val part2 = txid(30).^(fundingOutputIndex >> 8).toByte
    val part3 = txid(31).^(fundingOutputIndex).toByte
    txid.take(30) :+ part2 :+ part3
  }

  def hostedChanId(pubkey1: ByteVector, pubkey2: ByteVector) = {
    val pubkey1First: Boolean = LexicographicalOrdering.isLessThan(pubkey1, pubkey2)
    if (pubkey1First) Crypto.sha256(pubkey1 ++ pubkey2) else Crypto.sha256(pubkey2 ++ pubkey1)
  }
}

object Features {
  val OPTION_DATA_LOSS_PROTECT_MANDATORY = 0
  val OPTION_DATA_LOSS_PROTECT_OPTIONAL = 1

  val VARIABLE_LENGTH_ONION_MANDATORY = 8
  val VARIABLE_LENGTH_ONION_OPTIONAL = 9

  implicit def binData2BitSet(featuresBinaryData: ByteVector): util.BitSet = util.BitSet.valueOf(featuresBinaryData.reverse.toArray)
  def dataLossProtect(bitset: util.BitSet) = bitset.get(OPTION_DATA_LOSS_PROTECT_OPTIONAL) || bitset.get(OPTION_DATA_LOSS_PROTECT_MANDATORY)
  def variableLengthOnion(bitset: util.BitSet) = bitset.get(VARIABLE_LENGTH_ONION_OPTIONAL) || bitset.get(VARIABLE_LENGTH_ONION_MANDATORY)
  def isBitSet(position: Int, bitField: Byte): Boolean = bitField.&(1 << position) == (1 << position)

  def areSupported(bitset: util.BitSet): Boolean = {
    val mandatoryFeatures: Set[Int] = Set(OPTION_DATA_LOSS_PROTECT_MANDATORY)
    def mandatoryUnsupported(n: Int) = bitset.get(n) && !mandatoryFeatures.contains(n)
    !(0 until bitset.length by 2 exists mandatoryUnsupported)
  }
}

class LightningTypedErrorException(code: ByteVector) extends RuntimeException(code.toHex)
class LightningException(reason: String = "Failure") extends RuntimeException(reason)
case class CMDAddImpossible(rd: RoutingData, code: Int) extends LightningException

// STATE MACHINE

abstract class StateMachine[T] {
  def become(freshData: T, freshState: String) =
    runAnd { data = freshData } { state = freshState }

  def doProcess(change: Any)
  var state: String = _
  var data: T = _
}