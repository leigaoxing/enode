package org.enodeframework.eventing

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import org.enodeframework.common.exception.DuplicateEventStreamException
import org.enodeframework.common.extensions.SystemClock
import org.enodeframework.common.function.Action1
import org.enodeframework.common.io.Task
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

class EventCommittingContextMailBox(
    private val number: Int,
    private val batchSize: Int,
    private val coroutineDispatcher: CoroutineDispatcher,
    handleEventAction: Action1<List<EventCommittingContext>>
) {
    private val lockObj = Any()
    private val asyncLockObj = Any()
    private val aggregateDictDict: ConcurrentHashMap<String, ConcurrentHashMap<String, Byte>> = ConcurrentHashMap()
    private val messageQueue: ConcurrentLinkedQueue<EventCommittingContext> = ConcurrentLinkedQueue()
    private val handleMessageAction: Action1<List<EventCommittingContext>> = handleEventAction
    private var lastActiveTime: Date = Date()

    private var isRunning = false

    private fun totalUnHandledMessageCount(): Long {
        return messageQueue.count().toLong()
    }

    fun getNumber() {
        number
    }

    /**
     * 放入一个消息到MailBox，并自动尝试运行MailBox
     */
    fun enqueueMessage(message: EventCommittingContext) {
        synchronized(lockObj) {
            val eventDict =
                aggregateDictDict.computeIfAbsent(message.eventStream.aggregateRootId) { ConcurrentHashMap() }
            // If the specified key is not already associated with a value (or is mapped to null) associates it with the given value and returns null, else returns the current value.
            if (eventDict.putIfAbsent(message.eventStream.id, ONE_BYTE) == null) {
                message.mailBox = this
                messageQueue.add(message)
                if (logger.isDebugEnabled) {
                    logger.debug("{} enqueued new message, mailboxNumber: {}, aggregateRootId: {}, commandId: {}, eventVersion: {}, eventStreamId: {}, eventIds: {}",
                        javaClass.name,
                        number,
                        message.eventStream.aggregateRootId,
                        message.processingCommand.message.id,
                        message.eventStream.version,
                        message.eventStream.id,
                        message.eventStream.events.joinToString("|") { obj: DomainEventMessage<*> -> obj.id })
                }
                lastActiveTime = Date()
                tryRun()
            } else {
                throw DuplicateEventStreamException(message.eventStream)
            }
        }
    }

    /**
     * 尝试运行一次MailBox，一次运行会处理一个消息或者一批消息，当前MailBox不能是运行中或者暂停中或者已暂停
     */
    private fun tryRun() {
        synchronized(lockObj) {
            if (isRunning) {
                return
            }
            setAsRunning()
            if (logger.isDebugEnabled) {
                logger.debug("{} start run, mailboxNumber: {}", javaClass.name, number)
            }
            CoroutineScope(coroutineDispatcher).async { processMessages() }
            return
        }
    }

    /**
     * 请求完成MailBox的单次运行，如果MailBox中还有剩余消息，则继续尝试运行下一次
     */
    fun completeRun() {
        lastActiveTime = Date()
        if (logger.isDebugEnabled) {
            logger.debug("{} complete run, mailboxNumber: {}", javaClass.name, number)
        }
        setAsNotRunning()
        if (totalUnHandledMessageCount() > 0) {
            tryRun()
        }
    }

    fun removeAggregateAllEventCommittingContexts(aggregateRootId: String) {
        aggregateDictDict.remove(aggregateRootId)
    }

    fun isInactive(timeoutSeconds: Int): Boolean {
        return SystemClock.now() - lastActiveTime.time >= timeoutSeconds
    }

    private fun processMessages() {
        synchronized(asyncLockObj) {
            lastActiveTime = Date()
            val messageList: MutableList<EventCommittingContext> = ArrayList()
            while (messageList.size < batchSize) {
                val message = messageQueue.poll()
                if (message != null) {
                    val eventDict = aggregateDictDict[message.eventStream.aggregateRootId]
                    if (eventDict != null) {
                        if (eventDict.remove(message.eventStream.id) != null) {
                            messageList.add(message)
                        }
                    }
                } else {
                    break
                }
            }
            if (messageList.isEmpty()) {
                completeRun()
                return
            }
            try {
                handleMessageAction.apply(messageList)
            } catch (ex: Exception) {
                logger.error("{} run has unknown exception, mailboxNumber: {}", javaClass.name, number, ex)
                Task.sleep(1)
                completeRun()
            }
        }
    }

    private fun setAsRunning() {
        isRunning = true
    }

    private fun setAsNotRunning() {
        isRunning = false
    }

    companion object {
        val logger: Logger = LoggerFactory.getLogger(EventCommittingContextMailBox::class.java)
        private const val ONE_BYTE: Byte = 1
    }

    init {
        lastActiveTime = Date()
    }
}