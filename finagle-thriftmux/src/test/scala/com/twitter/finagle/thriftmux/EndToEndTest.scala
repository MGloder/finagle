package com.twitter.finagle.thriftmux

import com.twitter.conversions.DurationOps._
import com.twitter.finagle.Stack.Transformer
import com.twitter.finagle._
import com.twitter.finagle.builder.{ClientBuilder, ServerBuilder}
import com.twitter.finagle.client.StackClient
import com.twitter.finagle.context.Contexts
import com.twitter.finagle.dispatch.PipeliningDispatcher
import com.twitter.finagle.param.{Label, Stats, Tracer => PTracer}
import com.twitter.finagle.service._
import com.twitter.finagle.stats._
import com.twitter.finagle.thrift.{
  ClientId,
  MethodMetadata,
  Protocols,
  RichServerParam,
  ThriftClientRequest
}
import com.twitter.finagle.thriftmux.service.ThriftMuxResponseClassifier
import com.twitter.finagle.thriftmux.thriftscala._
import com.twitter.finagle.tracing.Annotation.{ClientSend, ServerRecv}
import com.twitter.finagle.tracing._
import com.twitter.finagle.transport.{Transport, TransportContext}
import com.twitter.finagle.util.DefaultTimer
import com.twitter.io.Buf
import com.twitter.scrooge
import com.twitter.scrooge.ThriftMethod
import com.twitter.util._
import com.twitter.util.tunable.Tunable
import java.net.{InetAddress, InetSocketAddress, SocketAddress}
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import org.apache.thrift.TApplicationException
import org.apache.thrift.protocol._
import org.scalactic.source.Position
import org.scalatest.concurrent.{Eventually, IntegrationPatience}
import org.scalatest.junit.AssertionsForJUnit
import org.scalatest.{FunSuite, Tag}
import scala.collection.JavaConverters._
import scala.language.reflectiveCalls

