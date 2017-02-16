package com.twitter.finagle.http2

import com.twitter.finagle.client.Transporter
import com.twitter.finagle.http
import com.twitter.finagle.http2.param._
import com.twitter.finagle.http2.transport._
import com.twitter.finagle.netty4.Netty4Transporter
import com.twitter.finagle.netty4.DirectToHeapInboundHandlerName
import com.twitter.finagle.netty4.channel.{DirectToHeapInboundHandler, BufferingChannelOutboundHandler}
import com.twitter.finagle.netty4.http.exp.{initClient, Netty4HttpTransporter}
import com.twitter.finagle.param.{Timer => TimerParam}
import com.twitter.finagle.transport.{Transport, TransportProxy}
import com.twitter.finagle.{Stack, Status}
import com.twitter.logging.Logger
import com.twitter.util._
import io.netty.channel.{
  ChannelHandlerContext,
  ChannelInboundHandlerAdapter,
  ChannelPipeline,
  ChannelPromise
}
import io.netty.handler.codec.http._
import io.netty.handler.codec.http2._
import java.net.SocketAddress
import java.security.cert.Certificate
import java.util.concurrent.atomic.AtomicReference

private[finagle] object Http2Transporter {

  def apply(params: Stack.Params)(addr: SocketAddress): Transporter[Any, Any] = {
    // current http2 client implementation doesn't support
    // netty-style backpressure
    // https://github.com/netty/netty/issues/3667#issue-69640214
    val underlying = Netty4Transporter.raw[Any, Any](
      init(params),
      addr,
      params + Netty4Transporter.Backpressure(false)
    )

    val PriorKnowledge(priorKnowledge) = params[PriorKnowledge]

    if (priorKnowledge) {
      new PriorKnowledgeTransporter(underlying, params)
    } else {
      val underlyingHttp11 = Netty4HttpTransporter(params)(addr)
      val TimerParam(timer) = params[TimerParam]

      new Http2Transporter(underlying, underlyingHttp11, timer)
    }
  }

  /**
   * Buffers until `channelActive` so we can ensure the connection preface is
   * the first message we send.
   */
  class BufferingHandler
    extends ChannelInboundHandlerAdapter
    with BufferingChannelOutboundHandler { self =>

    override def channelActive(ctx: ChannelHandlerContext): Unit = {
      ctx.fireChannelActive()
      // removing a BufferingChannelOutboundHandler writes and flushes too.
      ctx.pipeline.remove(self)
    }

    def bind(ctx: ChannelHandlerContext, addr: SocketAddress, promise: ChannelPromise): Unit = {
      ctx.bind(addr, promise)
    }
    def close(ctx: ChannelHandlerContext, promise: ChannelPromise): Unit = {
      ctx.close(promise)
    }
    def connect(
      ctx: ChannelHandlerContext,
      local: SocketAddress,
      remote: SocketAddress,
      promise: ChannelPromise
    ): Unit = {
      ctx.connect(local, remote, promise)
    }
    def deregister(ctx: ChannelHandlerContext, promise: ChannelPromise): Unit = {
      ctx.deregister(promise)
    }
    def disconnect(ctx: ChannelHandlerContext, promise: ChannelPromise): Unit = {
      ctx.disconnect(promise)
    }
    def read(ctx: ChannelHandlerContext): Unit = {
      ctx.read()
    }
  }

  // constructing an http2 cleartext transport
  private[http2] def init(params: Stack.Params): ChannelPipeline => Unit =
    { pipeline: ChannelPipeline =>
      pipeline.addLast(DirectToHeapInboundHandlerName, DirectToHeapInboundHandler)
      val connection = new DefaultHttp2Connection(false /*server*/)

      // decompresses data frames according to the content-encoding header
      val adapter = new DelegatingDecompressorFrameListener(
        connection,
        // adapts http2 to http 1.1
        Http2ClientDowngrader
      )

      val EncoderIgnoreMaxHeaderListSize(ignoreMaxHeaderListSize) =
        params[EncoderIgnoreMaxHeaderListSize]

      val connectionHandler = new RichHttpToHttp2ConnectionHandlerBuilder()
        .frameListener(adapter)
        .connection(connection)
        .initialSettings(Settings.fromParams(params))
        .encoderIgnoreMaxHeaderListSize(ignoreMaxHeaderListSize)
        .build()

      val PriorKnowledge(priorKnowledge) = params[PriorKnowledge]
      if (priorKnowledge) {
        pipeline.addLast("httpCodec", connectionHandler)
        pipeline.addLast("buffer", new BufferingHandler())
        pipeline.addLast("aggregate", new AdapterProxyChannelHandler({ pipeline =>
          pipeline.addLast("schemifier", new SchemifyingHandler("http"))
          initClient(params)(pipeline)
        }))
      } else {
        val maxChunkSize = params[http.param.MaxChunkSize].size
        val maxHeaderSize = params[http.param.MaxHeaderSize].size
        val maxInitialLineSize = params[http.param.MaxInitialLineSize].size

        val sourceCodec = new HttpClientCodec(
          maxInitialLineSize.inBytes.toInt,
          maxHeaderSize.inBytes.toInt,
          maxChunkSize.inBytes.toInt
        )

        val upgradeCodec = new Http2ClientUpgradeCodec(connectionHandler)
        val upgradeHandler = new HttpClientUpgradeHandler(sourceCodec, upgradeCodec, Int.MaxValue)
        pipeline.addLast("httpCodec", sourceCodec)
        pipeline.addLast("httpUpgradeHandler", upgradeHandler)
        pipeline.addLast(UpgradeRequestHandler.HandlerName, new UpgradeRequestHandler(params))
        initClient(params)(pipeline)
      }
    }

  def unsafeCast(t: Transport[HttpObject, HttpObject]): Transport[Any, Any] =
    t.map(_.asInstanceOf[HttpObject], _.asInstanceOf[Any])
}

