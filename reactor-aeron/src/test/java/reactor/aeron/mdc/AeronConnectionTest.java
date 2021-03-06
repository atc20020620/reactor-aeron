package reactor.aeron.mdc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static reactor.aeron.DefaultFragmentMapper.asString;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Stream;
import org.agrona.DirectBuffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import reactor.aeron.AeronDuplex;
import reactor.aeron.BaseAeronTest;
import reactor.aeron.OnDisposable;
import reactor.aeron.SocketUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.ReplayProcessor;

public class AeronConnectionTest extends BaseAeronTest {

  private static final Duration IMAGE_TIMEOUT = Duration.ofSeconds(1);

  private int serverPort;
  private int serverControlPort;
  private AeronResources resources;

  @BeforeEach
  void beforeEach() {
    serverPort = SocketUtils.findAvailableUdpPort();
    serverControlPort = SocketUtils.findAvailableUdpPort();
    resources =
        new AeronResources()
            .useTmpDir()
            .singleWorker()
            .media(mdc -> mdc.imageLivenessTimeoutNs(IMAGE_TIMEOUT.toNanos()))
            .start()
            .block();
  }

  @AfterEach
  void afterEach() {
    if (resources != null) {
      resources.dispose();
      resources.onDispose().block(TIMEOUT);
    }
  }

  @Test
  public void testClientCouldNotConnectToServer() {
    assertThrows(RuntimeException.class, this::createConnection);
  }

  @Test
  public void testServerDisconnectsSessionAndClientHandleUnavailableImage()
      throws InterruptedException {
    ReplayProcessor<DirectBuffer> processor = ReplayProcessor.create();
    CountDownLatch latch = new CountDownLatch(1);

    createServer(
        connection -> {
          connection.onDispose().doOnSuccess(aVoid -> latch.countDown()).subscribe();
          connection.inbound().receive().subscribe(processor);
          return connection.onDispose();
        });

    AeronDuplex connection = createConnection();
    connection
        .outbound()
        .sendString(
            Flux.range(1, 100)
                .delayElements(Duration.ofSeconds(1))
                .map(String::valueOf)
                .log("send"))
        .then()
        .subscribe();

    processor.blockFirst(TIMEOUT);

    connection.dispose();

    latch.await(IMAGE_TIMEOUT.multipliedBy(2).toMillis(), TimeUnit.MILLISECONDS);

    assertEquals(0, latch.getCount());
  }

  @Test
  public void testClientClosesSessionAndServerHandleUnavailableImage() throws Exception {
    OnDisposable server =
        createServer(
            connection ->
                connection
                    .outbound()
                    .sendString(
                        Flux.fromStream(Stream.of("hello1", "2", "3"))
                            .delayElements(Duration.ofSeconds(1))
                            .log("server1"))
                    .then(connection.onDispose()));

    ReplayProcessor<String> processor = ReplayProcessor.create();

    AeronDuplex<DirectBuffer> connection = createConnection();

    CountDownLatch latch = new CountDownLatch(1);
    connection.onDispose(latch::countDown);

    connection.inbound().receive().map(asString()).log("client").subscribe(processor);

    processor.take(1).blockLast(Duration.ofSeconds(4));

    server.dispose();

    latch.await(IMAGE_TIMEOUT.multipliedBy(2).toMillis(), TimeUnit.MILLISECONDS);

    assertEquals(0, latch.getCount());
  }

  @Test
  public void testServerDisconnects() throws Exception {
    OnDisposable server = createServer(OnDisposable::onDispose);

    CountDownLatch clientConnectionLatch = new CountDownLatch(1);

    AeronDuplex client = createConnection();

    client.onDispose(clientConnectionLatch::countDown);

    Mono //
        .delay(Duration.ofSeconds(1))
        .doOnSuccess(avoid -> server.dispose())
        .subscribe();

    boolean await = clientConnectionLatch.await(3, TimeUnit.SECONDS);
    assertTrue(await, "clientConnectionLatch: " + clientConnectionLatch.getCount());
  }

  @Test
  public void testServerDisconnectsAndClientCleanups() throws Exception {
    OnDisposable server = createServer(OnDisposable::onDispose);

    CountDownLatch clientConnectionLatch = new CountDownLatch(2);

    AeronDuplex<DirectBuffer> client = createConnection();

    client
        .inbound() //
        .receive()
        .log("CLIENT_INBOUND")
        .doFinally(s -> clientConnectionLatch.countDown())
        .then()
        .subscribe();
    client
        .outbound()
        .sendBuffer(
            Mono.<ByteBuffer>never()
                .log("CLIENT_OUTBOUND_SEND")
                .doFinally(s -> clientConnectionLatch.countDown()))
        .then()
        .log("CLIENT_OUTBOUND")
        .subscribe();

    Mono //
        .delay(Duration.ofSeconds(1))
        .doOnSuccess(avoid -> server.dispose())
        .subscribe();

    boolean await = clientConnectionLatch.await(3, TimeUnit.SECONDS);
    assertTrue(await, "clientConnectionLatch: " + clientConnectionLatch.getCount());
  }

  @Test
  public void testClientDisconnects() throws Exception {
    CountDownLatch serverConnectionLatch = new CountDownLatch(1);

    createServer(c -> c.onDispose().doFinally(s -> serverConnectionLatch.countDown()));

    AeronDuplex client = createConnection();

    Mono //
        .delay(Duration.ofSeconds(1))
        .doOnSuccess(avoid -> client.dispose())
        .subscribe();

    boolean await = serverConnectionLatch.await(3, TimeUnit.SECONDS);
    assertTrue(await, "serverConnectionLatch: " + serverConnectionLatch.getCount());
  }

  @Test
  public void testClientDisconnectsAndServerCleanups() throws Exception {
    CountDownLatch serverConnectionLatch = new CountDownLatch(2);

    createServer(
        c -> {
          c.inbound() //
              .receive()
              .log("SERVER_INBOUND")
              .doFinally(s -> serverConnectionLatch.countDown())
              .then()
              .subscribe();
          c.outbound()
              .sendBuffer(
                  Mono.<ByteBuffer>never()
                      .log("SERVER_OUTBOUND_SEND")
                      .doFinally(s -> serverConnectionLatch.countDown()))
              .then()
              .log("SERVER_OUTBOUND")
              .subscribe();
          return c.onDispose();
        });

    AeronDuplex client = createConnection();

    Mono //
        .delay(Duration.ofSeconds(1))
        .doOnSuccess(avoid -> client.dispose())
        .subscribe();

    boolean await = serverConnectionLatch.await(3, TimeUnit.SECONDS);
    assertTrue(await, "serverConnectionLatch: " + serverConnectionLatch.getCount());
  }

  private AeronDuplex<DirectBuffer> createConnection() {
    return AeronClient.create(resources)
        .options("localhost", serverPort, serverControlPort)
        .connect()
        .block(TIMEOUT);
  }

  private OnDisposable createServer(
      Function<? super AeronDuplex<DirectBuffer>, ? extends Publisher<Void>> handler) {
    return AeronServer.create(resources)
        .options("localhost", serverPort, serverControlPort)
        .handle(handler)
        .bind()
        .block(TIMEOUT);
  }
}
