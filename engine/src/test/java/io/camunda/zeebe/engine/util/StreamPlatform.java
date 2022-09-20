/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.engine.util;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.camunda.zeebe.db.ZeebeDb;
import io.camunda.zeebe.db.ZeebeDbFactory;
import io.camunda.zeebe.engine.Loggers;
import io.camunda.zeebe.engine.api.CommandResponseWriter;
import io.camunda.zeebe.engine.api.EmptyProcessingResult;
import io.camunda.zeebe.engine.api.InterPartitionCommandSender;
import io.camunda.zeebe.engine.api.ReadonlyStreamProcessorContext;
import io.camunda.zeebe.engine.api.RecordProcessor;
import io.camunda.zeebe.engine.api.StreamProcessorLifecycleAware;
import io.camunda.zeebe.engine.state.appliers.EventAppliers;
import io.camunda.zeebe.logstreams.impl.log.LoggedEventImpl;
import io.camunda.zeebe.logstreams.log.LogStreamBatchWriter;
import io.camunda.zeebe.logstreams.log.LogStreamReader;
import io.camunda.zeebe.logstreams.log.LoggedEvent;
import io.camunda.zeebe.logstreams.storage.LogStorage;
import io.camunda.zeebe.logstreams.util.ListLogStorage;
import io.camunda.zeebe.logstreams.util.SyncLogStream;
import io.camunda.zeebe.logstreams.util.SynchronousLogStream;
import io.camunda.zeebe.scheduler.Actor;
import io.camunda.zeebe.scheduler.ActorScheduler;
import io.camunda.zeebe.scheduler.future.ActorFuture;
import io.camunda.zeebe.streamprocessor.StreamProcessor;
import io.camunda.zeebe.streamprocessor.StreamProcessorMode;
import io.camunda.zeebe.util.FileUtil;
import io.camunda.zeebe.util.buffer.BufferUtil;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.slf4j.Logger;

public final class StreamPlatform {

  private static final String SNAPSHOT_FOLDER = "snapshot";
  private static final Logger LOG = Loggers.STREAM_PROCESSING;
  private static final int DEFAULT_PARTITION = 1;
  private static final String STREAM_NAME = "stream-";

  private final Path dataDirectory;
  private final List<AutoCloseable> closeables;
  private final ActorScheduler actorScheduler;
  private final CommandResponseWriter mockCommandResponseWriter;
  private LogContext logContext;
  private ProcessorContext processorContext;
  private boolean snapshotWasTaken = false;
  private final StreamProcessorMode streamProcessorMode = StreamProcessorMode.PROCESSING;
  private List<RecordProcessor> recordProcessors;

  private final RecordProcessor defaultRecordProcessor;

  private final WriteActor writeActor = new WriteActor();
  private final ZeebeDbFactory zeebeDbFactory;

  public StreamPlatform(
      final Path dataDirectory,
      final List<AutoCloseable> closeables,
      final ActorScheduler actorScheduler,
      final ZeebeDbFactory zeebeDbFactory) {
    this.dataDirectory = dataDirectory;
    this.closeables = closeables;
    this.actorScheduler = actorScheduler;
    this.zeebeDbFactory = zeebeDbFactory;

    mockCommandResponseWriter = mock(CommandResponseWriter.class);
    when(mockCommandResponseWriter.intent(any())).thenReturn(mockCommandResponseWriter);
    when(mockCommandResponseWriter.key(anyLong())).thenReturn(mockCommandResponseWriter);
    when(mockCommandResponseWriter.partitionId(anyInt())).thenReturn(mockCommandResponseWriter);
    when(mockCommandResponseWriter.recordType(any())).thenReturn(mockCommandResponseWriter);
    when(mockCommandResponseWriter.rejectionType(any())).thenReturn(mockCommandResponseWriter);
    when(mockCommandResponseWriter.rejectionReason(any())).thenReturn(mockCommandResponseWriter);
    when(mockCommandResponseWriter.valueType(any())).thenReturn(mockCommandResponseWriter);
    when(mockCommandResponseWriter.valueWriter(any())).thenReturn(mockCommandResponseWriter);

    when(mockCommandResponseWriter.tryWriteResponse(anyInt(), anyLong())).thenReturn(true);
    actorScheduler.submitActor(writeActor);

    defaultRecordProcessor = mock(RecordProcessor.class);
    when(defaultRecordProcessor.process(any(), any())).thenReturn(EmptyProcessingResult.INSTANCE);
    when(defaultRecordProcessor.onProcessingError(any(), any(), any()))
        .thenReturn(EmptyProcessingResult.INSTANCE);
    when(defaultRecordProcessor.accepts(any())).thenReturn(true);
    recordProcessors = List.of(defaultRecordProcessor);
    closeables.add(() -> recordProcessors.clear());
  }

