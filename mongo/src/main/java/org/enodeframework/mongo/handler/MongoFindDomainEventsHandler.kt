package org.enodeframework.mongo.handler

import com.google.common.collect.Maps
import com.mongodb.MongoServerException
import io.vertx.core.AsyncResult
import io.vertx.core.Handler
import io.vertx.core.json.JsonObject
import org.enodeframework.common.exception.EventStoreException
import org.enodeframework.common.exception.IORuntimeException
import org.enodeframework.common.serializing.SerializeService
import org.enodeframework.common.utils.EventStoreUtil
import org.enodeframework.eventing.DomainEventStream
import org.enodeframework.eventing.EventSerializer
import org.enodeframework.mongo.MongoEventStore
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture

class MongoFindDomainEventsHandler(
    private val eventSerializer: EventSerializer,
    private val serializeService: SerializeService,
    private val msg: String
) : Handler<AsyncResult<List<JsonObject>>> {

    companion object {
        private val logger = LoggerFactory.getLogger(MongoEventStore::class.java)
    }

    val future = CompletableFuture<List<DomainEventStream>>()

    override fun handle(ar: AsyncResult<List<JsonObject>>) {
        if (ar.succeeded()) {
            val documents = ar.result()
            val streams = documents.map { document ->
                DomainEventStream(
                    document.getString("commandId"),
                    document.getString("aggregateRootId"),
                    document.getString("aggregateRootTypeName"),
                    EventStoreUtil.toDate(document.getValue("gmtCreate")),
                    eventSerializer.deserialize(
                        (serializeService.deserialize(
                            document.getString("events"), MutableMap::class.java
                        ) as MutableMap<String, String>)
                    ),
                    Maps.newHashMap()
                )
            }.toMutableList()
            streams.sortWith(Comparator.comparingInt { obj: DomainEventStream -> obj.version })
            future.complete(streams)
            return
        }
        val throwable = ar.cause()
        if (throwable is MongoServerException) {
            logger.error("Failed to query aggregate events async, msg: {}", msg, throwable)
            future.completeExceptionally(IORuntimeException(msg, throwable))
            return
        }
        logger.error("Failed to query aggregate events async, msg: {}", msg, throwable)
        future.completeExceptionally(EventStoreException(msg, throwable))
        return
    }
}