/**
 * This `Transporter` makes `Transports` that speak netty http/1.1, but write
 * http/2 to the wire if they can.
 *
 * The `Transports` this hands out should be used serially, and will upgrade to
 * http/2 if possible.  It caches connections associated with a given socket
 * address, so that you can increase concurrency over a single connection by
 * getting another transport, the same way that serial protocols increase
 * concurrency.  Plainly, these transports have the same semantics as serial
 * netty4 http/1.1 transports, but allows them to be multiplexed under the hood.
 *
 * Since the decision on whether to multiplex a connection or not is made after
 * knowing the result of an upgrade, Http2Transporter is also in charge of
 * upgrading, and caches upgrade results.
 *
 * Since the cleartext upgrade is a request, it's possible to write another
 * request while the upgrade is in progress, which it does over http/1.1, and
 * doesn't attempt to upgrade.
 */
private[http2] class Http2Transporter(
    underlying: Transporter[Any, Any],
    underlyingHttp11: Transporter[Any, Any],
    implicit val timer: Timer)
  extends Transporter[Any, Any] { self =>

  private[this] def deadTransport(exn: Throwable) = new Transport[Any, Any] {
    def read(): Future[Any] = Future.never
    def write(msg: Any): Future[Unit] = Future.never
    val status: Status = Status.Closed
    def onClose: Future[Throwable] = Future.value(exn)
    def remoteAddress: SocketAddress = self.remoteAddress
    def localAddress: SocketAddress = new SocketAddress {}
    def peerCertificate: Option[Certificate] = None
    def close(deadline: Time): Future[Unit] = Future.Done
  }

  def remoteAddress: SocketAddress = underlying.remoteAddress

  private[this] val log = Logger.get()

  import Http2Transporter._

  protected[this] val cachedConnection = new AtomicReference[Future[Option[MultiplexedTransporter]]]()

  private[this] def onUpgradeFinished(
    f: Future[Option[MultiplexedTransporter]]
  ): Future[Transport[Any, Any]] = f.transform {
    case Return(Some(fn)) =>
      Future.value(fn() match {
        case Return(transport) => unsafeCast(transport)
        case Throw(exn) =>
          log.warning(
            exn,
            s"A previously successful connection to address $remoteAddress stopped being successful."
          )

          // kick us out of the cache so we can try to reestablish the connection
          cachedConnection.set(null)

          // we expect finagle to treat this specially and retry if possible
          deadTransport(exn)
      })
    case Return(None) =>
      // we didn't upgrade
      underlyingHttp11()
    case Throw(exn) =>
      log.warning(exn, s"A cached connection to address $remoteAddress was failed.")

      // kick us out of the cache so we can try to reestablish the connection
      cachedConnection.set(null)
      Future.exception(exn)
  }

  // uses http11 underneath, but marks itself as dead if the upgrade succeeds or
  // the connection fails
  private[this] def onUpgradeInProgress(
    f: Future[Option[_]]
  ): Transport[Any, Any] => Transport[Any, Any] = { http11: Transport[Any, Any] =>
    val ref = new RefTransport(http11)
    f.respond {
      case Return(None) => // we failed the upgrade, so we can keep the connection
      case _ =>
        // we shut down if we pass the upgrade or fail entirely
        // we're assuming here that a well behaved dispatcher will propagate our status to a pool
        // that will close us
        // TODO: if we fail entirely, we should try to reregister on the new connection attempt
        ref.update { trans =>
          new TransportProxy[Any, Any](trans) {
            def write(any: Any): Future[Unit] = trans.write(any)
            def read(): Future[Any] = trans.read()
            override def status: Status = Status.Closed
          }
        }
    }
    ref
  }

  private[this] def upgrade(): Future[Transport[Any, Any]] = {
    val conn: Future[Transport[Any, Any]] = underlying()
    val p = Promise[Option[MultiplexedTransporter]]()
    if (cachedConnection.compareAndSet(null, p)) {
      conn.transform {
        case Return(trans) =>
          val ref = new RefTransport(trans)
          ref.update { t =>
            t.onClose.ensure {
              cachedConnection.compareAndSet(p, null)
            }
            new Http2UpgradingTransport(t, ref, p)
          }
          Future.value(ref)
        case Throw(e) =>
          cachedConnection.compareAndSet(p, null)
          p.setException(e)
          Future.exception(e)
      }
    } else apply()
  }

  final def apply(): Future[Transport[Any, Any]] =
    Option(cachedConnection.get) match {
      case Some(f) =>
        if (f.isDefined) {
          onUpgradeFinished(f)
        } else {
          // fall back to http/1.1 while upgrading
          val conn = underlyingHttp11()
          conn.map(onUpgradeInProgress(f))
        }
      case None =>
        upgrade()
    }

  override def close(deadline: Time): Future[Unit] = {
    val maybeClose: Option[Closable] => Future[Unit] = {
      case Some(closable) => closable.close(deadline)
      case None => Future.Done
    }

    val f = cachedConnection.get

    if (f == null) Future.Done
    // TODO: shouldn't rescue on timeout
    else f.flatMap(maybeClose).by(deadline).rescue { case exn: Throwable => Future.Done }
  }
}
