package org.enodeframework.test.mock;

import org.enodeframework.common.exception.EnodeException;
import org.enodeframework.common.exception.IORuntimeException;
import org.enodeframework.eventing.DomainEventStream;
import org.enodeframework.eventing.EventAppendResult;
import org.enodeframework.eventing.EventStore;
import org.enodeframework.eventing.impl.InMemoryEventStore;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public class MockEventStore implements EventStore {
    private final InMemoryEventStore memoryEventStore = new InMemoryEventStore();
    public boolean supportBatchAppendEvent;
    private int expectFailedCount = 0;
    private int currentFailedCount = 0;
    private FailedType failedType;
    private int expectGetFailedCount = 0;
    private int currentGetFailedCount = 0;

    public MockEventStore() {
        supportBatchAppendEvent = true;
    }

    public void Reset() {
        failedType = FailedType.None;
        expectFailedCount = 0;
        currentFailedCount = 0;
        expectGetFailedCount = 0;
        currentGetFailedCount = 0;
    }

    public void SetExpectFailedCount(FailedType failedType, int count) {
        this.failedType = failedType;
        expectFailedCount = count;
        expectGetFailedCount = count;
    }

    @Override
    public CompletableFuture<EventAppendResult> batchAppendAsync(List<? extends DomainEventStream> eventStreams) {
        if (currentFailedCount < expectFailedCount) {
            currentFailedCount++;
            if (failedType == FailedType.UnKnownException) {
                throw new EnodeException("BatchAppendAsyncUnKnownException" + currentFailedCount);
            } else if (failedType == FailedType.IOException) {
                throw new IORuntimeException("BatchAppendAsyncIOException" + currentFailedCount);
            } else if (failedType == FailedType.TaskIOException) {
            }
        }
        return memoryEventStore.batchAppendAsync(eventStreams);
    }

    @Override
    public CompletableFuture<DomainEventStream> findAsync(String aggregateRootId, int version) {
        if (currentFailedCount < expectFailedCount) {
            currentFailedCount++;
            if (failedType == FailedType.UnKnownException) {
                throw new EnodeException("AppendAsyncUnKnownException" + currentFailedCount);
            } else if (failedType == FailedType.IOException) {
                throw new IORuntimeException("AppendAsyncIOException" + currentFailedCount);
            } else if (failedType == FailedType.TaskIOException) {
            }
        }
        return memoryEventStore.findAsync(aggregateRootId, version);
    }

    @Override
    public CompletableFuture<DomainEventStream> findAsync(String aggregateRootId, String commandId) {
        return memoryEventStore.findAsync(aggregateRootId, commandId);
    }

    @Override
    public CompletableFuture<List<DomainEventStream>> queryAggregateEventsAsync(String aggregateRootId, String aggregateRootTypeName, int minVersion, int maxVersion) {
        return memoryEventStore.queryAggregateEventsAsync(aggregateRootId, aggregateRootTypeName, minVersion, maxVersion);
    }
}
