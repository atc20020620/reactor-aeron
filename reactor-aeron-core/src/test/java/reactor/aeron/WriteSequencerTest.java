package reactor.aeron;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItems;
import static reactor.aeron.TestUtils.log;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

public class WriteSequencerTest {

  private Scheduler scheduler;

  private Scheduler scheduler1;

  private Scheduler scheduler2;

  /** Setup. */
  @BeforeEach
  public void doSetup() {
    scheduler = Schedulers.newSingle("scheduler-sender");
    scheduler1 = Schedulers.newSingle("scheduler-A");
    scheduler2 = Schedulers.newSingle("scheduler-B");
  }

  /** Teardown. */
  @AfterEach
  public void doTeardown() {
    scheduler.dispose();
    scheduler1.dispose();
    scheduler2.dispose();
  }

  @Test
  public void itProvidesSignalsFromAddedPublishers() throws Exception {

    TestWriteSequencer sequencer = new TestWriteSequencer(scheduler);

    Flux<ByteBuffer> flux1 =
        Flux.just("Hello", "world").map(AeronUtils::stringToByteBuffer).publishOn(scheduler1);
    Mono result1 = sequencer.add(flux1);

    Flux<ByteBuffer> flux2 =
        Flux.just("Everybody", "happy").map(AeronUtils::stringToByteBuffer).publishOn(scheduler2);
    Mono result2 = sequencer.add(flux2);

    result1.block();
    result2.block();

    assertThat(sequencer.getSignals(), hasItems("Hello", "world", "Everybody", "happy"));
  }

  static class TestWriteSequencer extends AeronWriteSequencer {

    static final Consumer<Throwable> ERROR_HANDLER =
        th -> System.err.println("Unexpected exception: " + th);

    private final SubscriberForTest inner;

    TestWriteSequencer(Scheduler scheduler) {
      super(scheduler, "test", null, 1234);
      this.inner = new SubscriberForTest(this, null, 1234);
    }

    @Override
    PublisherSender getInner() {
      return inner;
    }

    @Override
    Consumer<Throwable> getErrorHandler() {
      return ERROR_HANDLER;
    }

    List<String> getSignals() {
      return inner
          .signals
          .stream()
          .map(AeronUtils::byteBufferToString)
          .collect(Collectors.toList());
    }

    static class SubscriberForTest extends PublisherSender {

      final List<ByteBuffer> signals = new CopyOnWriteArrayList<>();

      SubscriberForTest(
          AeronWriteSequencer sequencer, MessagePublication publication, long sessionId) {
        super(sequencer, publication, sessionId);
      }

      public void doOnNext(ByteBuffer o) {
        log("onNext: " + o);
        signals.add(o);
      }

      void doOnSubscribe() {
        request(Long.MAX_VALUE);
      }

      public void doOnError(Throwable t) {
        log("onError: " + t);
        super.doOnError(t);
      }

      public void doOnComplete() {
        log("onComplete");
        super.doOnComplete();
      }
    }
  }
}
