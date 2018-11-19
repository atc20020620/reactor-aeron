package reactor.ipc.aeron.client;

import io.aeron.Subscription;
import io.aeron.driver.AeronResources;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import org.reactivestreams.Publisher;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoProcessor;
import reactor.ipc.aeron.AeronConnector;
import reactor.ipc.aeron.AeronInbound;
import reactor.ipc.aeron.AeronOutbound;
import reactor.ipc.aeron.Connection;
import reactor.ipc.aeron.DefaultAeronOutbound;
import reactor.ipc.aeron.HeartbeatSender;
import reactor.ipc.aeron.HeartbeatWatchdog;
import reactor.util.Logger;
import reactor.util.Loggers;

public final class AeronClientConnector implements AeronConnector, Disposable {

  private static final Logger logger = Loggers.getLogger(AeronClientConnector.class);

  private final AeronClientOptions options;

  private final String name;

  private static final AtomicInteger streamIdCounter = new AtomicInteger();

  private final ClientControlMessageSubscriber controlMessageSubscriber;

  private final Subscription controlSubscription;

  private final int clientControlStreamId;

  private final HeartbeatSender heartbeatSender;

  private final List<ClientHandler> handlers = new CopyOnWriteArrayList<>();

  private final HeartbeatWatchdog heartbeatWatchdog;

  private final AeronResources aeronResources;

  AeronClientConnector(String name, AeronResources aeronResources, AeronClientOptions options) {
    this.options = options;
    this.name = name == null ? "client" : name;
    this.aeronResources = aeronResources;
    this.heartbeatWatchdog = new HeartbeatWatchdog(options.heartbeatTimeoutMillis(), this.name);
    this.controlMessageSubscriber =
        new ClientControlMessageSubscriber(name, heartbeatWatchdog, this::dispose);
    this.clientControlStreamId = streamIdCounter.incrementAndGet();
    this.heartbeatSender = new HeartbeatSender(options.heartbeatTimeoutMillis(), this.name);
    this.controlSubscription =
        aeronResources.controlSubscription(
            name,
            options.clientChannel(),
            clientControlStreamId,
            "to receive control requests on",
            0,
            controlMessageSubscriber);
  }

  @Override
  public Mono<Connection> newHandler(
      BiFunction<AeronInbound, AeronOutbound, ? extends Publisher<Void>> ioHandler) {
    ClientHandler handler = new ClientHandler();
    return handler.initialise();
  }

  @Override
  public void dispose() {
    handlers.forEach(ClientHandler::dispose);
    handlers.clear();
    aeronResources.close(controlSubscription);
  }

  private void dispose(long sessionId) {
    handlers
        .stream()
        .filter(handler -> handler.sessionId == sessionId)
        .findFirst()
        .ifPresent(ClientHandler::dispose);
  }

  class ClientHandler implements Connection {

    private final DefaultAeronOutbound outbound;

    private final int clientSessionStreamId;

    private final ClientConnector connector;

    private volatile AeronClientInbound inbound;

    private volatile long sessionId;

    private final MonoProcessor<Void> onClose = MonoProcessor.create();

    ClientHandler() {
      this.clientSessionStreamId = streamIdCounter.incrementAndGet();
      this.outbound =
          new DefaultAeronOutbound(name, aeronResources, options.serverChannel(), options);
      this.connector =
          new ClientConnector(
              name,
              aeronResources,
              options,
              controlMessageSubscriber,
              heartbeatSender,
              clientControlStreamId,
              clientSessionStreamId);

      this.onClose
          .doOnTerminate(this::dispose0)
          .subscribe(null, th -> logger.warn("SessionHandler disposed with error: {}", th));
    }

    Mono<Connection> initialise() {
      handlers.add(this);

      return connector
          .connect()
          .flatMap(
              connectAckResponse -> {
                inbound =
                    new AeronClientInbound(
                        name,
                        aeronResources,
                        options.clientChannel(),
                        clientSessionStreamId,
                        connectAckResponse.sessionId,
                        this::dispose);

                this.sessionId = connectAckResponse.sessionId;
                heartbeatWatchdog.add(
                    connectAckResponse.sessionId,
                    this::dispose,
                    () -> inbound.getLastSignalTimeNs());

                return outbound.initialise(
                    connectAckResponse.sessionId, connectAckResponse.serverSessionStreamId);
              })
          .doOnError(
              th -> {
                logger.error(
                    "[{}] Occurred exception for sessionId: {} clientSessionStreamId: {}, error: ",
                    name,
                    sessionId,
                    clientSessionStreamId,
                    th);
                dispose();
              })
          .then(Mono.just(this));
    }

    @Override
    public AeronInbound inbound() {
      return inbound;
    }

    @Override
    public AeronOutbound outbound() {
      return outbound;
    }

    @Override
    public Mono<Void> onDispose() {
      return onClose;
    }

    @Override
    public void dispose() {
      if (!onClose.isDisposed()) {
        onClose.onComplete();
      }
    }

    @Override
    public boolean isDisposed() {
      return onClose.isDisposed();
    }

    public void dispose0() {
      handlers.remove(this);
      heartbeatWatchdog.remove(sessionId);
      connector.dispose();
      if (inbound != null) {
        inbound.dispose();
      }
      outbound.dispose();

      logger.debug("[{}] Closed session with Id: {}", name, sessionId);
    }
  }
}
