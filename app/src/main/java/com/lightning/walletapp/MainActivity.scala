package com.lightning.walletapp

import R.string._
import android.widget._
import com.lightning.walletapp.Utils._
import java.io.{File, FileInputStream}
import co.infinum.goldfinger.{Error => FPError}
import org.bitcoinj.core.{BlockChain, PeerGroup}
import com.google.common.io.{ByteStreams, Files}
import com.lightning.walletapp.ln.Tools.{none, runAnd}
import ln.wire.LightningMessageCodecs.walletZygoteCodec
import org.ndeftools.util.activity.NfcReaderActivity
import org.bitcoinj.wallet.WalletProtobufSerializer
import com.lightning.walletapp.helper.FingerPrint
import com.lightning.walletapp.ln.LNParams
import co.infinum.goldfinger.Goldfinger
import android.content.Intent
import org.ndeftools.Message
import scodec.bits.BitVector
import android.app.Activity
import android.os.Bundle
import android.view.View


object MainActivity {
  var proceedOnSuccess: Runnable = _
  var actOnError: PartialFunction[Throwable, Unit] = _
  val wallet = classOf[WalletActivity]

  lazy val prepareKit = {
    val stream = new FileInputStream(app.walletFile)
    val proto = WalletProtobufSerializer parseToProto stream

    app.kit = new app.WalletKit {
      wallet = (new WalletProtobufSerializer).readWallet(app.params, null, proto)
      store = new org.bitcoinj.store.SPVBlockStore(app.params, app.chainFile)
      blockChain = new BlockChain(app.params, wallet, store)
      peerGroup = new PeerGroup(app.params, blockChain)

      def startUp = try {
        setupAndStartDownload
        proceedOnSuccess.run
      } catch actOnError
    }
  }
}

class MainActivity extends NfcReaderActivity with TimerActivity { me =>
  lazy val mainFingerprintImage = findViewById(R.id.mainFingerprintImage).asInstanceOf[ImageView]
  lazy val mainFingerprint = findViewById(R.id.mainFingerprint).asInstanceOf[View]
  lazy val mainChoice = findViewById(R.id.mainChoice).asInstanceOf[View]
  lazy val gf = new Goldfinger.Builder(me).build

  override def onActivityResult(requestCode: Int, resultCode: Int, resultData: Intent) =
    if (requestCode == 101 & resultCode == Activity.RESULT_OK) restoreFromZygote(resultData)

  def INIT(state: Bundle) = {
    runAnd(me setContentView R.layout.activity_main)(me initNfc state)
    MainActivity.proceedOnSuccess = UITask { if (FingerPrint isOperational gf) proceedWithAuth else me exitTo MainActivity.wallet }
    MainActivity.actOnError = { case reThrowToEnterEmergencyActivity => UITask { throw reThrowToEnterEmergencyActivity }.run }
    Utils clickableTextField findViewById(R.id.mainGreetings)
  }

  // NFC AND SHARE

  private[this] def readFail(readingError: Throwable) = runAnd(app toast err_no_data)(next)
  def readNdefMessage(msg: Message) = <(app.TransData recordValue ndefMessageString(msg), readFail)(_ => next)

  override def onNoNfcIntentFound = {
    val processIntent = (getIntent.getFlags & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0
    val dataOpt = Seq(getIntent.getDataString, getIntent getStringExtra Intent.EXTRA_TEXT).find(null.!=)
    if (processIntent) <(dataOpt foreach app.TransData.recordValue, readFail)(_ => next) else next
  }

  def onNfcStateEnabled = none
  def onNfcStateDisabled = none
  def onNfcFeatureNotFound = none
  def onNfcStateChange(ok: Boolean) = none
  def readNonNdefMessage = me readFail null
  def readEmptyNdefMessage = me readFail null

  // STARTUP LOGIC

  def proceedWithAuth =
    gf authenticate new Goldfinger.Callback {
      mainFingerprint setVisibility View.VISIBLE
      def onError(fpError: FPError) = fpError match {
        case FPError.LOCKOUT => mainFingerprintImage.setAlpha(0.25F)
        case FPError.CANCELED => mainFingerprintImage.setAlpha(0.25F)
        case _ => app toast fpError.toString
      }

      def onSuccess(cipherText: String) = {
        mainFingerprint setVisibility View.GONE
        me exitTo MainActivity.wallet
      }
  }

  def next: Unit =
    Tuple2(app.walletFile.exists, app.isAlive) match {
      case (false, _) => mainChoice setVisibility View.VISIBLE
      case (true, true) => MainActivity.proceedOnSuccess.run

      case (true, false) =>
        MainActivity.prepareKit
        // First load wallet files, then init db, then init the rest
        LNParams setup app.kit.wallet.getKeyChainSeed.getSeedBytes
        app.kit.startAsync
    }

  // MISC

  def goRestoreWallet(view: View) = {
    val restoreOptions = getResources getStringArray R.array.restore_options
    val lst = getLayoutInflater.inflate(R.layout.frag_center_list, null).asInstanceOf[ListView]
    lst setAdapter new ArrayAdapter(me, R.layout.frag_top_tip, R.id.titleTip, restoreOptions)
    val alert = showForm(negBuilder(dialog_cancel, me getString wallet_restore, lst).create)
    lst setDividerHeight 0
    lst setDivider null

    lst setOnItemClickListener onTap {
      case 0 => rm(alert)(exitRestoreWallet)
      case 1 => proceedWithMigrationFile
    }

    def proceedWithMigrationFile = rm(alert) {
      val intent = new Intent(Intent.ACTION_OPEN_DOCUMENT) setType "text/plain"
      startActivityForResult(intent addCategory Intent.CATEGORY_OPENABLE, 101)
    }
  }

  def restoreFromZygote(intent: Intent) = {
    val databaseFile = new File(app.getDatabasePath(dbFileName).getPath)
    val inputStream = getContentResolver.openInputStream(intent.getData)
    val bitVector = BitVector.view(ByteStreams toByteArray inputStream)
    val zygote = walletZygoteCodec.decode(bitVector).require.value
    if (!databaseFile.exists) databaseFile.getParentFile.mkdirs
    Files.write(zygote.wallet.toArray, app.walletFile)
    Files.write(zygote.chain.toArray, app.chainFile)
    Files.write(zygote.db.toArray, databaseFile)
    next
  }

  def exitRestoreWallet = me exitTo classOf[WalletRestoreActivity]
  def exitCreateWallet(view: View) = me exitTo classOf[WalletCreateActivity]
}