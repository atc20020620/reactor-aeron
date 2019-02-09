package reactor.aeron.demo.pure;

import io.aeron.Aeron;
import io.aeron.Aeron.Context;
import io.aeron.Publication;
import io.aeron.driver.Configuration;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import io.aeron.logbuffer.BufferClaim;
import java.io.File;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;
import org.agrona.DirectBuffer;
import org.agrona.IoUtil;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.BackoffIdleStrategy;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.UnsafeBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Exceptions;

class RawAeronResources {

  private static final Logger logger = LoggerFactory.getLogger(RawAeronServerThroughput.class);

  static Aeron start() {

    Supplier<IdleStrategy> idleStrategySupplier = () -> new BackoffIdleStrategy(1, 1, 1, 100);

    String aeronDirectoryName =
        IoUtil.tmpDirName()
            + "aeron"
            + '-'
            + System.getProperty("user.name", "default")
            + '-'
            + UUID.randomUUID().toString();

    MediaDriver.Context mediaContext =
        new MediaDriver.Context()
            .errorHandler(th -> logger.warn("Exception occurred on MediaDriver: " + th, th))
            .mtuLength(Configuration.MTU_LENGTH)
            .imageLivenessTimeoutNs(
                Duration.ofNanos(Configuration.IMAGE_LIVENESS_TIMEOUT_NS).toNanos())
            .warnIfDirectoryExists(true)
            .dirDeleteOnStart(true)
            .threadingMode(ThreadingMode.DEDICATED)
            .conductorIdleStrategy(idleStrategySupplier.get())
            .receiverIdleStrategy(idleStrategySupplier.get())
            .senderIdleStrategy(idleStrategySupplier.get())
            .termBufferSparseFile(false)
            .publicationReservedSessionIdLow(0)
            .publicationReservedSessionIdHigh(Integer.MAX_VALUE)
            .aeronDirectoryName(aeronDirectoryName);

    MediaDriver mediaDriver = MediaDriver.launchEmbedded(mediaContext);

    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  File aeronDirectory = Paths.get(mediaDriver.aeronDirectoryName()).toFile();
                  if (aeronDirectory.exists()) {
                    IoUtil.delete(aeronDirectory, true);
                  }
                }));

    return Aeron.connect(
        new Context()
            .errorHandler(th -> logger.warn("Aeron exception occurred: " + th, th))
            .aeronDirectoryName(mediaDriver.aeronDirectoryName()));
  }

  static class MsgPublication {
    private static final ThreadLocal<BufferClaim> bufferClaims =
        ThreadLocal.withInitial(BufferClaim::new);

    private final Publication publication;
    private final int writeLimit;

    MsgPublication(Publication publication, int writeLimit) {
      this.publication = publication;
      this.writeLimit = writeLimit;
    }

    int proceed(DirectBuffer buffer) {
      int result = 0;
      for (int i = 0, current; i < writeLimit; i++) {
        current = proceed0(buffer);
        if (current < 1) {
          break;
        }
        result += current;
      }
      return result;
    }

    private int proceed0(DirectBuffer buffer) {
      long result = publish(buffer);

      if (result > 0) {
        return 1;
      }

      // Handle closed publication
      if (result == Publication.CLOSED) {
        logger.warn("aeron.Publication is CLOSED: {}", this);
        return 0;
      }

      // Handle max position exceeded
      if (result == Publication.MAX_POSITION_EXCEEDED) {
        logger.warn("aeron.Publication received MAX_POSITION_EXCEEDED: {}", this);
        return 0;
      }
      return 0;
    }

    private long publish(DirectBuffer buffer) {
      int length = buffer.capacity();

      if (length < publication.maxPayloadLength()) {
        BufferClaim bufferClaim = bufferClaims.get();
        long result = publication.tryClaim(length, bufferClaim);
        if (result > 0) {
          try {
            MutableDirectBuffer directBuffer = bufferClaim.buffer();
            int offset = bufferClaim.offset();
            directBuffer.putBytes(offset, buffer, 0, length);
            bufferClaim.commit();
          } catch (Exception ex) {
            bufferClaim.abort();
            throw Exceptions.propagate(ex);
          }
        }
        return result;
      } else {
        return publication.offer(new UnsafeBuffer(buffer, 0, length));
      }
    }
  }
}