class EndToEndTest
    extends FunSuite
    with AssertionsForJUnit
    with Eventually
    with IntegrationPatience {

  def await[T](a: Awaitable[T], d: Duration = 5.seconds): T =
    Await.result(a, d)

  // turn off failure detector since we don't need it for these tests.
  override def test(testName: String, testTags: Tag*)(f: => Any)(implicit pos: Position): Unit = {
    super.test(testName, testTags: _*) {
      liveness.sessionFailureDetector.let("none") { f }
    }
  }

  def clientImpl: ThriftMux.Client =
    ThriftMux.client.copy(muxer = ThriftMux.Client.standardMuxer)

  def serverImpl: ThriftMux.Server = {
    // need to copy the params since the `.server` call sets the Label to "thrift" into
    // the current muxers params
    val serverParams = ThriftMux.server.params
    ThriftMux.server.copy(muxer = ThriftMux.Server.defaultMuxer.withParams(serverParams))
  }

  // Used for testing ThriftMux's Context functionality. Duplicated from the
  // finagle-mux package as a workaround because you can't easily depend on a
  // test package in Maven.
  case class TestContext(buf: Buf)

  val testContext = new Contexts.broadcast.Key[TestContext]("com.twitter.finagle.mux.MuxContext") {
    def marshal(tc: TestContext): Buf = tc.buf
    def tryUnmarshal(buf: Buf): Try[TestContext] = Return(TestContext(buf))
  }

  trait ThriftMuxTestServer {
    val server = serverImpl.serveIface(
      new InetSocketAddress(InetAddress.getLoopbackAddress, 0),
      new TestService.MethodPerEndpoint {
        def query(x: String): Future[String] = {
          assert(
            MethodMetadata.current.exists {
              methodMetadata =>
                methodMetadata.methodName == TestService.Query.name &&
                methodMetadata.serviceName == TestService.Query.serviceName &&
                methodMetadata.argsClass.getName == TestService.Query.argsCodec.metaData.structClass.getName &&
                methodMetadata.resultClass.getName == TestService.Query.responseCodec.metaData.structClass.getName
            }
          )
          (Contexts.broadcast.get(testContext), Dtab.local) match {
            case (None, Dtab.empty) =>
              Future.value(x + x)

            case (Some(TestContext(buf)), _) =>
              val Buf.Utf8(str) = buf
              Future.value(str)

            case (_, dtab) =>
              Future.value(dtab.show)
          }
        }
        def question(y: String): Future[String] = {
          assert(
            MethodMetadata.current.exists {
              methodMetadata =>
                methodMetadata.methodName == TestService.Question.name &&
                methodMetadata.serviceName == TestService.Question.serviceName &&
                methodMetadata.argsClass.getName == TestService.Question.argsCodec.metaData.structClass.getName &&
                methodMetadata.resultClass.getName == TestService.Question.responseCodec.metaData.structClass.getName
            }
          )
          (Contexts.broadcast.get(testContext), Dtab.local) match {
            case (None, Dtab.empty) =>
              Future.value(y + y)

            case (Some(TestContext(buf)), _) =>
              val Buf.Utf8(str) = buf
              Future.value(str)

            case (_, dtab) =>
              Future.value(dtab.show)
          }
        }
        def inquiry(z: String): Future[String] = {
          assert(
            MethodMetadata.current.exists {
              methodMetadata =>
                methodMetadata.methodName == TestService.Inquiry.name &&
                methodMetadata.serviceName == TestService.Inquiry.serviceName &&
                methodMetadata.argsClass.getName == TestService.Inquiry.argsCodec.metaData.structClass.getName &&
                methodMetadata.resultClass.getName == TestService.Inquiry.responseCodec.metaData.structClass.getName
            }
          )
          (Contexts.broadcast.get(testContext), Dtab.local) match {
            case (None, Dtab.empty) =>
              Future.value(z + z)

            case (Some(TestContext(buf)), _) =>
              val Buf.Utf8(str) = buf
              Future.value(str)

            case (_, dtab) =>
              Future.value(dtab.show)
          }
        }
      }
    )
  }

  test("end-to-end thriftmux") {
    new ThriftMuxTestServer {
      val client = clientImpl.build[TestService.MethodPerEndpoint](
        Name.bound(Address(server.boundAddress.asInstanceOf[InetSocketAddress])),
        "client"
      )
      assert(await(client.query("ok")) == "okok")

      await(server.close())
    }
  }

  test("end-to-end thriftmux: propagate Dtab.local") {
    new ThriftMuxTestServer {
      val client =
        clientImpl.build[TestService.MethodPerEndpoint](
          Name.bound(Address(server.boundAddress.asInstanceOf[InetSocketAddress])),
          "client"
        )

      assert(await(client.query("ok")) == "okok")

      Dtab.unwind {
        Dtab.local = Dtab.read("/foo=>/bar")
        assert(await(client.query("ok")) == "/foo=>/bar")
      }

      await(server.close())
    }
  }

  test("thriftmux server + Finagle thrift client") {
    new ThriftMuxTestServer {
      val client =
        Thrift.client.build[TestService.MethodPerEndpoint](
          Name.bound(Address(server.boundAddress.asInstanceOf[InetSocketAddress])),
          "client"
        )
      1 to 5 foreach { _ =>
        assert(await(client.query("ok")) == "okok")
      }

      await(server.close())
    }
  }

  // While we're supporting both old & new APIs, test the cross-product
  test("Mix of client and server creation styles") {
    val clientId = ClientId("test.service")

    def servers(pf: TProtocolFactory): Seq[(String, Closable, Int)] = {
      val iface = new TestService.MethodPerEndpoint {
        def query(x: String): Future[String] =
          if (x.isEmpty) Future.value(ClientId.current.map(_.name).getOrElse(""))
          else Future.value(x + x)
        def question(y: String): Future[String] =
          if (y.isEmpty) Future.value(ClientId.current.map(_.name).getOrElse(""))
          else Future.value(y + y)
        def inquiry(z: String): Future[String] =
          if (z.isEmpty) Future.value(ClientId.current.map(_.name).getOrElse(""))
          else Future.value(z + z)
      }

      val pfSvc = new TestService$FinagleService(iface, pf)
      val builder = ServerBuilder()
        .stack(serverImpl.withProtocolFactory(pf))
        .name("ThriftMuxServer")
        .bindTo(new InetSocketAddress(0))
        .build(pfSvc)
      val netty4 = serverImpl
        .withProtocolFactory(pf)
        .serveIface(new InetSocketAddress(InetAddress.getLoopbackAddress, 0), iface)

      def port(socketAddr: SocketAddress): Int =
        socketAddr.asInstanceOf[InetSocketAddress].getPort

      Seq(
        ("ServerBuilder", builder, port(builder.boundAddress)),
        ("ThriftMux", netty4, port(netty4.boundAddress))
      )
    }

    def clients(
      pf: TProtocolFactory,
      port: Int
    ): Seq[(String, TestService$FinagleClient, Closable)] = {
      val dest = s"localhost:$port"
      val builder = ClientBuilder()
        .stack(clientImpl.withClientId(clientId).withProtocolFactory(pf))
        .dest(dest)
        .build()
      val thriftBuilder = ClientBuilder()
        .stack(Thrift.client.withProtocolFactory(pf).withClientId(clientId))
        .hostConnectionLimit(1)
        .dest(dest)
        .build()
      val thriftProto = Thrift.client
        .withClientId(clientId)
        .withProtocolFactory(pf)
        .newService(dest)
      val newProto = clientImpl
        .withClientId(clientId)
        .withProtocolFactory(pf)
        .newService(dest)

      def toIface(svc: Service[ThriftClientRequest, Array[Byte]]): TestService$FinagleClient =
        new TestService.FinagledClient(svc, pf)

      Seq(
        ("ThriftMux via ClientBuilder", toIface(builder), builder),
        ("Thrift via ClientBuilder", toIface(thriftBuilder), thriftBuilder),
        ("Thrift via proto", toIface(thriftProto), thriftProto),
        ("ThriftMux proto", toIface(newProto), newProto)
      )
    }

    for {
      pf <- Seq(new TCompactProtocol.Factory, Protocols.binaryFactory())
      (serverWhich, serverClosable, port) <- servers(pf)
    } {
      for {
        (clientWhich, clientIface, clientClosable) <- clients(pf, port)
      } withClue(s"Server ($serverWhich), Client ($clientWhich) client with protocolFactory $pf") {
        1.to(5).foreach { _ =>
          assert(await(clientIface.query("ok")) == "okok")
        }
        assert(await(clientIface.query("")) == clientId.name)
        await(clientClosable.close())
      }
      await(serverClosable.close())
    }
  }

  test(
    "thriftmux server + Finagle thrift client: client should receive a " +
      "TApplicationException if the server throws an unhandled exception"
  ) {
    val server = serverImpl.serveIface(
      new InetSocketAddress(InetAddress.getLoopbackAddress, 0),
      new TestService.MethodPerEndpoint {
        def query(x: String): Future[String] = throw new Exception("sad panda")
        def question(y: String): Future[String] = throw new Exception("sad panda")
        def inquiry(z: String): Future[String] = throw new Exception("sad panda")
      }
    )
    val client = Thrift.client.build[TestService.MethodPerEndpoint](
      Name.bound(Address(server.boundAddress.asInstanceOf[InetSocketAddress])),
      "aclient"
    )
    val thrown = intercept[TApplicationException] { await(client.query("ok")) }
    assert(thrown.getMessage == "Internal error processing query: 'java.lang.Exception: sad panda'")

    await(server.close())
  }

  test("thriftmux server + Finagle thrift client: traceId should be passed from client to server") {
    @volatile var cltTraceId: Option[TraceId] = None
    @volatile var srvTraceId: Option[TraceId] = None
    val tracer = new Tracer {
      def record(record: Record): Unit = {
        record match {
          case Record(id, _, ServerRecv, _) => srvTraceId = Some(id)
          case Record(id, _, ClientSend, _) => cltTraceId = Some(id)
          case _ =>
        }
      }
      def sampleTrace(traceId: TraceId): Option[Boolean] = None
    }

    val server = serverImpl
      .configured(PTracer(tracer))
      .serveIface(
        new InetSocketAddress(InetAddress.getLoopbackAddress, 0),
        new TestService.MethodPerEndpoint {
          def query(x: String): Future[String] = Future.value(x + x)
          def question(y: String): Future[String] = Future.value(y + y)
          def inquiry(z: String): Future[String] = Future.value(z + z)
        }
      )

    val client = Thrift.client
      .configured(PTracer(tracer))
      .build[TestService.MethodPerEndpoint](
        Name.bound(Address(server.boundAddress.asInstanceOf[InetSocketAddress])),
        "client"
      )

    await(client.query("ok"))

    (srvTraceId, cltTraceId) match {
      case (Some(id1), Some(id2)) => assert(id1 == id2)
      case _ =>
        assert(
          false,
          s"the trace ids sent by client and received by server do not match srv: $srvTraceId clt: $cltTraceId"
        )
    }

    await(server.close())
  }

  test(
    "thriftmux server + Finagle thrift client: 128 bit traceId should be passed from client to server"
  ) {
    @volatile var cltTraceId: Option[TraceId] = None
    @volatile var srvTraceId: Option[TraceId] = None
    val tracer = new Tracer {
      def record(record: Record): Unit = {
        record match {
          case Record(id, _, ServerRecv, _) => srvTraceId = Some(id)
          case Record(id, _, ClientSend, _) => cltTraceId = Some(id)
          case _ =>
        }
      }
      def sampleTrace(traceId: TraceId): Option[Boolean] = None
    }

    Time.withTimeAt(Time.fromSeconds(1465510280)) { tc => // Thursday, June 9, 2016 10:11:20 PM
      traceId128Bit.let(true) {
        val server = ThriftMux.server
          .configured(PTracer(tracer))
          .serveIface(
            new InetSocketAddress(InetAddress.getLoopbackAddress, 0),
            new TestService.MethodPerEndpoint {
              def query(x: String): Future[String] = Future.value(x + x)
              def question(y: String): Future[String] = Future.value(y + y)
              def inquiry(z: String): Future[String] = Future.value(z + z)
            }
          )

        val client = Thrift.client
          .configured(PTracer(tracer))
          .build[TestService.MethodPerEndpoint](
            Name.bound(Address(server.boundAddress.asInstanceOf[InetSocketAddress])),
            "client"
          )

        await(client.query("ok"))

        (srvTraceId, cltTraceId) match {
          case (Some(id1), Some(id2)) =>
            assert(id1 == id2)
            assert(id1.traceIdHigh.isDefined)
            assert(id2.traceIdHigh.isDefined)
            assert(id1.traceIdHigh.get.toString.startsWith("5759e988"))
            assert(id2.traceIdHigh.get.toString.startsWith("5759e988"))
          case _ =>
            assert(
              false,
              s"the trace ids sent by client and received by server do not match srv: $srvTraceId clt: $cltTraceId"
            )
        }

        await(server.close())
      }
    }
  }

  test("client + server: rpc name should be included in trace") {
    def tracer(rpcName: AtomicReference[String]): Tracer = new Tracer {
      def record(record: Record): Unit = {
        record match {
          case Record(_, _, Annotation.Rpc(name), _) =>
            rpcName.compareAndSet(null, name)
          case _ =>
        }
      }
      def sampleTrace(traceId: TraceId): Option[Boolean] = Tracer.SomeTrue
    }

    val serverRpc = new AtomicReference[String]()
    val server = serverImpl
      .withTracer(tracer(serverRpc))
      .serveIface(
        new InetSocketAddress(InetAddress.getLoopbackAddress, 0),
        new TestService.MethodPerEndpoint {
          def query(x: String): Future[String] = Future.value(x)
          def question(y: String): Future[String] = ???
          def inquiry(z: String): Future[String] = ???
        }
      )

    val clientRpc = new AtomicReference[String]()
    val client = clientImpl
      .withTracer(tracer(clientRpc))
      .build[TestService.MethodPerEndpoint](
        Name.bound(Address(server.boundAddress.asInstanceOf[InetSocketAddress])),
        "client"
      )

    await(client.query("ok"))

    assert("query" == serverRpc.get)
    assert("query" == clientRpc.get)

    await(server.close())
  }

  test("thriftmux server + Finagle thrift client: clientId should be passed from client to server") {
    val server = serverImpl.serveIface(
      new InetSocketAddress(InetAddress.getLoopbackAddress, 0),
      new TestService.MethodPerEndpoint {
        def query(x: String): Future[String] =
          Future.value(ClientId.current.map(_.name).getOrElse("No ClientId"))
        def question(y: String): Future[String] =
          Future.value(ClientId.current.map(_.name).getOrElse("No ClientId"))
        def inquiry(z: String): Future[String] =
          Future.value(ClientId.current.map(_.name).getOrElse("No ClientId"))
      }
    )

    val clientId = "test.service"
    val client = Thrift.client
      .withClientId(ClientId(clientId))
      .build[TestService.MethodPerEndpoint](
        Name.bound(Address(server.boundAddress.asInstanceOf[InetSocketAddress])),
        "client"
      )

    1 to 5 foreach { _ =>
      assert(await(client.query("ok")) == clientId)
    }

    await(server.close())
  }

  test("thriftmux server + Finagle thrift client: ClientId should not be overridable externally") {
    val server = serverImpl.serveIface(
      new InetSocketAddress(InetAddress.getLoopbackAddress, 0),
      new TestService.MethodPerEndpoint {
        def query(x: String): Future[String] =
          Future.value(ClientId.current.map(_.name).getOrElse("No ClientId"))
        def question(y: String): Future[String] =
          Future.value(ClientId.current.map(_.name).getOrElse("No ClientId"))
        def inquiry(z: String): Future[String] =
          Future.value(ClientId.current.map(_.name).getOrElse("No ClientId"))
      }
    )

    val clientId = ClientId("test.service")
    val otherClientId = ClientId("other.bar")
    val client = Thrift.client
      .withClientId(clientId)
      .build[TestService.MethodPerEndpoint](
        Name.bound(Address(server.boundAddress.asInstanceOf[InetSocketAddress])),
        "client"
      )

    1 to 5 foreach { _ =>
      otherClientId.asCurrent {
        assert(await(client.query("ok")) == clientId.name)
      }
    }

    await(server.close())
  }

  test("RemoteInfo's upstreamId is correct") {
    val slowServer = serverImpl
      .withLabel("slowServer")
      .serveIface(
        new InetSocketAddress(InetAddress.getLoopbackAddress, 0),
        new TestService.MethodPerEndpoint {
          def query(x: String): Future[String] = {
            val clientId = ClientId("second_hop_clientId")
            assert(ClientId.current.contains(clientId))
            Future.value("cool").delayed(1.second)(DefaultTimer)
          }
          def question(y: String): Future[String] = {
            val clientId = ClientId("second_hop_clientId")
            assert(ClientId.current.contains(clientId))
            Future.value("cool").delayed(1.second)(DefaultTimer)
          }
          def inquiry(z: String): Future[String] = {
            val clientId = ClientId("second_hop_clientId")
            assert(ClientId.current.contains(clientId))
            Future.value("cool").delayed(1.second)(DefaultTimer)
          }
        }
      )

    val slowClient = clientImpl
      .withLabel("middle_service_label")
      .withClientId(ClientId("second_hop_clientId"))
      .withRequestTimeout(10.millis) // smaller than the delay used in slowServer
      .servicePerEndpoint[TestService.ServicePerEndpoint](
        s"localhost:${slowServer.boundAddress.asInstanceOf[InetSocketAddress].getPort}",
        "middle_service_label"
      )

    val theServer = serverImpl
      .withLabel("middleServer")
      .serveIface(
        new InetSocketAddress(InetAddress.getLoopbackAddress, 0),
        new TestService.MethodPerEndpoint {
          def query(x: String): Future[String] = {
            val clientId = ClientId("theClient_clientId")
            assert(ClientId.current.contains(clientId))
            slowClient.query(TestService.Query.Args("abc"))
          }
          def question(y: String): Future[String] = {
            val clientId = ClientId("theClient_clientId")
            assert(ClientId.current.contains(clientId))
            slowClient.query(TestService.Query.Args("abc"))
          }
          def inquiry(z: String): Future[String] = {
            val clientId = ClientId("theClient_clientId")
            assert(ClientId.current.contains(clientId))
            slowClient.query(TestService.Query.Args("abc"))
          }
        }
      )

    val theClient = clientImpl
      .withLabel("the_service_label")
      .withClientId(ClientId("theClient_clientId"))
      .servicePerEndpoint[TestService.ServicePerEndpoint](
        s"localhost:${theServer.boundAddress.asInstanceOf[InetSocketAddress].getPort}",
        "the_service_label"
      )

    val ex = intercept[Exception] {
      await(theClient.query(TestService.Query.Args("abc")))
    }
    val msg = ex.getMessage
    assert(msg.contains("Upstream id: theClient_clientId"))
    assert(msg.contains("Downstream label: middle_service_label"))

    await(theServer.close())
    await(slowServer.close())
  }

  test("thriftmux server + Finagle thrift client: server.close()") {
    new ThriftMuxTestServer {
      val client = Thrift.client.build[TestService.MethodPerEndpoint](
        Name.bound(Address(server.boundAddress.asInstanceOf[InetSocketAddress])),
        "client"
      )

      assert(await(client.query("ok")) == "okok")
      await(server.close())

      // This request fails and is not requeued because there are
      // no Open service factories in the load balancer.
      try await(client.query("ok"))
      catch {
        case Failure(Some(_: ConnectionFailedException)) => // ok
      }

      // Subsequent requests are failed fast since there are (still) no
      // Open service factories in the load balancer. Again, no requeues
      // are attempted.
      intercept[FailedFastException] {
        await(client.query("ok"))
      }
    }
  }

  test("thriftmux server + thriftmux client: ClientId should not be overridable externally") {
    val server = serverImpl.serveIface(
      new InetSocketAddress(InetAddress.getLoopbackAddress, 0),
      new TestService.MethodPerEndpoint {
        def query(x: String): Future[String] =
          Future.value(ClientId.current.map(_.name).getOrElse(""))
        def question(y: String): Future[String] =
          Future.value(ClientId.current.map(_.name).getOrElse(""))
        def inquiry(z: String): Future[String] =
          Future.value(ClientId.current.map(_.name).getOrElse(""))
      }
    )

    val clientId = ClientId("test.service")
    val otherClientId = ClientId("other.bar")
    val client = clientImpl
      .withClientId(clientId)
      .build[TestService.MethodPerEndpoint](
        Name.bound(Address(server.boundAddress.asInstanceOf[InetSocketAddress])),
        "client"
      )

    otherClientId.asCurrent {
      assert(await(client.query("ok")) == clientId.name)
    }

    await(server.close())
  }

  // Skip upnegotiation.
  object OldPlainThriftClient extends Thrift.Client(stack = StackClient.newStack)

  test("thriftmux server + Finagle thrift client w/o protocol upgrade") {
    new ThriftMuxTestServer {
      val client = OldPlainThriftClient.build[TestService.MethodPerEndpoint](
        Name.bound(Address(server.boundAddress.asInstanceOf[InetSocketAddress])),
        "client"
      )
      assert(await(client.query("ok")) == "okok")

      await(server.close())
    }
  }

  test("thriftmux server + Finagle thrift client w/o protocol upgrade: server.close()") {
    new ThriftMuxTestServer {
      val client = OldPlainThriftClient.build[TestService.MethodPerEndpoint](
        Name.bound(Address(server.boundAddress.asInstanceOf[InetSocketAddress])),
        "client"
      )

      assert(await(client.query("ok")) == "okok")
      await(server.close())
      val ex = intercept[Failure] {
        await(client.query("ok"))
      }

      ex match {
        case Failure(Some(_: ConnectionFailedException)) => ()
        case other => fail(s"Expected Failure(Some(ConnectionFailedException)) but found $other")
      }

      intercept[FailedFastException] {
        await(client.query("ok"))
      }

      await(server.close())
    }
  }

  test(
    "thriftmux server + thrift client: client should receive a " +
      "TApplicationException if the server throws an unhandled exception"
  ) {
    val server = serverImpl.serveIface(
      new InetSocketAddress(InetAddress.getLoopbackAddress, 0),
      new TestService.MethodPerEndpoint {
        def query(x: String): Future[String] = throw new Exception("sad panda")
        def question(y: String): Future[String] = throw new Exception("sad panda")
        def inquiry(z: String): Future[String] = throw new Exception("sad panda")
      }
    )
    val client = OldPlainThriftClient.build[TestService.MethodPerEndpoint](
      Name.bound(Address(server.boundAddress.asInstanceOf[InetSocketAddress])),
      "aclient"
    )
    val thrown = intercept[TApplicationException] { await(client.query("ok")) }
    assert(thrown.getMessage == "Internal error processing query: 'java.lang.Exception: sad panda'")
  }

  test("thriftmux server should count exceptions as failures") {
    val iface = new TestService.MethodPerEndpoint {
      def query(x: String): Future[String] = Future.exception(new RuntimeException("lolol"))
      def question(y: String): Future[String] = Future.exception(new RuntimeException("lolol"))
      def inquiry(z: String): Future[String] = Future.exception(new RuntimeException("lolol"))
    }
    val svc = new TestService.FinagledService(iface, Protocols.binaryFactory())

    val sr = new InMemoryStatsReceiver()
    val server = serverImpl
      .withStatsReceiver(sr)
      .serve(new InetSocketAddress(InetAddress.getLoopbackAddress, 0), svc)
    val client =
      clientImpl.build[TestService.MethodPerEndpoint](
        Name.bound(Address(server.boundAddress.asInstanceOf[InetSocketAddress])),
        "client"
      )

    val ex = intercept[TApplicationException] {
      await(client.query("hi"))
    }
    assert(ex.getMessage.contains("lolol"))
    assert(sr.counters(Seq("thrift", "requests")) == 1)
    assert(sr.counters(Seq("thrift", "success")) == 0)
    assert(sr.counters(Seq("thrift", "failures")) == 1)

    await(server.close())
  }

  test("thriftmux client default failure classification") {
    val iface = new TestService.MethodPerEndpoint {
      def query(x: String): Future[String] = Future.exception(new InvalidQueryException(x.length))
      def question(y: String): Future[String] =
        Future.exception(new InvalidQueryException(y.length))
      def inquiry(z: String): Future[String] = Future.exception(new InvalidQueryException(z.length))
    }
    val svc = new TestService.FinagledService(iface, Protocols.binaryFactory())

    val server = serverImpl
      .configured(Stats(NullStatsReceiver))
      .serve(new InetSocketAddress(InetAddress.getLoopbackAddress, 0), svc)

    val sr = new InMemoryStatsReceiver()
    val client =
      clientImpl
        .withStatsReceiver(sr)
        .build[TestService.MethodPerEndpoint](
          Name.bound(Address(server.boundAddress.asInstanceOf[InetSocketAddress])),
          "client"
        )

    val ex = intercept[InvalidQueryException] {
      await(client.query("hi"))
    }
    assert("hi".length == ex.errorCode)
    assert(sr.counters(Seq("client", "requests")) == 1)
    // by default, the filter/service are Array[Byte] => Array[Byte]
    // which in turn means thrift exceptions are just "successful"
    // arrays of bytes...
    assert(sr.counters(Seq("client", "success")) == 1)

    await(server.close())
  }

  private val scalaClassifier: ResponseClassifier =
    ResponseClassifier.named("EndToEndTestClassifier") {
      case ReqRep(TestService.Query.Args(x), Throw(_: InvalidQueryException)) if x == "ok" =>
        ResponseClass.Success
      case ReqRep(_, Throw(_: InvalidQueryException)) =>
        ResponseClass.NonRetryableFailure
      case ReqRep(_, Throw(_: RequestTimeoutException)) |
          ReqRep(_, Throw(_: java.util.concurrent.TimeoutException)) =>
        ResponseClass.Success
      case ReqRep(_, Return(_: String)) =>
        ResponseClass.NonRetryableFailure
    }

  private val javaClassifier: ResponseClassifier = {
    case ReqRep(x: thriftjava.TestService.query_args, Throw(_: thriftjava.InvalidQueryException))
        if x.x == "ok" =>
      ResponseClass.Success
    case ReqRep(_, Throw(_: thriftjava.InvalidQueryException)) =>
      ResponseClass.NonRetryableFailure
    case ReqRep(_, Return(s: String)) =>
      ResponseClass.NonRetryableFailure
  }

  val iface = new TestService.MethodPerEndpoint {
    def query(x: String): Future[String] =
      if (x == "safe")
        Future.value("safe")
      else if (x == "slow")
        Future.sleep(1.second)(DefaultTimer).before(Future.value("slow"))
      else
        Future.exception(new InvalidQueryException(x.length))
    def question(y: String): Future[String] =
      if (y == "safe")
        Future.value("safe")
      else if (y == "slow")
        Future.sleep(1.second)(DefaultTimer).before(Future.value("slow"))
      else
        Future.exception(new InvalidQueryException(y.length))
    def inquiry(z: String): Future[String] =
      if (z == "safe")
        Future.value("safe")
      else if (z == "slow")
        Future.sleep(1.second)(DefaultTimer).before(Future.value("slow"))
      else
        Future.exception(new InvalidQueryException(z.length))
  }

  private class TestServiceImpl extends thriftjava.TestService.ServiceIface {
    def query(x: String): Future[String] =
      if (x == "safe")
        Future.value("safe")
      else if (x == "slow")
        Future.sleep(1.second)(DefaultTimer).before(Future.value("slow"))
      else
        Future.exception(new thriftjava.InvalidQueryException(x.length))
    def question(y: String): Future[String] =
      if (y == "safe")
        Future.value("safe")
      else if (y == "slow")
        Future.sleep(1.second)(DefaultTimer).before(Future.value("slow"))
      else
        Future.exception(new thriftjava.InvalidQueryException(y.length))
    def inquiry(z: String): Future[String] =
      if (z == "safe")
        Future.value("safe")
      else if (z == "slow")
        Future.sleep(1.second)(DefaultTimer).before(Future.value("slow"))
      else
        Future.exception(new thriftjava.InvalidQueryException(z.length))
  }

  def serverForClassifier(): ListeningServer = {
    val svc = new TestService.FinagledService(iface, RichServerParam())
    serverImpl
      .withStatsReceiver(NullStatsReceiver)
      .serve(new InetSocketAddress(InetAddress.getLoopbackAddress, 0), svc)
  }

  private def testScalaClientFailureClassification(
    sr: InMemoryStatsReceiver,
    client: TestService.MethodPerEndpoint
  ): Unit = {
    val ex = intercept[InvalidQueryException] {
      await(client.query("hi"))
    }
    assert("hi".length == ex.errorCode)
    assert(sr.counters(Seq("client", "requests")) == 1)
    assert(sr.counters(Seq("client", "success")) == 0)
    assert(sr.counters(Seq("client", "query", "requests")) == 1)
    eventually {
      assert(sr.counters(Seq("client", "query", "failures")) == 1)
      assert(sr.counters(Seq("client", "query", "success")) == 0)
    }

    // test that we can examine the request as well.
    intercept[InvalidQueryException] {
      await(client.query("ok"))
    }
    assert(sr.counters(Seq("client", "requests")) == 2)
    assert(sr.counters(Seq("client", "success")) == 1)
    assert(sr.counters(Seq("client", "query", "requests")) == 2)
    eventually {
      assert(sr.counters(Seq("client", "query", "success")) == 1)
      assert(sr.counters(Seq("client", "query", "failures")) == 1)
    }

    // test that we can mark a successfully deserialized result as a failure
    assert("safe" == await(client.query("safe")))
    assert(sr.counters(Seq("client", "requests")) == 3)
    assert(sr.counters(Seq("client", "success")) == 1)
    assert(sr.counters(Seq("client", "query", "requests")) == 3)
    eventually {
      assert(sr.counters(Seq("client", "query", "success")) == 1)
      assert(sr.counters(Seq("client", "query", "failures")) == 2)
    }

    // this query produces a `Throw` response produced on the client side and
    // we want to ensure that we can translate it to a `Success`.
    intercept[RequestTimeoutException] {
      await(client.query("slow"))
    }
    assert(sr.counters(Seq("client", "requests")) == 4)
    assert(sr.counters(Seq("client", "success")) == 2)
    assert(sr.counters(Seq("client", "query", "requests")) == 4)
    eventually {
      assert(sr.counters(Seq("client", "query", "success")) == 2)
      assert(sr.counters(Seq("client", "query", "failures")) == 2)
    }
  }

  private def testScalaServerResponseClassification(
    sr: InMemoryStatsReceiver,
    client: TestService.MethodPerEndpoint
  ): Unit = {

    val ex = intercept[InvalidQueryException] {
      await(client.query("hi"))
    }
    assert("hi".length == ex.errorCode)
    assert(sr.counters(Seq("thrift", "query", "requests")) == 1)
    assert(sr.counters.get(Seq("thrift", "query", "success")) == Some(0))

    assert(sr.counters(Seq("thrift", "requests")) == 1)
    assert(sr.counters.get(Seq("thrift", "success")) == Some(0))

    // test that we can examine the request as well.
    intercept[InvalidQueryException] {
      await(client.query("ok"))
    }
    assert(sr.counters(Seq("thrift", "query", "requests")) == 2)
    assert(sr.counters(Seq("thrift", "query", "success")) == 1)

    assert(sr.counters(Seq("thrift", "requests")) == 2)
    assert(sr.counters(Seq("thrift", "success")) == 1)

    // test that we can mark a successfully deserialized result as a failure
    assert("safe" == await(client.query("safe")))
    assert(sr.counters(Seq("thrift", "query", "requests")) == 3)
    assert(sr.counters(Seq("thrift", "query", "success")) == 1)

    assert(sr.counters(Seq("thrift", "requests")) == 3)
    assert(sr.counters(Seq("thrift", "success")) == 1)

    // this query produces a Timeout exception in server side and it should be
    // translated to `Success`
    intercept[TApplicationException] {
      await(client.query("slow"))
    }
    assert(sr.counters(Seq("thrift", "query", "requests")) == 4)
    assert(sr.counters(Seq("thrift", "query", "success")) == 2)

    assert(sr.counters(Seq("thrift", "requests")) == 4)
    assert(sr.counters(Seq("thrift", "success")) == 2)
  }

  private def testJavaClientFailureClassification(
    sr: InMemoryStatsReceiver,
    client: thriftjava.TestService.ServiceIface
  ): Unit = {
    val ex = intercept[thriftjava.InvalidQueryException] {
      await(client.query("hi"))
    }
    assert("hi".length == ex.errorCode)
    assert(sr.counters(Seq("client", "requests")) == 1)
    assert(sr.counters(Seq("client", "success")) == 0)

    // test that we can examine the request as well.
    intercept[thriftjava.InvalidQueryException] {
      await(client.query("ok"))
    }
    assert(sr.counters(Seq("client", "requests")) == 2)
    assert(sr.counters(Seq("client", "success")) == 1)

    // test that we can mark a successfully deserialized result as a failure
    assert("safe" == await(client.query("safe")))
    assert(sr.counters(Seq("client", "requests")) == 3)
    assert(sr.counters(Seq("client", "success")) == 1)
    assert(sr.counters(Seq("client", "failures")) == 2)
  }

  private def testJavaServerFailureClassification(
    sr: InMemoryStatsReceiver,
    client: thriftjava.TestService.ServiceIface
  ): Unit = {
    val ex = intercept[thriftjava.InvalidQueryException] {
      await(client.query("hi"))
    }
    assert("hi".length == ex.errorCode)
    assert(sr.counters(Seq("thrift", "requests")) == 1)
    assert(sr.counters.get(Seq("thrift", "success")) == Some(0))

    // test that we can examine the request as well.
    intercept[thriftjava.InvalidQueryException] {
      await(client.query("ok"))
    }
    assert(sr.counters(Seq("thrift", "requests")) == 2)
    assert(sr.counters(Seq("thrift", "success")) == 1)

    // test that we can mark a successfully deserialized result as a failure
    assert("safe" == await(client.query("safe")))
    assert(sr.counters(Seq("thrift", "requests")) == 3)
    assert(sr.counters(Seq("thrift", "success")) == 1)
    assert(sr.counters(Seq("thrift", "failures")) == 2)
  }

  test("scala thriftmux stack client deserialized response classification with `build`") {
    val server = serverForClassifier()
    val sr = new InMemoryStatsReceiver()
    val client = clientImpl
      .withStatsReceiver(sr)
      .withResponseClassifier(scalaClassifier)
      .withRequestTimeout(100.milliseconds) // used in conjuection with a "slow" query
      .build[TestService.MethodPerEndpoint](
        Name.bound(Address(server.boundAddress.asInstanceOf[InetSocketAddress])),
        "client"
      )

    testScalaClientFailureClassification(sr, client)
    server.close()
  }

  test("scala thriftmux stack server deserialized response classification with `serveIface`") {
    val sr = new InMemoryStatsReceiver()

    val server = ThriftMux.server
      .withStatsReceiver(sr)
      .withResponseClassifier(scalaClassifier)
      .withRequestTimeout(100.milliseconds)
      .withPerEndpointStats
      .serveIface(new InetSocketAddress(InetAddress.getLoopbackAddress, 0), iface)

    val client = ThriftMux.client.build[TestService.MethodPerEndpoint](
      Name.bound(Address(server.boundAddress.asInstanceOf[InetSocketAddress])),
      "client"
    )

    testScalaServerResponseClassification(sr, client)
    server.close()
  }

  test("scala thriftmux stack server deserialized response classification with `serve`") {
    val sr = new InMemoryStatsReceiver()

    val svc = new TestService.FinagledService(
      iface,
      RichServerParam(
        serverStats = sr,
        responseClassifier = scalaClassifier,
        perEndpointStats = true
      )
    )

    val server = ThriftMux.server
      .withStatsReceiver(sr)
      .withResponseClassifier(scalaClassifier)
      .withRequestTimeout(100.milliseconds)
      .withPerEndpointStats
      .serve(new InetSocketAddress(InetAddress.getLoopbackAddress, 0), svc)

    val client = ThriftMux.client.build[TestService.MethodPerEndpoint](
      Name.bound(Address(server.boundAddress.asInstanceOf[InetSocketAddress])),
      "client"
    )

    testScalaServerResponseClassification(sr, client)
    server.close()
  }

  test(
    "scala thriftmux stack server deserialized response classification with " +
      "`servicePerEndpoint[ServicePerEndpoint]`"
  ) {
    val sr = new InMemoryStatsReceiver()

    val svc = new TestService.FinagledService(
      iface,
      RichServerParam(
        serverStats = sr,
        responseClassifier = scalaClassifier,
        perEndpointStats = true
      )
    )

    val server = ThriftMux.server
      .withStatsReceiver(sr)
      .withResponseClassifier(scalaClassifier)
      .withRequestTimeout(100.milliseconds)
      .withPerEndpointStats
      .serve(new InetSocketAddress(InetAddress.getLoopbackAddress, 0), svc)

    val client = ThriftMux.client.servicePerEndpoint[TestService.ServicePerEndpoint](
      Name.bound(Address(server.boundAddress.asInstanceOf[InetSocketAddress])),
      "client"
    )

    def thriftQuerySuccess: Long =
      sr.counters.getOrElse(Seq("thrift", "query", "success"), 0L)

    def thriftSuccess: Long =
      sr.counters.getOrElse(Seq("thrift", "success"), 0L)

    val ex = intercept[InvalidQueryException] {
      await(client.query(TestService.Query.Args("hi")))
    }

    assert("hi".length == ex.errorCode)
    assert(sr.counters(Seq("thrift", "query", "requests")) == 1)
    assert(thriftQuerySuccess == 0L)

    assert(sr.counters(Seq("thrift", "requests")) == 1)
    assert(thriftSuccess == 0)

    // test that we can examine the request as well.
    intercept[InvalidQueryException] {
      await(client.query(TestService.Query.Args("ok")))
    }
    assert(sr.counters(Seq("thrift", "query", "requests")) == 2)
    assert(thriftQuerySuccess == 1)

    assert(sr.counters(Seq("thrift", "requests")) == 2)
    assert(thriftSuccess == 1)

    // test that we can mark a successfully deserialized result as a failure
    assert("safe" == await(client.query(TestService.Query.Args("safe"))))
    assert(sr.counters(Seq("thrift", "query", "requests")) == 3)
    assert(thriftQuerySuccess == 1)

    assert(sr.counters(Seq("thrift", "requests")) == 3)
    assert(thriftSuccess == 1)

    // this query produces a Timeout exception in server side and it should be
    // translated to `Success`
    intercept[TApplicationException] {
      await(client.query(TestService.Query.Args("slow")))
    }
    assert(sr.counters(Seq("thrift", "query", "requests")) == 4)
    assert(thriftQuerySuccess == 2)

    assert(sr.counters(Seq("thrift", "requests")) == 4)
    assert(thriftSuccess == 2)
    server.close()
  }

  test(
    "scala thriftmux stack server deserialized response classification with " +
      "`servicePerEndpoint[ReqRepServicePerEndpoint]`"
  ) {
    val sr = new InMemoryStatsReceiver()

    val svc = new TestService.FinagledService(
      iface,
      RichServerParam(
        serverStats = sr,
        responseClassifier = scalaClassifier,
        perEndpointStats = true
      )
    )

    val server = ThriftMux.server
      .withStatsReceiver(sr)
      .withResponseClassifier(scalaClassifier)
      .withRequestTimeout(100.milliseconds)
      .withPerEndpointStats
      .serve(new InetSocketAddress(InetAddress.getLoopbackAddress, 0), svc)

    val client = ThriftMux.client.servicePerEndpoint[TestService.ReqRepServicePerEndpoint](
      Name.bound(Address(server.boundAddress.asInstanceOf[InetSocketAddress])),
      "client"
    )

    val ex = intercept[InvalidQueryException] {
      await(client.query(scrooge.Request(TestService.Query.Args("hi"))))
    }
    assert("hi".length == ex.errorCode)
    assert(sr.counters(Seq("thrift", "query", "requests")) == 1)
    assert(sr.counters.get(Seq("thrift", "query", "success")) == Some(0))

    assert(sr.counters(Seq("thrift", "requests")) == 1)
    assert(sr.counters.get(Seq("thrift", "success")) == Some(0))

    // test that we can examine the request as well.
    intercept[InvalidQueryException] {
      await(client.query(scrooge.Request(TestService.Query.Args("ok"))))
    }
    assert(sr.counters(Seq("thrift", "query", "requests")) == 2)
    assert(sr.counters(Seq("thrift", "query", "success")) == 1)

    assert(sr.counters(Seq("thrift", "requests")) == 2)
    assert(sr.counters(Seq("thrift", "success")) == 1)

    // test that we can mark a successfully deserialized result as a failure
    assert("safe" == await(client.query(scrooge.Request(TestService.Query.Args("safe")))).value)
    assert(sr.counters(Seq("thrift", "query", "requests")) == 3)
    assert(sr.counters(Seq("thrift", "query", "success")) == 1)

    assert(sr.counters(Seq("thrift", "requests")) == 3)
    assert(sr.counters(Seq("thrift", "success")) == 1)

    // this query produces a Timeout exception in server side and it should be
    // translated to `Success`
    intercept[TApplicationException] {
      await(client.query(scrooge.Request(TestService.Query.Args("slow"))))
    }
    assert(sr.counters(Seq("thrift", "query", "requests")) == 4)
    assert(sr.counters(Seq("thrift", "query", "success")) == 2)

    assert(sr.counters(Seq("thrift", "requests")) == 4)
    assert(sr.counters(Seq("thrift", "success")) == 2)
    server.close()
  }

  test("java thriftmux stack client deserialized response classification") {
    val server = serverForClassifier()
    val sr = new InMemoryStatsReceiver()
    val client = clientImpl
      .withStatsReceiver(sr)
      .withResponseClassifier(javaClassifier)
      .build[thriftjava.TestService.ServiceIface](
        Name.bound(Address(server.boundAddress.asInstanceOf[InetSocketAddress])),
        "client"
      )

    testJavaClientFailureClassification(sr, client)
    server.close()
  }

  test("java thriftmux stack server deserialized response classification") {
    val sr = new InMemoryStatsReceiver()

    val server = ThriftMux.server
      .withStatsReceiver(sr)
      .withResponseClassifier(javaClassifier)
      .withRequestTimeout(100.milliseconds)
      .serveIface(new InetSocketAddress(InetAddress.getLoopbackAddress, 0), new TestServiceImpl)

    val client = ThriftMux.client.build[thriftjava.TestService.ServiceIface](
      Name.bound(Address(server.boundAddress.asInstanceOf[InetSocketAddress])),
      "client"
    )

    testJavaServerFailureClassification(sr, client)
    server.close()
  }

  test("scala thriftmux ClientBuilder deserialized response classification") {
    val server = serverForClassifier()
    val sr = new InMemoryStatsReceiver()
    val clientBuilder = ClientBuilder()
      .stack(clientImpl)
      .name("client")
      .reportTo(sr)
      .responseClassifier(scalaClassifier)
      .requestTimeout(100.milliseconds) // used in conjuection with a "slow" query
      .dest(Name.bound(Address(server.boundAddress.asInstanceOf[InetSocketAddress])))
      .build()
    val client = new TestService.FinagledClient(
      clientBuilder,
      serviceName = "client",
      stats = sr,
      responseClassifier = scalaClassifier
    )

    testScalaClientFailureClassification(sr, client)
    server.close()
  }

  test("scala thriftmux ServerBuilder deserialized response classification") {
    val sr = new InMemoryStatsReceiver()

    val svc = new TestService.FinagledService(
      iface,
      RichServerParam(
        serverStats = sr,
        responseClassifier = scalaClassifier,
        perEndpointStats = true
      )
    )

    val server = ServerBuilder()
      .stack(ThriftMux.server)
      .responseClassifier(scalaClassifier)
      .requestTimeout(100.milliseconds)
      .name("thrift")
      .reportTo(sr)
      .bindTo(new InetSocketAddress(0))
      .build(svc)

    val client = ThriftMux.client.build[TestService.MethodPerEndpoint](
      Name.bound(Address(server.boundAddress.asInstanceOf[InetSocketAddress])),
      "client"
    )

    testScalaServerResponseClassification(sr, client)
    server.close()
  }

  test("java thriftmux ClientBuilder deserialized response classification") {
    val server = serverForClassifier()
    val sr = new InMemoryStatsReceiver()
    val clientBuilder = ClientBuilder()
      .stack(clientImpl)
      .name("client")
      .reportTo(sr)
      .responseClassifier(javaClassifier)
      .dest(Name.bound(Address(server.boundAddress.asInstanceOf[InetSocketAddress])))
      .build()
    val client = new thriftjava.TestService.ServiceToClient(
      clientBuilder,
      Protocols.binaryFactory(),
      javaClassifier
    )

    testJavaClientFailureClassification(sr, client)
    server.close()
  }

  test("java thriftmux ServerBuilder deserialized response classification") {
    val sr = new InMemoryStatsReceiver()
    val svc = new thriftjava.TestService.Service(new TestServiceImpl, RichServerParam())

    val server = ServerBuilder()
      .stack(ThriftMux.server)
      .responseClassifier(javaClassifier)
      .requestTimeout(100.milliseconds)
      .name("thrift")
      .reportTo(sr)
      .bindTo(new InetSocketAddress(0))
      .build(svc)

    val client = ThriftMux.client.build[thriftjava.TestService.ServiceIface](
      Name.bound(Address(server.boundAddress.asInstanceOf[InetSocketAddress])),
      "client"
    )

    testJavaServerFailureClassification(sr, client)
    server.close()
  }

  test("scala thriftmux client response classification using ThriftExceptionsAsFailures") {
    val server = serverForClassifier()
    val sr = new InMemoryStatsReceiver()
    val client = clientImpl
      .withStatsReceiver(sr)
      .withResponseClassifier(ThriftMuxResponseClassifier.ThriftExceptionsAsFailures)
      .build[TestService.MethodPerEndpoint](
        Name.bound(Address(server.boundAddress.asInstanceOf[InetSocketAddress])),
        "client"
      )

    val ex = intercept[InvalidQueryException] {
      await(client.query("hi"))
    }
    assert("hi".length == ex.errorCode)
    assert(sr.counters(Seq("client", "requests")) == 1)
    assert(sr.counters(Seq("client", "success")) == 0)

    // test that we can mark a successfully deserialized result as a failure
    assert("safe" == await(client.query("safe")))
    assert(sr.counters(Seq("client", "requests")) == 2)
    assert(sr.counters(Seq("client", "success")) == 1)
    await(server.close())
  }

  test("scala thriftmux server response classification using ThriftExceptionAsFailures") {
    val sr = new InMemoryStatsReceiver()

    val server = ThriftMux.server
      .withStatsReceiver(sr)
      .withResponseClassifier(ThriftMuxResponseClassifier.ThriftExceptionsAsFailures)
      .withRequestTimeout(100.milliseconds)
      .withPerEndpointStats
      .serveIface(new InetSocketAddress(InetAddress.getLoopbackAddress, 0), iface)

    val client = ThriftMux.client.build[TestService.MethodPerEndpoint](
      Name.bound(Address(server.boundAddress.asInstanceOf[InetSocketAddress])),
      "client"
    )

    val ex = intercept[InvalidQueryException] {
      Await.result(client.query("hi"), 5.seconds)
    }
    assert("hi".length == ex.errorCode)
    assert(sr.counters(Seq("thrift", "requests")) == 1)
    assert(sr.counters.get(Seq("thrift", "success")) == Some(0))
    assert(sr.counters(Seq("thrift", "query", "requests")) == 1)
    assert(sr.counters.get(Seq("thrift", "query", "success")) == Some(0))

    // test that we can mark a successfully deserialized result as a failure
    assert("safe" == Await.result(client.query("safe"), 10.seconds))
    assert(sr.counters(Seq("thrift", "requests")) == 2)
    assert(sr.counters(Seq("thrift", "success")) == 1)
    assert(sr.counters(Seq("thrift", "query", "requests")) == 2)
    assert(sr.counters(Seq("thrift", "query", "success")) == 1)
    server.close()
  }

  test("java thriftmux client response classification using ThriftExceptionsAsFailures") {
    val server = serverForClassifier()
    val sr = new InMemoryStatsReceiver()
    val client = clientImpl
      .withStatsReceiver(sr)
      .withResponseClassifier(ThriftMuxResponseClassifier.ThriftExceptionsAsFailures)
      .build[thriftjava.TestService.ServiceIface](
        Name.bound(Address(server.boundAddress.asInstanceOf[InetSocketAddress])),
        "client"
      )

    val ex = intercept[thriftjava.InvalidQueryException] {
      Await.result(client.query("hi"), 5.seconds)
    }
    assert("hi".length == ex.errorCode)
    assert(sr.counters(Seq("client", "requests")) == 1)
    assert(sr.counters(Seq("client", "success")) == 0)

    // test that we can mark a successfully deserialized result as a failure
    assert("safe" == Await.result(client.query("safe")))
    assert(sr.counters(Seq("client", "requests")) == 2)
    assert(sr.counters(Seq("client", "success")) == 1)
    assert(sr.counters(Seq("client", "failures")) == 1)
    server.close()
  }

  test("java thrift server response classification using ThriftExceptionsAsFailures") {
    val sr = new InMemoryStatsReceiver()

    val server = ThriftMux.server
      .withStatsReceiver(sr)
      .withResponseClassifier(ThriftMuxResponseClassifier.ThriftExceptionsAsFailures)
      .withRequestTimeout(100.milliseconds)
      .serveIface(new InetSocketAddress(InetAddress.getLoopbackAddress, 0), new TestServiceImpl)

    val client = ThriftMux.client.build[thriftjava.TestService.ServiceIface](
      Name.bound(Address(server.boundAddress.asInstanceOf[InetSocketAddress])),
      "client"
    )

    val ex = intercept[thriftjava.InvalidQueryException] {
      Await.result(client.query("hi"), 5.seconds)
    }
    assert("hi".length == ex.errorCode)
    assert(sr.counters(Seq("thrift", "requests")) == 1)
    assert(sr.counters.get(Seq("thrift", "success")) == Some(0))

    // test that we can mark a successfully deserialized result as a failure
    assert("safe" == Await.result(client.query("safe"), 10.seconds))
    assert(sr.counters(Seq("thrift", "requests")) == 2)
    assert(sr.counters(Seq("thrift", "success")) == 1)
    server.close()
  }

  test("thriftmux server + thrift client w/o protocol upgrade but w/ pipelined dispatch") {
    val nreqs = 5
    val servicePromises = Array.fill(nreqs)(new Promise[String])
    val requestReceived = Array.fill(nreqs)(new Promise[String])
    val testService = new TestService.MethodPerEndpoint {
      @volatile var nReqReceived = 0
      def query(x: String): Future[String] = synchronized {
        nReqReceived += 1
        requestReceived(nReqReceived - 1).setValue(x)
        servicePromises(nReqReceived - 1)
      }
      def question(y: String): Future[String] = synchronized {
        nReqReceived += 1
        requestReceived(nReqReceived - 1).setValue(y)
        servicePromises(nReqReceived - 1)
      }
      def inquiry(z: String): Future[String] = synchronized {
        nReqReceived += 1
        requestReceived(nReqReceived - 1).setValue(z)
        servicePromises(nReqReceived - 1)
      }
    }
    val server = serverImpl
      .serveIface(new InetSocketAddress(InetAddress.getLoopbackAddress, 0), testService)

    object OldPlainPipeliningThriftClient extends Thrift.Client(stack = StackClient.newStack) {
      override protected def newDispatcher(
        transport: Transport[ThriftClientRequest, Array[Byte]] {
          type Context <: TransportContext
        }
      ) =
        new PipeliningDispatcher(transport, NullStatsReceiver, 10.seconds, new MockTimer)
    }

    val service = await(
      OldPlainPipeliningThriftClient.newClient(
        Name.bound(Address(server.boundAddress.asInstanceOf[InetSocketAddress])),
        "client"
      )()
    )
    val client = new TestService.FinagledClient(service, Protocols.binaryFactory())
    val reqs = 1 to nreqs map { i =>
      client.query("ok" + i)
    }
    // Although the requests are pipelined in the client, they must be
    // received by the service serially.
    1 to nreqs foreach { i =>
      val req = await(requestReceived(i - 1))
      if (i != nreqs) assert(!requestReceived(i).isDefined)
      assert(testService.nReqReceived == i)
      servicePromises(i - 1).setValue(req + req)
    }
    1 to nreqs foreach { i =>
      assert(await(reqs(i - 1)) == "ok" + i + "ok" + i)
    }
    await(server.close())
  }

  test("thriftmux client: should emit ClientId") {
    val server = serverImpl.serveIface(
      new InetSocketAddress(InetAddress.getLoopbackAddress, 0),
      new TestService.MethodPerEndpoint {
        def query(x: String): Future[String] = {
          Future.value(ClientId.current.map(_.name).getOrElse("No ClientId"))
        }
        def question(y: String): Future[String] =
          Future.value(ClientId.current.map(_.name).getOrElse("No ClientId"))
        def inquiry(z: String): Future[String] =
          Future.value(ClientId.current.map(_.name).getOrElse("No ClientId"))
      }
    )

    val client = clientImpl
      .withClientId(ClientId("foo.bar"))
      .build[TestService.MethodPerEndpoint](
        Name.bound(Address(server.boundAddress.asInstanceOf[InetSocketAddress])),
        "client"
      )

    assert(await(client.query("ok")) == "foo.bar")
    await(server.close())
  }

  /* TODO: add back when sbt supports old-school thrift gen
   test("end-to-end finagle-thrift") {
     import com.twitter.finagle.thriftmux.thrift.TestService

     val server = ThriftMux.server.serveIface(
       new InetSocketAddress(InetAddress.getLoopbackAddress, 0), new TestService.ServiceIface {
         def query(x: String) = Future.value(x+x)
       })

     val client = thriftMuxClient.newIface[TestService.ServiceIface](server)
     assert(client.query("ok").get() == "okok")
   }
   */

  test("ThriftMux servers and clients should export protocol stats") {
    val iface = new TestService.MethodPerEndpoint {
      def query(x: String): Future[String] = Future.value(x + x)
      def question(y: String): Future[String] = Future.value(y + y)
      def inquiry(z: String): Future[String] = Future.value(z + z)
    }
    val mem = new InMemoryStatsReceiver
    val server = serverImpl
      .withStatsReceiver(mem)
      .withLabel("server")
      .serveIface(new InetSocketAddress(InetAddress.getLoopbackAddress, 0), iface)

    val client = clientImpl
      .withStatsReceiver(mem)
      .withLabel("client")
      .build[TestService.MethodPerEndpoint](
        Name.bound(Address(server.boundAddress.asInstanceOf[InetSocketAddress])),
        "client"
      )

    assert(await(client.query("ok")) == "okok")
    assert(mem.gauges(Seq("server", "protocol", "thriftmux"))() == 1.0)
    assert(mem.gauges(Seq("client", "protocol", "thriftmux"))() == 1.0)
    await(server.close())
  }

  test("ThriftMux clients are properly labeled and scoped") {
    new ThriftMuxTestServer {
      private def base(sr: InMemoryStatsReceiver) =
        clientImpl
          .withLabel(Label.Default) // this is "unsetting" the default of "thrift"
          .withStatsReceiver(sr)

      private def assertStats(
        prefix: String,
        sr: InMemoryStatsReceiver,
        iface: TestService.MethodPerEndpoint
      ) = {
        assert(await(iface.query("ok")) == "okok")
        // These stats are exported by scrooge generated code.
        assert(sr.counters(Seq(prefix, "query", "requests")) == 1)
      }

      // non-labeled client inherits destination as label
      val name = Name.bound(Address(server.boundAddress.asInstanceOf[InetSocketAddress]))
      val sr1 = new InMemoryStatsReceiver
      assertStats(name.toString, sr1, base(sr1).build[TestService.MethodPerEndpoint](name, ""))

      // labeled via configured
      val sr2 = new InMemoryStatsReceiver
      assertStats(
        "client",
        sr2,
        base(sr2).build[TestService.MethodPerEndpoint](
          Name.bound(Address(server.boundAddress.asInstanceOf[InetSocketAddress])),
          "client"
        )
      )

      await(server.close())
    }
  }

  test("ThriftMux clients are filtered") {
    var numReqs = 0
    val filter = new SimpleFilter[mux.Request, mux.Response] {
      def apply(
        request: mux.Request,
        service: Service[mux.Request, mux.Response]
      ): Future[mux.Response] = {
        numReqs += 1
        service(request)
      }
    }

    val echo = serverImpl.serveIface(
      new InetSocketAddress(InetAddress.getLoopbackAddress, 0),
      new TestService.MethodPerEndpoint {
        def query(x: String): Future[String] = Future.value(x)
        def question(y: String): Future[String] = Future.value(y)
        def inquiry(z: String): Future[String] = Future.value(z)
      }
    )

    val client = clientImpl
      .filtered(filter)
      .build[TestService.MethodPerEndpoint](
        Name.bound(Address(echo.boundAddress.asInstanceOf[InetSocketAddress])),
        "client"
      )

    assert(await(client.query("ok")) == "ok")
    assert(numReqs == 1)
    await(echo.close())
  }

  test("ThriftMux servers are filtered") {
    val filter1 = new SimpleFilter[mux.Request, mux.Response] {
      def apply(
        request: mux.Request,
        service: Service[mux.Request, mux.Response]
      ): Future[mux.Response] = {
        service(request).rescue {
          case _ => Future.exception(new FailedFastException("still unhappy"))
        }
      }
    }

    val filter2 = new SimpleFilter[mux.Request, mux.Response] {
      def apply(
        request: mux.Request,
        service: Service[mux.Request, mux.Response]
      ): Future[mux.Response] = {
        service(request).rescue {
          case _: FailedFastException => Future.exception(new FailedFastException("still no"))
        }
      }
    }

    // one filtered
    val server1 = serverImpl
      .filtered(filter1)
      .serveIface(
        new InetSocketAddress(InetAddress.getLoopbackAddress, 0),
        new TestService.MethodPerEndpoint {
          def query(x: String): Future[String] = Future.exception(Failure.rejected("unhappy"))
          def question(y: String): Future[String] = Future.exception(Failure.rejected("unhappy"))
          def inquiry(z: String): Future[String] = Future.exception(Failure.rejected("unhappy"))
        }
      )

    val client1 = clientImpl
      .build[TestService.MethodPerEndpoint](
        Name.bound(Address(server1.boundAddress.asInstanceOf[InetSocketAddress])),
        "client1"
      )

    // server1 filtered once and reply exception "still unhappy"
    val ex1 = intercept[com.twitter.finagle.mux.ServerApplicationError] {
      await(client1.query("hi"))
    }
    assert(ex1.getMessage.contains("still unhappy"))

    // call filtered twice with two filters
    val server2 = serverImpl
      .filtered(filter1)
      .filtered(filter2)
      .serveIface(
        new InetSocketAddress(InetAddress.getLoopbackAddress, 0),
        new TestService.MethodPerEndpoint {
          def query(x: String): Future[String] = Future.exception(Failure.rejected("unhappy"))
          def question(y: String): Future[String] = Future.exception(Failure.rejected("unhappy"))
          def inquiry(z: String): Future[String] = Future.exception(Failure.rejected("unhappy"))
        }
      )

    val client2 = clientImpl
      .build[TestService.MethodPerEndpoint](
        Name.bound(Address(server2.boundAddress.asInstanceOf[InetSocketAddress])),
        "client2"
      )

    // server2 filtered twice and the last filter reply exception "still no"
    val ex2 = intercept[com.twitter.finagle.mux.ServerApplicationError] {
      await(client2.query("hi again"))
    }
    assert(ex2.getMessage.contains("still no"))
    await(server1.close())
    await(server2.close())
  }

  test("downgraded pipelines are properly scoped") {
    val sr = new InMemoryStatsReceiver

    val server = serverImpl
      .configured(Stats(sr))
      .configured(Label("myserver"))
      .serveIface(
        new InetSocketAddress(InetAddress.getLoopbackAddress, 0),
        new TestService.MethodPerEndpoint {
          def query(x: String): Future[String] = Future.value(x + x)
          def question(y: String): Future[String] = Future.value(y + y)
          def inquiry(z: String): Future[String] = Future.value(z + z)
        }
      )

    val thriftClient =
      Thrift.client.build[TestService.MethodPerEndpoint](
        Name.bound(Address(server.boundAddress.asInstanceOf[InetSocketAddress])),
        "client"
      )

    assert(await(thriftClient.query("ok")) == "okok")
    assert(sr.counters(Seq("myserver", "thriftmux", "downgraded_connects")) == 1)

    await(server.close())
  }

  test("serverImpl with TCompactProtocol") {
    val pf = new TCompactProtocol.Factory
    val server = serverImpl
      .withProtocolFactory(pf)
      .serveIface(
        new InetSocketAddress(InetAddress.getLoopbackAddress, 0),
        new TestService.MethodPerEndpoint {
          def query(x: String): Future[String] = Future.value(x + x)
          def question(y: String): Future[String] = Future.value(y + y)
          def inquiry(z: String): Future[String] = Future.value(z + z)
        }
      )

    val dst = Name.bound(Address(server.boundAddress.asInstanceOf[InetSocketAddress]))
    val tcompactClient = clientImpl
      .withProtocolFactory(pf)
      .build[TestService.MethodPerEndpoint](dst, "client")
    assert(await(tcompactClient.query("ok")) == "okok")

    await(server.close())
  }

  test("serverImpl with TCompactProtocol: binary fails") {
    val server = serverImpl
      .withProtocolFactory(new TCompactProtocol.Factory)
      .serveIface(
        new InetSocketAddress(InetAddress.getLoopbackAddress, 0),
        new TestService.MethodPerEndpoint {
          def query(x: String): Future[String] = Future.value(x + x)
          def question(y: String): Future[String] = Future.value(y + y)
          def inquiry(z: String): Future[String] = Future.value(z + z)
        }
      )

    val dst = Name.bound(Address(server.boundAddress.asInstanceOf[InetSocketAddress]))
    val tbinaryClient = clientImpl.build[TestService.MethodPerEndpoint](dst, "client")
    intercept[com.twitter.finagle.mux.ServerApplicationError] {
      await(tbinaryClient.query("ok"))
    }

    await(server.close())
  }

  // This test uses excessive memory so skip on SBT builds
  if (!sys.props.contains("SKIP_SBT"))
    test("ThriftMux client to Thrift server ") {
      val iface = new TestService.MethodPerEndpoint {
        def query(x: String): Future[String] = Future.value(x + x)
        def question(y: String): Future[String] = Future.value(y + y)
        def inquiry(z: String): Future[String] = Future.value(z + z)
      }
      val mem = new InMemoryStatsReceiver
      val server = Thrift.server
        .withStatsReceiver(mem)
        .serveIface(new InetSocketAddress(InetAddress.getLoopbackAddress, 0), iface)

      val port = server.boundAddress.asInstanceOf[InetSocketAddress].getPort
      val clientSvc = clientImpl
        .withStatsReceiver(mem)
        .withLabel("client")
        .newService(s"localhost:$port")
      val client = new TestService.FinagledClient(clientSvc)

      // the thrift server doesn't understand the protocol of the request,
      // so it does its usual thing and closes the connection.
      intercept[ChannelClosedException] {
        await(client.query("ethics"))
      }

      await(clientSvc.close() join server.close())
    }

  test("drain downgraded connections") {
    val latch = Promise[Unit]()
    val response = new Promise[String]()
    val iface = new TestService.MethodPerEndpoint {
      def query(x: String): Future[String] = {
        latch.setDone
        response
      }
      def question(y: String): Future[String] = {
        latch.setDone
        response
      }
      def inquiry(z: String): Future[String] = {
        latch.setDone
        response
      }
    }

    val inet = new InetSocketAddress(InetAddress.getLoopbackAddress, 0)
    val server = serverImpl.serveIface(inet, iface)
    val client =
      Thrift.client.build[TestService.MethodPerEndpoint](
        Name.bound(Address(server.boundAddress.asInstanceOf[InetSocketAddress])),
        "client"
      )

    // hold the connection open later
    val f = client.query("ok")
    // make sure the connection established
    await(latch)

    // wait for the pending request satisfied up to 1 minute
    val close = server.close(1.minute)
    intercept[Exception] { await(close, 10.milliseconds) }

    response.setValue("done")

    // connection closed after satisfied the request
    assert(await(close.liftToTry) == Return.Unit)
    assert(await(f) == "done")
  }

  test("gracefully reject sessions") {
    @volatile var n = 0
    val server1 = serverImpl.serve(
      new InetSocketAddress(InetAddress.getLoopbackAddress, 0),
      new ServiceFactory {
        def apply(conn: ClientConnection): Future[Nothing] = {
          n += 1
          Future.exception(new Exception)
        }
        def close(deadline: Time): Future[Unit] = Future.Done
      }
    )

    val server2 = serverImpl.serve(
      new InetSocketAddress(InetAddress.getLoopbackAddress, 0),
      new ServiceFactory {
        def apply(conn: ClientConnection): Future[Nothing] = {
          n += 1
          Future.exception(new Exception)
        }
        def close(deadline: Time): Future[Unit] = Future.Done
      }
    )

    val client =
      clientImpl
        .build[TestService.MethodPerEndpoint](
          Name.bound(
            Address(server1.boundAddress.asInstanceOf[InetSocketAddress]),
            Address(server2.boundAddress.asInstanceOf[InetSocketAddress])
          ),
          "client"
        )

    val failure = intercept[Failure] {
      await(client.query("ok"))
    }

    // FailureFlags.Retryable is stripped.
    assert(!failure.isFlagged(FailureFlags.Retryable))

    // Tried multiple times.
    assert(n > 1)

    await(server1.close())
    await(server2.close())
  }

  trait ThriftMuxFailServer {
    val serverSr = new InMemoryStatsReceiver
    val server =
      serverImpl
        .configured(Stats(serverSr))
        .serveIface(
          new InetSocketAddress(InetAddress.getLoopbackAddress, 0),
          new TestService.MethodPerEndpoint {
            def query(x: String): Future[String] = Future.exception(Failure.rejected("unhappy"))
            def question(y: String): Future[String] = Future.exception(Failure.rejected("unhappy"))
            def inquiry(z: String): Future[String] = Future.exception(Failure.rejected("unhappy"))
          }
        )
  }

  /** no minimum, and 1 request gives 1 retry */
  def budget: RetryBudget =
    RetryBudget(1.minute, minRetriesPerSec = 0, percentCanRetry = 1.0)

  test("thriftmux server + thriftmux client: auto requeues retryable failures") {
    new ThriftMuxFailServer {
      val sr = new InMemoryStatsReceiver
      val client =
        clientImpl
          .withStatsReceiver(sr)
          .configured(Retries.Budget(budget))
          .build[TestService.MethodPerEndpoint](
            Name.bound(Address(server.boundAddress.asInstanceOf[InetSocketAddress])),
            "client"
          )

      val failure = intercept[Exception](await(client.query("ok")))
      assert(failure.getMessage == "The request was Nacked by the server")

      assert(serverSr.counters(Seq("thrift", "thriftmux", "connects")) == 1)
      assert(serverSr.counters(Seq("thrift", "requests")) == 2)
      assert(serverSr.counters(Seq("thrift", "failures")) == 2)

      assert(sr.counters(Seq("client", "query", "requests")) == 1)
      assert(sr.counters(Seq("client", "requests")) == 2)
      assert(sr.counters(Seq("client", "failures")) == 2)

      // reuse connection
      intercept[Exception](await(client.query("ok")))
      assert(serverSr.counters(Seq("thrift", "thriftmux", "connects")) == 1)

      await(server.close())
    }
  }

  test("thriftmux server + thriftmux client: pass mux-supported c.t.f.FailureFlags") {

    import FailureFlags._

    var failure: FailureFlags[_] = null

    val server = serverImpl.serveIface(
      new InetSocketAddress(InetAddress.getLoopbackAddress, 0),
      new TestService.MethodPerEndpoint {
        def query(s: String): Future[String] = Future.exception(failure)
        def question(y: String): Future[String] = Future.exception(failure)
        def inquiry(z: String): Future[String] = Future.exception(failure)
      }
    )

    // Don't strip failure flags, as we're testing to ensure they traverse. Also, disable retries
    val removeFailure = new Transformer {
      def apply[Req, Rep](stack: Stack[ServiceFactory[Req, Rep]]): Stack[ServiceFactory[Req, Rep]] =
        stack.remove(Failure.role).remove(Retries.Role)
    }

    val client = clientImpl
      .transformed(removeFailure)
      .build[TestService.MethodPerEndpoint](
        Name.bound(Address(server.boundAddress.asInstanceOf[InetSocketAddress])),
        "client"
      )

    def check(f: FailureFlags[_]) = {
      failure = f
      await(client.query(":(").liftToTry) match {
        case Throw(res: FailureFlags[_]) => assert(res.flags == f.flags)
        case other => fail(s"Unexpected response: $other")
      }
    }

    class CustomException(val flags: Long) extends FailureFlags[CustomException] {
      def copyWithFlags(newFlags: Long): CustomException = new CustomException(newFlags)
    }

    val failures = Seq(
      Failure("Rejected", Rejected),
      Failure("Restartable", Retryable),
      Failure("NonRetryable", NonRetryable),
      Failure.rejected("Rejected/Retryable"),
      new CustomException(Rejected),
      new CustomException(Retryable),
      new CustomException(NonRetryable)
    )
    failures.foreach(check _)
    await(server.close())
  }

  test("thriftmux server + thrift client: does not support Nack") {
    new ThriftMuxFailServer {
      val sr = new InMemoryStatsReceiver
      val client =
        Thrift.client
          .configured(Stats(sr))
          .build[TestService.MethodPerEndpoint](
            Name.bound(Address(server.boundAddress.asInstanceOf[InetSocketAddress])),
            "client"
          )

      val failure = intercept[ChannelClosedException](await(client.query("ok")))
      assert(failure.getMessage.startsWith("ChannelException at remote address"))

      assert(serverSr.counters(Seq("thrift", "requests")) == 1)
      assert(serverSr.counters(Seq("thrift", "connects")) == 1)
      assert(serverSr.counters(Seq("thrift", "thriftmux", "downgraded_connects")) == 1)

      assert(sr.counters(Seq("client", "requests")) == 1)
      assert(sr.counters(Seq("client", "failures")) == 1)
      assert(sr.stats(Seq("client", "connection_duration")).size == 1)
      assert(sr.counters(Seq("client", "retries", "requeues")) == 0)

      intercept[ChannelClosedException](await(client.query("ok")))
      // reconnects on the second request
      assert(serverSr.counters(Seq("thrift", "connects")) == 2)

      await(server.close())
    }
  }

  trait ThriftMuxFailSessionServer {
    val serverSr = new InMemoryStatsReceiver

    def boundNames: Name.Bound =
      Name.bound(
        Address(server1.boundAddress.asInstanceOf[InetSocketAddress]),
        Address(server2.boundAddress.asInstanceOf[InetSocketAddress])
      )

    private val server1 =
      serverImpl
        .configured(Stats(serverSr))
        .serve(
          new InetSocketAddress(InetAddress.getLoopbackAddress, 0),
          new ServiceFactory {
            def apply(conn: ClientConnection): Future[Nothing] =
              Future.exception(new Exception("unhappy"))
            def close(deadline: Time): Future[Unit] = Future.Done
          }
        )

    private val server2 =
      serverImpl
        .configured(Stats(serverSr))
        .serve(
          new InetSocketAddress(InetAddress.getLoopbackAddress, 0),
          new ServiceFactory {
            def apply(conn: ClientConnection): Future[Nothing] =
              Future.exception(new Exception("unhappy"))
            def close(deadline: Time): Future[Unit] = Future.Done
          }
        )

    def closeServers(): Future[Unit] = Closable.all(server1, server2).close()
  }

  test("thriftmux server + thriftmux client: session rejection") {
    new ThriftMuxFailSessionServer {
      val sr = new InMemoryStatsReceiver
      val client =
        clientImpl
          .withStatsReceiver(sr)
          .configured(Retries.Budget(budget))
          .build[TestService.MethodPerEndpoint](
            boundNames,
            "client"
          )

      val failure = intercept[Exception](await(client.query("ok")))
      assert(
        // The server hadn't yet issued the drain command
        failure.getMessage == "The request was Nacked by the server" ||
          // the service was already closed due to draining when it got to the pool
          failure.getMessage == "Returned unavailable service"
      )

      // Note: we don't check 'client/[requests|failures]' since the value is racy:
      // the server asks the client to drain immediately so we may never get a live
      // session out of the singleton pool.

      // We requeue once since the first try will fail so it will try another server
      assert(sr.counters(Seq("client", "retries", "requeues")) == 2 - 1)
      assert(sr.counters(Seq("client", "connects")) == 2)
      assert(sr.counters(Seq("client", "mux", "draining")) == 2)
      await(closeServers())
    }
  }

  test("thriftmux server + thrift client: session rejection") {
    new ThriftMuxFailSessionServer {
      val sr = new InMemoryStatsReceiver
      val client =
        Thrift.client
          .configured(Stats(sr))
          .build[TestService.MethodPerEndpoint](
            boundNames,
            "client"
          )

      intercept[ChannelClosedException](await(client.query("ok")))
      // We may have had some retries because the connection get's closed eagerly
      // so there is some racing between a dispatch writing it's message and being
      // hung up on.
      assert(sr.counters(Seq("client", "connects")) >= 1)
      assert(sr.counters(Seq("client", "failures")) >= 1)
      assert(sr.counters(Seq("client", "success")) == 0)

      await(closeServers())
    }
  }

  private def testMethodBuilderTimeouts(
    stats: InMemoryStatsReceiver,
    server: ListeningServer,
    builder: MethodBuilder
  ): Unit = {
    // these should never complete within the timeout
    // ServicePerEndpoint
    val shortTimeout: Service[TestService.Query.Args, TestService.Query.SuccessType] =
      builder
        .withTimeoutPerRequest(5.millis)
        .servicePerEndpoint[TestService.ServicePerEndpoint]("fast")
        .query

    intercept[IndividualRequestTimeoutException] {
      await(shortTimeout(TestService.Query.Args("shorty")))
    }
    eventually {
      assert(stats.counter("a_label", "fast", "logical", "requests")() == 1)
      assert(stats.counter("a_label", "fast", "logical", "success")() == 0)
    }

    // ServicePerEndpoint
    val shortTimeoutSvcPerEndpoint: Service[TestService.Query.Args, TestService.Query.SuccessType] =
      builder
        .withTimeoutPerRequest(5.millis)
        .servicePerEndpoint[TestService.ServicePerEndpoint]("fast")
        .query

    intercept[IndividualRequestTimeoutException] {
      await(shortTimeoutSvcPerEndpoint(TestService.Query.Args("shorty")))
    }
    // ReqRepServicePerEndpoint
    val shortTimeoutReqRepSvcPerEndpoint: Service[
      scrooge.Request[TestService.Query.Args],
      scrooge.Response[
        TestService.Query.SuccessType
      ]] =
      builder
        .withTimeoutPerRequest(5.millis)
        .servicePerEndpoint[TestService.ReqRepServicePerEndpoint]("fast")
        .query

    intercept[IndividualRequestTimeoutException] {
      await(shortTimeoutReqRepSvcPerEndpoint(scrooge.Request(TestService.Query.Args("shorty"))))
    }

    // these should always complete within the timeout
    // ServicePerEndpoint
    val longTimeout =
      builder
        .withTimeoutPerRequest(5.seconds)
        .servicePerEndpoint[TestService.ServicePerEndpoint]("slow")
        .query

    var result = await(longTimeout(TestService.Query.Args("looong")))
    assert("looong" == result)
    eventually {
      assert(stats.counter("a_label", "slow", "logical", "requests")() == 1)
      assert(stats.counter("a_label", "slow", "logical", "success")() == 1)
    }

    // ServicePerEndpoint
    val longTimeoutSvcPerEndpoint =
      builder
        .withTimeoutPerRequest(5.seconds)
        .servicePerEndpoint[TestService.ServicePerEndpoint]("slow")
        .query

    result = await(longTimeoutSvcPerEndpoint(TestService.Query.Args("looong")))
    assert("looong" == result)

    // ReqRepServicePerEndpoint
    val longTimeoutReqRepSvcPerEndpoint =
      builder
        .withTimeoutPerRequest(5.seconds)
        .servicePerEndpoint[TestService.ReqRepServicePerEndpoint]("slow")
        .query

    val response = await(
      longTimeoutReqRepSvcPerEndpoint(scrooge.Request(TestService.Query.Args("looong")))
    )
    assert("looong" == response.value)

    await(server.close())
  }

  test("methodBuilder timeouts from Stack") {
    implicit val timer: Timer = DefaultTimer
    val service = new TestService.MethodPerEndpoint {
      def query(x: String): Future[String] = {
        Future.sleep(50.millis).before { Future.value(x) }
      }
      def question(y: String): Future[String] = {
        Future.sleep(50.millis).before { Future.value(y) }
      }
      def inquiry(z: String): Future[String] = {
        Future.sleep(50.millis).before { Future.value(z) }
      }
    }
    val server =
      serverImpl.serveIface(new InetSocketAddress(InetAddress.getLoopbackAddress, 0), service)

    val stats = new InMemoryStatsReceiver()
    val client = clientImpl
      .configured(param.Timer(timer))
      .withStatsReceiver(stats)
      .withLabel("a_label")
    val name = Name.bound(Address(server.boundAddress.asInstanceOf[InetSocketAddress]))
    val builder: MethodBuilder = client.methodBuilder(name)

    testMethodBuilderTimeouts(stats, server, builder)
  }

  test("methodBuilder timeouts from ClientBuilder") {
    implicit val timer: Timer = DefaultTimer
    val service = new TestService.MethodPerEndpoint {
      def query(x: String): Future[String] = {
        Future.sleep(50.millis).before { Future.value(x) }
      }
      def question(y: String): Future[String] = {
        Future.sleep(50.millis).before { Future.value(y) }
      }
      def inquiry(z: String): Future[String] = {
        Future.sleep(50.millis).before { Future.value(z) }
      }
    }
    val server =
      serverImpl.serveIface(new InetSocketAddress(InetAddress.getLoopbackAddress, 0), service)

    val stats = new InMemoryStatsReceiver()
    val client = clientImpl
      .configured(param.Timer(timer))

    val clientBuilder = ClientBuilder()
      .reportTo(stats)
      .name("a_label")
      .stack(client)
      .dest(Name.bound(Address(server.boundAddress.asInstanceOf[InetSocketAddress])))
    val mb = MethodBuilder.from(clientBuilder)

    testMethodBuilderTimeouts(stats, server, mb)
  }

  test("methodBuilder timeouts from configured ClientBuilder") {
    implicit val timer = new MockTimer
    val sleepTime = new AtomicReference[Duration](Duration.Bottom)
    val service = new TestService.MethodPerEndpoint {
      def query(x: String): Future[String] = {
        Future.sleep(sleepTime.get)(DefaultTimer).before { Future.value(x) }
      }
      def question(y: String): Future[String] = {
        Future.sleep(sleepTime.get)(DefaultTimer).before { Future.value(y) }
      }
      def inquiry(z: String): Future[String] = {
        Future.sleep(sleepTime.get)(DefaultTimer).before { Future.value(z) }
      }
    }
    val server =
      serverImpl.serveIface(new InetSocketAddress(InetAddress.getLoopbackAddress, 0), service)

    val stats = new InMemoryStatsReceiver()
    val client = clientImpl
      .configured(param.Timer(timer))

    val clientBuilder = ClientBuilder()
    // set tight "default" timeouts that MB must override in
    // order to get successful responses.
      .requestTimeout(1.milliseconds)
      .timeout(2.milliseconds)
      .reportTo(stats)
      .name("a_label")
      .stack(client)
      .dest(Name.bound(Address(server.boundAddress.asInstanceOf[InetSocketAddress])))
    val mb = MethodBuilder.from(clientBuilder)

    // these should never complete within the timeout
    // ServicePerEndpoint
    val asIs: Service[TestService.Query.Args, TestService.Query.SuccessType] =
      mb.servicePerEndpoint[TestService.ServicePerEndpoint]("as_is").query

    Time.withCurrentTimeFrozen { control =>
      // Send a priming request to get a connection established so we don't timeout service acquisition
      assert("prime" == await(asIs(TestService.Query.Args("prime"))))
      sleepTime.set(Duration.Top)
      val req1 = asIs(TestService.Query.Args("nope"))
      control.advance(10.milliseconds)
      timer.tick()
      intercept[RequestTimeoutException] { await(req1) }
      eventually {
        assert(stats.counter("a_label", "as_is", "logical", "requests")() == 2)
        assert(stats.counter("a_label", "as_is", "logical", "success")() == 1)
      }

      // ServicePerEndpoint
      val asIsSvcPerEndpoint: Service[TestService.Query.Args, TestService.Query.SuccessType] =
        mb.servicePerEndpoint[TestService.ServicePerEndpoint]("as_is").query
      val req2 = asIsSvcPerEndpoint(TestService.Query.Args("nope"))
      control.advance(10.milliseconds)
      timer.tick()
      intercept[RequestTimeoutException] { await(req2) }

      // ReqRepServicePerEndpoint
      val asIsReqRepSvcPerEndpoint: Service[
        scrooge.Request[TestService.Query.Args],
        scrooge.Response[
          TestService.Query.SuccessType
        ]] =
        mb.servicePerEndpoint[TestService.ReqRepServicePerEndpoint]("as_is").query
      val req3 = asIsReqRepSvcPerEndpoint(scrooge.Request(TestService.Query.Args("nope")))
      control.advance(10.milliseconds)
      timer.tick()
      intercept[RequestTimeoutException] { await(req3) }

      // increase the timeouts via MB and now the request should succeed
      // ServicePerEndpoint
      val longTimeout: Service[TestService.Query.Args, TestService.Query.SuccessType] =
        mb.withTimeoutPerRequest(5.seconds)
          .withTimeoutTotal(5.seconds)
          .servicePerEndpoint[TestService.ServicePerEndpoint]("good")
          .query

      // An actual sleep
      sleepTime.set(50.milliseconds)

      val req4 = longTimeout(TestService.Query.Args("yep"))
      control.advance(1.second)
      timer.tick()
      val result1 = await(req4)
      assert("yep" == result1)
      eventually {
        assert(stats.counter("a_label", "good", "logical", "requests")() == 1)
        assert(stats.counter("a_label", "good", "logical", "success")() == 1)
      }

      // ServicePerEndpoint
      val longTimeoutSvcPerEndpoint: Service[
        TestService.Query.Args,
        TestService.Query.SuccessType] =
        mb.withTimeoutPerRequest(5.seconds)
          .withTimeoutTotal(5.seconds)
          .servicePerEndpoint[TestService.ServicePerEndpoint]("good")
          .query

      val req5 = longTimeoutSvcPerEndpoint(TestService.Query.Args("yep"))
      control.advance(1.second)
      timer.tick()

      val result2 = await(req5)
      assert("yep" == result2)
      // ReqRepServicePerEndpoint
      val longTimeoutReqRepSvcPerEndpoint: Service[
        scrooge.Request[TestService.Query.Args],
        scrooge.Response[
          TestService.Query.SuccessType
        ]] =
        mb.withTimeoutPerRequest(5.seconds)
          .withTimeoutTotal(5.seconds)
          .servicePerEndpoint[TestService.ReqRepServicePerEndpoint]("good")
          .query

      val req6 = longTimeoutReqRepSvcPerEndpoint(scrooge.Request(TestService.Query.Args("yep")))
      control.advance(1.second)
      timer.tick()
      val response = await(req6)
      assert("yep" == response.value)

      await(server.close())
    }
  }

  test("methodBuilder tunable timeouts from configured ClientBuilder") {
    val timer: MockTimer = new MockTimer()
    val service = new TestService.MethodPerEndpoint {
      private val methodCalled = new AtomicBoolean(false)
      def query(x: String): Future[String] = {
        if (methodCalled.compareAndSet(false, true)) {
          Future.value(x)
        } else {
          Future.never
        }
      }
      def question(y: String): Future[String] = {
        if (methodCalled.compareAndSet(false, true)) {
          Future.value(y)
        } else {
          Future.never
        }
      }
      def inquiry(z: String): Future[String] = {
        if (methodCalled.compareAndSet(false, true)) {
          Future.value(z)
        } else {
          Future.never
        }
      }
    }
    val server =
      serverImpl.serveIface(new InetSocketAddress(InetAddress.getLoopbackAddress, 0), service)

    val stats = new InMemoryStatsReceiver()
    val client = clientImpl
      .configured(param.Timer(timer))

    val clientBuilder = ClientBuilder()
    // set tight "default" timeouts that MB must override in
    // order to get successful responses.
      .requestTimeout(1.milliseconds)
      .timeout(2.milliseconds)
      .reportTo(stats)
      .name("a_label")
      .stack(client)
      .dest(Name.bound(Address(server.boundAddress.asInstanceOf[InetSocketAddress])))
    val mb = MethodBuilder.from(clientBuilder)

    val perRequestTimeoutTunable = Tunable.emptyMutable[Duration]("perRequest")
    val totalTimeoutTunable = Tunable.emptyMutable[Duration]("total")
    val tunableTimeoutSvc: Service[TestService.Query.Args, TestService.Query.SuccessType] =
      mb.withTimeoutPerRequest(perRequestTimeoutTunable)
        .withTimeoutTotal(totalTimeoutTunable)
        .servicePerEndpoint[TestService.ServicePerEndpoint]("good")
        .query

    Time.withCurrentTimeFrozen { _ =>
      // send a good response to ensure the stack has a chance to initialized
      await(tunableTimeoutSvc(TestService.Query.Args("first")))
    }

    // increase the timeouts via MB and tests should fail after tunable timeouts
    Time.withCurrentTimeFrozen { currentTime =>
      // ------------------ test total timeouts ------------------
      perRequestTimeoutTunable.set(100.seconds) // long timeout that does not trigger
      totalTimeoutTunable.set(5.seconds)
      val result1 = tunableTimeoutSvc(TestService.Query.Args("yep"))
      assert(!result1.isDefined)

      // go past the total timeout
      currentTime.advance(6.seconds)
      timer.tick()
      val ex1 = intercept[GlobalRequestTimeoutException] { await(result1) }
      assert(ex1.getMessage().contains(totalTimeoutTunable().get.toString))

      // change the timeout
      totalTimeoutTunable.set(3.seconds)
      val result2 = tunableTimeoutSvc(TestService.Query.Args("nope"))
      assert(result2.poll == None)

      // this time, 4 seconds pushes us past
      currentTime.advance(4.seconds)
      timer.tick()
      val ex2 = intercept[GlobalRequestTimeoutException] { await(result2) }
      assert(ex2.getMessage().contains(totalTimeoutTunable().get.toString))

    }

    Time.withCurrentTimeFrozen { currentTime =>
      // ------------------ test per request timeouts ------------------
      totalTimeoutTunable.set(100.seconds) // long timeout that does not trigger
      perRequestTimeoutTunable.set(2.seconds)
      val result1 = tunableTimeoutSvc(TestService.Query.Args("huh"))
      assert(result1.poll == None)

      // go past the per request timeout
      currentTime.advance(5.seconds)
      timer.tick()
      val ex1 = intercept[IndividualRequestTimeoutException] { await(result1) }
      assert(ex1.getMessage().contains(perRequestTimeoutTunable().get.toString))

      // change the timeout
      perRequestTimeoutTunable.set(1.seconds)
      val result2 = tunableTimeoutSvc(TestService.Query.Args("what"))
      assert(result2.poll == None)

      // this time, 4 seconds pushes us past
      currentTime.advance(4.seconds)
      timer.tick()
      val ex2 = intercept[IndividualRequestTimeoutException] { await(result2) }
      assert(ex2.getMessage().contains(perRequestTimeoutTunable().get.toString))
    }

    await(server.close())
  }

  private[this] def testMethodBuilderRetries(
    stats: InMemoryStatsReceiver,
    server: ListeningServer,
    builder: MethodBuilder
  ): Unit = {
    // ServicePerEndpoint
    val retryInvalid: Service[TestService.Query.Args, TestService.Query.SuccessType] =
      builder
        .withRetryForClassifier {
          case ReqRep(_, Throw(InvalidQueryException(_))) =>
            ResponseClass.RetryableFailure
        }
        .servicePerEndpoint[TestService.ServicePerEndpoint]("all_invalid")
        .query

    intercept[InvalidQueryException] {
      await(retryInvalid(TestService.Query.Args("fail0")))
    }
    eventually {
      assert(stats.counter("a_label", "all_invalid", "logical", "requests")() == 1)
      assert(stats.counter("a_label", "all_invalid", "logical", "success")() == 0)
      assert(stats.stat("a_label", "all_invalid", "retries")() == Seq(2))
    }
    // ServicePerEndpoint
    val retryInvalidSvcPerEndpoint: Service[TestService.Query.Args, TestService.Query.SuccessType] =
      builder
        .withRetryForClassifier {
          case ReqRep(_, Throw(InvalidQueryException(_))) =>
            ResponseClass.RetryableFailure
        }
        .servicePerEndpoint[TestService.ServicePerEndpoint]("all_invalid")
        .query

    intercept[InvalidQueryException] {
      await(retryInvalidSvcPerEndpoint(TestService.Query.Args("fail0")))
    }
    // ReqRepServicePerEndpoint
    val retryInvalidReqRepSvcPerEndpoint: Service[
      scrooge.Request[TestService.Query.Args],
      scrooge.Response[
        TestService.Query.SuccessType
      ]] =
      builder
        .withRetryForClassifier {
          case ReqRep(_, Throw(InvalidQueryException(_))) =>
            ResponseClass.RetryableFailure
        }
        .servicePerEndpoint[TestService.ReqRepServicePerEndpoint]("all_invalid")
        .query

    intercept[InvalidQueryException] {
      await(retryInvalidReqRepSvcPerEndpoint(scrooge.Request(TestService.Query.Args("fail0"))))
    }

    // ServicePerEndpoint
    val errCode1Succeeds: Service[TestService.Query.Args, TestService.Query.SuccessType] =
      builder
        .withRetryForClassifier {
          case ReqRep(_, Throw(InvalidQueryException(errorCode))) if errorCode == 0 =>
            ResponseClass.NonRetryableFailure
          case ReqRep(_, Throw(InvalidQueryException(errorCode))) if errorCode == 1 =>
            ResponseClass.Success
        }
        .servicePerEndpoint[TestService.ServicePerEndpoint]("err_1")
        .query

    intercept[InvalidQueryException] {
      // this is a non-retryable failure
      await(errCode1Succeeds(TestService.Query.Args("fail0")))
    }
    eventually {
      assert(stats.counter("a_label", "err_1", "logical", "requests")() == 1)
      assert(stats.counter("a_label", "err_1", "logical", "success")() == 0)
      assert(stats.stat("a_label", "err_1", "retries")() == Seq(0))
    }

    intercept[InvalidQueryException] {
      // this is a "successful" "failure"
      await(errCode1Succeeds(TestService.Query.Args("fail1")))
    }
    eventually {
      assert(stats.counter("a_label", "err_1", "logical", "requests")() == 2)
      assert(stats.counter("a_label", "err_1", "logical", "success")() == 1)
    }

    // ServicePerEndpoint
    val errCode1SucceedsSvcPerEndpoint: Service[
      TestService.Query.Args,
      TestService.Query.SuccessType] =
      builder
        .withRetryForClassifier {
          case ReqRep(_, Throw(InvalidQueryException(errorCode))) if errorCode == 0 =>
            ResponseClass.NonRetryableFailure
          case ReqRep(_, Throw(InvalidQueryException(errorCode))) if errorCode == 1 =>
            ResponseClass.Success
        }
        .servicePerEndpoint[TestService.ServicePerEndpoint]("err_1")
        .query

    intercept[InvalidQueryException] {
      // this is a non-retryable failure
      await(errCode1SucceedsSvcPerEndpoint(TestService.Query.Args("fail0")))
    }
    intercept[InvalidQueryException] {
      // this is a "successful" "failure"
      await(errCode1SucceedsSvcPerEndpoint(TestService.Query.Args("fail1")))
    }
    // ReqRepServicePerEndpoint
    val errCode1SucceedsReqRepSvcPerEndpoint: Service[
      scrooge.Request[TestService.Query.Args],
      scrooge.Response[
        TestService.Query.SuccessType
      ]] =
      builder
        .withRetryForClassifier {
          case ReqRep(_, Throw(InvalidQueryException(errorCode))) if errorCode == 0 =>
            ResponseClass.NonRetryableFailure
          case ReqRep(_, Throw(InvalidQueryException(errorCode))) if errorCode == 1 =>
            ResponseClass.Success
        }
        .servicePerEndpoint[TestService.ReqRepServicePerEndpoint]("err_1")
        .query

    intercept[InvalidQueryException] {
      // this is a non-retryable failure
      await(errCode1SucceedsReqRepSvcPerEndpoint(scrooge.Request(TestService.Query.Args("fail0"))))
    }
    intercept[InvalidQueryException] {
      // this is a "successful" "failure"
      await(errCode1SucceedsReqRepSvcPerEndpoint(scrooge.Request(TestService.Query.Args("fail1"))))
    }

    await(server.close())
  }

  test("methodBuilder retries from Stack") {
    val service = new TestService.MethodPerEndpoint {
      def query(x: String): Future[String] = x match {
        case "fail0" => Future.exception(InvalidQueryException(0))
        case "fail1" => Future.exception(InvalidQueryException(1))
        case _ => Future.value(x)
      }
      def question(y: String): Future[String] = y match {
        case "fail0" => Future.exception(InvalidQueryException(0))
        case "fail1" => Future.exception(InvalidQueryException(1))
        case _ => Future.value(y)
      }
      def inquiry(z: String): Future[String] = z match {
        case "fail0" => Future.exception(InvalidQueryException(0))
        case "fail1" => Future.exception(InvalidQueryException(1))
        case _ => Future.value(z)
      }
    }
    val server =
      serverImpl.serveIface(new InetSocketAddress(InetAddress.getLoopbackAddress, 0), service)
    val stats = new InMemoryStatsReceiver()
    val client = clientImpl
      .withStatsReceiver(stats)
      .withLabel("a_label")
    val name = Name.bound(Address(server.boundAddress.asInstanceOf[InetSocketAddress]))
    val builder: MethodBuilder = client.methodBuilder(name)

    testMethodBuilderRetries(stats, server, builder)
  }

  test("methodBuilder retries from Stack with MethodPerEndpoint") {
    val service = new TestService.MethodPerEndpoint {
      def query(x: String): Future[String] = x match {
        case "fail0" => Future.exception(InvalidQueryException(0))
        case "fail1" => Future.exception(InvalidQueryException(1))
        case _ => Future.value(x)
      }
      def question(y: String): Future[String] = y match {
        case "fail0" => Future.exception(InvalidQueryException(0))
        case "fail1" => Future.exception(InvalidQueryException(1))
        case _ => Future.value(y)
      }
      def inquiry(z: String): Future[String] = z match {
        case "fail0" => Future.exception(InvalidQueryException(0))
        case "fail1" => Future.exception(InvalidQueryException(1))
        case _ => Future.value(z)
      }
    }
    val server =
      serverImpl.serveIface(new InetSocketAddress(InetAddress.getLoopbackAddress, 0), service)
    val stats = new InMemoryStatsReceiver()
    val client = clientImpl
      .withStatsReceiver(stats)
      .withLabel("a_label")
    val name = Name.bound(Address(server.boundAddress.asInstanceOf[InetSocketAddress]))
    val builder: MethodBuilder = client.methodBuilder(name)

    testMethodBuilderRetries(stats, server, builder)
  }

  test("methodBuilder retries from ClientBuilder") {
    val service = new TestService.MethodPerEndpoint {
      def query(x: String): Future[String] = x match {
        case "fail0" => Future.exception(InvalidQueryException(0))
        case "fail1" => Future.exception(InvalidQueryException(1))
        case _ => Future.value(x)
      }
      def question(y: String): Future[String] = y match {
        case "fail0" => Future.exception(InvalidQueryException(0))
        case "fail1" => Future.exception(InvalidQueryException(1))
        case _ => Future.value(y)
      }
      def inquiry(z: String): Future[String] = z match {
        case "fail0" => Future.exception(InvalidQueryException(0))
        case "fail1" => Future.exception(InvalidQueryException(1))
        case _ => Future.value(z)
      }
    }
    val server =
      serverImpl.serveIface(new InetSocketAddress(InetAddress.getLoopbackAddress, 0), service)
    val stats = new InMemoryStatsReceiver()
    val client = clientImpl
    val clientBuilder = ClientBuilder()
      .reportTo(stats)
      .name("a_label")
      .stack(client)
      .dest(Name.bound(Address(server.boundAddress.asInstanceOf[InetSocketAddress])))

    val builder: MethodBuilder = MethodBuilder.from(clientBuilder)

    testMethodBuilderRetries(stats, server, builder)
  }

  test("methodBuilder retries from ClientBuilder with MethodPerEndpoint") {
    val service = new TestService.MethodPerEndpoint {
      def query(x: String): Future[String] = x match {
        case "fail0" => Future.exception(InvalidQueryException(0))
        case "fail1" => Future.exception(InvalidQueryException(1))
        case _ => Future.value(x)
      }
      def question(y: String): Future[String] = y match {
        case "fail0" => Future.exception(InvalidQueryException(0))
        case "fail1" => Future.exception(InvalidQueryException(1))
        case _ => Future.value(y)
      }
      def inquiry(z: String): Future[String] = z match {
        case "fail0" => Future.exception(InvalidQueryException(0))
        case "fail1" => Future.exception(InvalidQueryException(1))
        case _ => Future.value(z)
      }
    }
    val server =
      serverImpl.serveIface(new InetSocketAddress(InetAddress.getLoopbackAddress, 0), service)
    val stats = new InMemoryStatsReceiver()
    val client = clientImpl
    val clientBuilder = ClientBuilder()
      .reportTo(stats)
      .name("a_label")
      .stack(client)
      .dest(Name.bound(Address(server.boundAddress.asInstanceOf[InetSocketAddress])))

    val builder: MethodBuilder = MethodBuilder.from(clientBuilder)

    testMethodBuilderRetries(stats, server, builder)
  }

  test("methodBuilder stats are not eager for all methods") {
    // note that this CaptureStatsReceiver could be avoided if `InMemoryStatsReceiver`
    // was eager about creating counters and stats that have not been used.
    // see CSL-6751
    val metrics = new ConcurrentHashMap[String, Unit]()
    class CaptureStatsReceiver(protected val self: StatsReceiver) extends StatsReceiverProxy {
      override def counter(verbosity: Verbosity, names: String*): Counter = {
        metrics.put(names.mkString("/"), ())
        super.counter(verbosity, names: _*)
      }

      override def stat(verbosity: Verbosity, names: String*): Stat = {
        metrics.put(names.mkString("/"), ())
        super.stat(verbosity, names: _*)
      }

      override def addGauge(verbosity: Verbosity, names: String*)(f: => Float): Gauge = {
        metrics.put(names.mkString("/"), ())
        super.addGauge(verbosity, names: _*)(f)
      }
    }
    val stats = new InMemoryStatsReceiver

    val service = new TestService.MethodPerEndpoint {
      def query(x: String): Future[String] = Future.value(x)
      def question(y: String): Future[String] = Future.value(y)
      def inquiry(z: String): Future[String] = Future.value(z)
    }

    val server =
      serverImpl.serveIface(new InetSocketAddress(InetAddress.getLoopbackAddress, 0), service)

    val client = clientImpl
      .withStatsReceiver(new CaptureStatsReceiver(stats))
      .withLabel("a_service")
    val name = Name.bound(Address(server.boundAddress.asInstanceOf[InetSocketAddress]))
    val builder: MethodBuilder = client.methodBuilder(name)

    // ensure there are no metrics to start with. e.g. "logical", "query"
    assert(!metrics.keys.asScala.exists(key => key.contains("logical/requests")))

    // ensure that materializing into a ServicePerEndpoint doesn't create the metrics
    val spe = builder.servicePerEndpoint[TestService.ServicePerEndpoint]("a_method")
    assert(!metrics.keys.asScala.exists(key => key.contains("a_service/a_method")))

    // ensure that changing configuration doesn't either.
    val configured = builder
      .idempotent(0.1)
      .servicePerEndpoint[TestService.ServicePerEndpoint]("a_method")
      .query
    assert(!metrics.keys.asScala.exists(key => key.contains("a_service/a_method")))

    // use it to confirm metrics appear
    assert("hello" == await(configured(TestService.Query.Args("hello"))))
    eventually {
      assert(stats.counter("a_service", "a_method", "logical", "requests")() == 1)
    }

    server.close()
  }

  test("MethodMetadata#asCurrent") {
    def assertMethod(method: ThriftMethod): Unit = {
      assert(MethodMetadata.current ne None)
      val methodMetadata = MethodMetadata.current.get
      assert(methodMetadata.methodName == method.name)
      assert(methodMetadata.serviceName == method.serviceName)
    }

    val queryMethodMetadata = MethodMetadata(TestService.Query)
    val questionMethodMetadata = MethodMetadata(TestService.Question)
    val inquiryMethodMetadata = MethodMetadata(TestService.Inquiry)

    queryMethodMetadata.asCurrent {
      assertMethod(TestService.Query)

      questionMethodMetadata.asCurrent {
        assertMethod(TestService.Question)

        inquiryMethodMetadata.asCurrent {
          assertMethod(TestService.Inquiry)
        }
      }

      assertMethod(TestService.Query)

      inquiryMethodMetadata.asCurrent {
        assertMethod(TestService.Inquiry)
      }
    }
  }

}