  public SynchronousLogStream createLogStream() {
    final var listLogStorage = new ListLogStorage();
    return createLogStream(
        listLogStorage,
        logStream -> listLogStorage.setPositionListener(logStream::setLastWrittenPosition));
  }

  private SynchronousLogStream createLogStream(
      final LogStorage logStorage, final Consumer<SyncLogStream> logStreamConsumer) {
    final var logStream =
        SyncLogStream.builder()
            .withLogName(STREAM_NAME + DEFAULT_PARTITION)
            .withLogStorage(logStorage)
            .withPartitionId(DEFAULT_PARTITION)
            .withActorSchedulingService(actorScheduler)
            .build();

    logStreamConsumer.accept(logStream);

    logContext = new LogContext(logStream);
    closeables.add(() -> logContext.close());
    return logStream;
  }

  public SynchronousLogStream getLogStream() {
    return logContext.logStream();
  }

  public Stream<LoggedEvent> events() {
    final SynchronousLogStream logStream = getLogStream();

    final LogStreamReader reader = logStream.newLogStreamReader();
    closeables.add(reader);

    reader.seekToFirstEvent();

    final Iterable<LoggedEvent> iterable = () -> reader;

    // copy to allow for collecting, which is what AssertJ does under the hood when using stream
    // assertions
    return StreamSupport.stream(iterable.spliterator(), false)
        .map(
            event -> {
              final var copyableEvent = (LoggedEventImpl) event;
              final var copiedBuffer =
                  BufferUtil.cloneBuffer(
                      copyableEvent.getBuffer(),
                      copyableEvent.getFragmentOffset(),
                      copyableEvent.getLength());
              final var copy = new LoggedEventImpl();
              copy.wrap(copiedBuffer, 0);

              return copy;
            });
  }

  public Path createRuntimeFolder(final SynchronousLogStream stream) {
    final Path rootDirectory = dataDirectory.resolve(stream.getLogName()).resolve("state");

    try {
      Files.createDirectories(rootDirectory);
    } catch (final FileAlreadyExistsException ignored) {
      // totally fine if it already exists
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }

    return rootDirectory.resolve("runtime");
  }

  public StreamPlatform withRecordProcessors(final List<RecordProcessor> recordProcessors) {
    this.recordProcessors = recordProcessors;
    return this;
  }

  public StreamProcessor startStreamProcessor() {
    final SynchronousLogStream stream = getLogStream();
    return buildStreamProcessor(stream, true);
  }

  public StreamProcessor startStreamProcessorNotAwaitOpening() {
    final SynchronousLogStream stream = getLogStream();
    return buildStreamProcessor(stream, false);
  }

  public StreamProcessor buildStreamProcessor(
      final SynchronousLogStream stream, final boolean awaitOpening) {
    final var storage = createRuntimeFolder(stream);
    final var snapshot = storage.getParent().resolve(SNAPSHOT_FOLDER);

    final var recoveredLatch = new CountDownLatch(1);
    final var recoveredAwaiter =
        new StreamProcessorLifecycleAware() {
          @Override
          public void onRecovered(final ReadonlyStreamProcessorContext context) {
            recoveredLatch.countDown();
          }
        };

    final ZeebeDb<?> zeebeDb;
    if (snapshotWasTaken) {
      zeebeDb = zeebeDbFactory.createDb(snapshot.toFile());
    } else {
      zeebeDb = zeebeDbFactory.createDb(storage.toFile());
    }

    final var builder =
        StreamProcessor.builder()
            .logStream(stream.getAsyncLogStream())
            .zeebeDb(zeebeDb)
            .actorSchedulingService(actorScheduler)
            .commandResponseWriter(mockCommandResponseWriter)
            .recordProcessors(recordProcessors)
            .eventApplierFactory(EventAppliers::new) // todo remove this soon
            .streamProcessorMode(streamProcessorMode)
            .partitionCommandSender(mock(InterPartitionCommandSender.class));

    builder.getLifecycleListeners().add(recoveredAwaiter);

    final StreamProcessor streamProcessor = builder.build();
    final var openFuture = streamProcessor.openAsync(false);

    if (awaitOpening) { // and recovery
      try {
        recoveredLatch.await(15, TimeUnit.SECONDS);
      } catch (final InterruptedException e) {
        Thread.interrupted();
      }
    }
    openFuture.join(15, TimeUnit.SECONDS);

    processorContext =
        ProcessorContext.createStreamContext(streamProcessor, zeebeDb, storage, snapshot);
    closeables.add(() -> processorContext.close());

    return streamProcessor;
  }

