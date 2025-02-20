package org.enodeframework.mongo.handler

import com.google.common.base.Strings
import com.google.common.collect.Lists
import com.mongodb.MongoBulkWriteException
import com.mongodb.MongoServerException
import com.mongodb.MongoWriteException
import io.vertx.core.AsyncResult
import io.vertx.core.Handler
import io.vertx.ext.mongo.MongoClientBulkWriteResult
import org.enodeframework.common.exception.EventStoreException
import org.enodeframework.common.exception.IORuntimeException
import org.enodeframework.configurations.EventStoreOptions
import org.enodeframework.eventing.AggregateEventAppendResult
import org.enodeframework.eventing.EventAppendStatus
import org.enodeframework.mongo.MongoEventStore
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture

class MongoAddDomainEventsHandler(
    private val options: EventStoreOptions,
    private val msg: String,
) : Handler<AsyncResult<MongoClientBulkWriteResult>> {
    companion object {
        private val logger = LoggerFactory.getLogger(MongoEventStore::class.java)
    }

    val future = CompletableFuture<AggregateEventAppendResult>()

    override fun handle(ar: AsyncResult<MongoClientBulkWriteResult>) {
        if (ar.succeeded()) {
            val appendResult = AggregateEventAppendResult()
            appendResult.eventAppendStatus = EventAppendStatus.Success
            future.complete(appendResult)
            return
        }
        val throwable = ar.cause()
        var message = ""
        if (throwable is MongoWriteException) {
            message = throwable.message!!
        }
        if (throwable is MongoBulkWriteException) {
            if (throwable.writeErrors.size >= 1) {
                val writeError = throwable.writeErrors[0]
                message = writeError.message
            }
        }
        if (message.contains(options.eventVersionUkName)) {
            val appendResult = AggregateEventAppendResult()
            appendResult.eventAppendStatus = EventAppendStatus.DuplicateEvent
            future.complete(appendResult)
            return
        }
        if (message.contains(options.eventCommandIdUkName)) {
            val appendResult = AggregateEventAppendResult()
            appendResult.eventAppendStatus = EventAppendStatus.DuplicateCommand
            val commandId = options.parseDuplicatedId(message)
            if (!Strings.isNullOrEmpty(commandId)) {
                appendResult.duplicateCommandIds = Lists.newArrayList(commandId)
            } else {
                appendResult.duplicateCommandIds = Lists.newArrayList(message)
            }
            future.complete(appendResult)
            return
        }
        logger.error("Batch append event has exception. {}", msg, throwable)
        if (throwable is MongoServerException) {
            future.completeExceptionally(IORuntimeException(msg, throwable))
        }
        future.completeExceptionally(EventStoreException(msg, throwable))
        return
    }
}