  public void pauseProcessing() {
    processorContext.streamProcessor.pauseProcessing().join();
    LOG.info("Paused processing for processor {}", processorContext.streamProcessor.getName());
  }

  public void resumeProcessing() {
    processorContext.streamProcessor.resumeProcessing();
    LOG.info("Resume processing for processor {}", processorContext.streamProcessor.getName());
  }

  public void snapshot() {
    processorContext.snapshot();
    snapshotWasTaken = true;
    LOG.info("Snapshot database for processor {}", processorContext.streamProcessor.getName());
  }

  public RecordProcessor getDefaultRecordProcessor() {
    return defaultRecordProcessor;
  }

  public StreamProcessor getStreamProcessor() {
    return Optional.ofNullable(processorContext)
        .map(c -> c.streamProcessor)
        .orElseThrow(() -> new NoSuchElementException("No stream processor found."));
  }

  public LogStreamBatchWriter setupBatchWriter(final RecordToWrite[] recordToWrites) {
    final SynchronousLogStream logStream = getLogStream();
    final LogStreamBatchWriter logStreamBatchWriter = logStream.newLogStreamBatchWriter();
    for (final RecordToWrite recordToWrite : recordToWrites) {
      logStreamBatchWriter
          .event()
          .key(recordToWrite.getKey())
          .sourceIndex(recordToWrite.getSourceIndex())
          .metadataWriter(recordToWrite.getRecordMetadata())
          .valueWriter(recordToWrite.getUnifiedRecordValue())
          .done();
    }
    return logStreamBatchWriter;
  }

  public long writeBatch(final RecordToWrite... recordsToWrite) {
    final var batchWriter = setupBatchWriter(recordsToWrite);
    return writeActor.submit(batchWriter::tryWrite).join();
  }

  /** Used to run writes within an actor thread. */
  private static final class WriteActor extends Actor {
    public ActorFuture<Long> submit(final Callable<Long> write) {
      return actor.call(write);
    }
  }

  private record LogContext(SynchronousLogStream logStream) implements AutoCloseable {
    @Override
    public void close() {
      logStream.close();
    }
  }

  private static final class ProcessorContext implements AutoCloseable {
    private final ZeebeDb zeebeDb;
    private final StreamProcessor streamProcessor;
    private final Path runtimePath;
    private final Path snapshotPath;
    private boolean closed = false;

    private ProcessorContext(
        final StreamProcessor streamProcessor,
        final ZeebeDb zeebeDb,
        final Path runtimePath,
        final Path snapshotPath) {
      this.streamProcessor = streamProcessor;
      this.zeebeDb = zeebeDb;
      this.runtimePath = runtimePath;
      this.snapshotPath = snapshotPath;
    }

    public static ProcessorContext createStreamContext(
        final StreamProcessor streamProcessor,
        final ZeebeDb zeebeDb,
        final Path runtimePath,
        final Path snapshotPath) {
      return new ProcessorContext(streamProcessor, zeebeDb, runtimePath, snapshotPath);
    }

    public void snapshot() {
      zeebeDb.createSnapshot(snapshotPath.toFile());
    }

    @Override
    public void close() throws Exception {
      if (closed) {
        return;
      }

      LOG.debug("Close stream processor");
      streamProcessor.closeAsync().join();
      zeebeDb.close();
      if (runtimePath.toFile().exists()) {
        FileUtil.deleteFolder(runtimePath);
      }
      closed = true;
    }
  }
}
