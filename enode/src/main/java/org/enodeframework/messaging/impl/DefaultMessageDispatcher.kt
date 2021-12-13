package org.enodeframework.messaging.impl

import com.google.common.collect.Lists
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.future.asCompletableFuture
import org.enodeframework.common.function.Action4
import org.enodeframework.common.io.IOHelper
import org.enodeframework.common.io.Task
import org.enodeframework.common.serializing.SerializeService
import org.enodeframework.infrastructure.ObjectProxy
import org.enodeframework.infrastructure.TypeNameProvider
import org.enodeframework.messaging.*
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture

/**
 * @author anruence@gmail.com
 */
class DefaultMessageDispatcher(
    private val typeNameProvider: TypeNameProvider,
    private val messageHandlerProvider: MessageHandlerProvider,
    private val twoMessageHandlerProvider: TwoMessageHandlerProvider,
    private val threeMessageHandlerProvider: ThreeMessageHandlerProvider,
    private val serializeService: SerializeService,
    private val coroutineDispatcher: CoroutineDispatcher,
) : MessageDispatcher {

    override fun dispatchMessageAsync(message: Message): CompletableFuture<Boolean> {
        return dispatchMessages(Lists.newArrayList(message))
    }

    override fun dispatchMessagesAsync(messages: List<Message>): CompletableFuture<Boolean> {
        return dispatchMessages(messages)
    }

    private fun dispatchMessages(messages: List<Message>): CompletableFuture<Boolean> {
        val messageCount = messages.size
        if (messageCount == 0) {
            return Task.completedTask
        }
        val rootDispatching = RootDispatching()
        //先对每个事件调用其Handler
        val queueMessageDispatching = QueueMessageDispatching(this, rootDispatching, messages)
        dispatchSingleMessage(queueMessageDispatching.dequeueMessage(), queueMessageDispatching)
        //如果有至少两个事件，则尝试调用针对两个事件的Handler
        if (messageCount >= 2) {
            val twoMessageHandlers =
                twoMessageHandlerProvider.getHandlers(messages.map { x: Message -> x.javaClass }.toList())
            if (twoMessageHandlers.isNotEmpty()) {
                dispatchMultiMessage(
                    messages, twoMessageHandlers, rootDispatching
                ) { multiMessageDispatching: MultiMessageDispatching, handlerProxy: MessageHandlerProxy2, queueHandler: QueuedHandler<MessageHandlerProxy2>?, retryTimes: Int ->
                    dispatchTwoMessageToHandlerAsync(
                        multiMessageDispatching, handlerProxy, queueHandler, retryTimes
                    )
                }
            }
        }
        //如果有至少三个事件，则尝试调用针对三个事件的Handler
        if (messageCount >= 3) {
            val threeMessageHandlers =
                threeMessageHandlerProvider.getHandlers(messages.map { x: Message -> x.javaClass }.toList())
            if (threeMessageHandlers.isNotEmpty()) {
                dispatchMultiMessage(
                    messages, threeMessageHandlers, rootDispatching
                ) { multiMessageDispatching: MultiMessageDispatching, handlerProxy: MessageHandlerProxy3, queueHandler: QueuedHandler<MessageHandlerProxy3>?, retryTimes: Int ->
                    dispatchThreeMessageToHandlerAsync(
                        multiMessageDispatching, handlerProxy, queueHandler, retryTimes
                    )
                }
            }
        }
        return rootDispatching.taskCompletionSource
    }

    fun dispatchSingleMessage(message: Message, queueMessageDispatching: QueueMessageDispatching) {
        val messageHandlerDataList = messageHandlerProvider.getHandlers(message.javaClass)
        if (messageHandlerDataList.isEmpty()) {
            queueMessageDispatching.onMessageHandled(message)
            return
        }
        messageHandlerDataList.forEach { messageHandlerData: MessageHandlerData<MessageHandlerProxy1> ->
            val singleMessageDispatching = SingleMessageDispatching(
                message, queueMessageDispatching, messageHandlerData.allHandlers, typeNameProvider
            )
            if (messageHandlerData.listHandlers.isNotEmpty()) {
                messageHandlerData.listHandlers.forEach { handler: MessageHandlerProxy1 ->
                    dispatchSingleMessageToHandlerAsync(
                        singleMessageDispatching, handler, null, 0
                    )
                }
            }
            if (messageHandlerData.queuedHandlers.isNotEmpty()) {
                val queueHandler =
                    QueuedHandler(messageHandlerData.queuedHandlers) { queuedHandler: QueuedHandler<MessageHandlerProxy1>?, nextHandler: MessageHandlerProxy1 ->
                        dispatchSingleMessageToHandlerAsync(
                            singleMessageDispatching, nextHandler, queuedHandler, 0
                        )
                    }
                dispatchSingleMessageToHandlerAsync(
                    singleMessageDispatching, queueHandler.dequeueHandler(), queueHandler, 0
                )
            }
        }
    }

    private fun <T : ObjectProxy> dispatchMultiMessage(
        messages: List<Message>,
        messageHandlerDataList: List<MessageHandlerData<T>>,
        rootDispatching: RootDispatching,
        dispatchAction: Action4<MultiMessageDispatching, T, QueuedHandler<T>?, Int>
    ) {
        messageHandlerDataList.forEach { messageHandlerData: MessageHandlerData<T> ->
            val multiMessageDispatching =
                MultiMessageDispatching(messages, messageHandlerData.allHandlers, rootDispatching, typeNameProvider)
            if (messageHandlerData.listHandlers.isNotEmpty()) {
                messageHandlerData.listHandlers.forEach { handler: T ->
                    dispatchAction.apply(
                        multiMessageDispatching, handler, null, 0
                    )
                }
            }
            if (messageHandlerData.queuedHandlers.isNotEmpty()) {
                val queuedHandler =
                    QueuedHandler(messageHandlerData.queuedHandlers) { currentQueuedHandler: QueuedHandler<T>?, nextHandler: T ->
                        dispatchAction.apply(
                            multiMessageDispatching, nextHandler, currentQueuedHandler, 0
                        )
                    }
                dispatchAction.apply(multiMessageDispatching, queuedHandler.dequeueHandler(), queuedHandler, 0)
            }
        }
    }

    private fun dispatchSingleMessageToHandlerAsync(
        singleMessageDispatching: SingleMessageDispatching,
        handlerProxy: MessageHandlerProxy1,
        queueHandler: QueuedHandler<MessageHandlerProxy1>?,
        retryTimes: Int
    ) {
        val message = singleMessageDispatching.message
        val messageTypeName = typeNameProvider.getTypeName(message.javaClass)
        val handlerType: Class<*> = handlerProxy.getInnerObject().javaClass
        val handlerTypeName = typeNameProvider.getTypeName(handlerType)
        handleSingleMessageAsync(
            singleMessageDispatching, handlerProxy, handlerTypeName, messageTypeName, queueHandler, retryTimes
        )
    }

    private fun dispatchTwoMessageToHandlerAsync(
        multiMessageDispatching: MultiMessageDispatching,
        handlerProxy: MessageHandlerProxy2,
        queueHandler: QueuedHandler<MessageHandlerProxy2>?,
        retryTimes: Int
    ) {
        val handlerType: Class<*> = handlerProxy.getInnerObject().javaClass
        val handlerTypeName = typeNameProvider.getTypeName(handlerType)
        handleTwoMessageAsync(multiMessageDispatching, handlerProxy, handlerTypeName, queueHandler, 0)
    }

    private fun dispatchThreeMessageToHandlerAsync(
        multiMessageDispatching: MultiMessageDispatching,
        handlerProxy: MessageHandlerProxy3,
        queueHandler: QueuedHandler<MessageHandlerProxy3>?,
        retryTimes: Int
    ) {
        val handlerType: Class<*> = handlerProxy.getInnerObject().javaClass
        val handlerTypeName = typeNameProvider.getTypeName(handlerType)
        handleThreeMessageAsync(multiMessageDispatching, handlerProxy, handlerTypeName, queueHandler, 0)
    }

    private fun handleSingleMessageAsync(
        singleMessageDispatching: SingleMessageDispatching,
        handlerProxy: MessageHandlerProxy1,
        handlerTypeName: String,
        messageTypeName: String,
        queueHandler: QueuedHandler<MessageHandlerProxy1>?,
        retryTimes: Int
    ) {
        val message = singleMessageDispatching.message

        IOHelper.tryAsyncActionRecursivelyWithoutResult("HandleSingleMessageAsync", {
            CoroutineScope(coroutineDispatcher).async {
                handlerProxy.handleAsync(message)
            }.asCompletableFuture()
        }, {
            singleMessageDispatching.removeHandledHandler(handlerTypeName)
            queueHandler?.onHandlerFinished(handlerProxy)
            if (logger.isDebugEnabled) {
                logger.debug("message handled success, messages: {}", serializeService.serialize(message))
            }
        }, {
            String.format(
                "[message: %s, handlerType: %s]",
                serializeService.serialize(message),
                handlerProxy.getInnerObject().javaClass.name
            )
        }, null, retryTimes, true)
    }

    private fun handleTwoMessageAsync(
        multiMessageDispatching: MultiMessageDispatching,
        handlerProxy: MessageHandlerProxy2,
        handlerTypeName: String,
        queueHandler: QueuedHandler<MessageHandlerProxy2>?,
        retryTimes: Int
    ) {
        val messages = multiMessageDispatching.messages
        val message1 = messages[0]
        val message2 = messages[1]
        IOHelper.tryAsyncActionRecursively("HandleTwoMessageAsync", {
            CoroutineScope(coroutineDispatcher).async {
                handlerProxy.handleAsync(message1, message2)
            }.asCompletableFuture()
        }, {
            multiMessageDispatching.removeHandledHandler(handlerTypeName)
            queueHandler?.onHandlerFinished(handlerProxy)
            if (logger.isDebugEnabled) {
                logger.debug("TwoMessage handled success, messages: {}", serializeService.serialize(messages))
            }
        }, {
            String.format(
                "[messages: %s, handlerType: %s]",
                serializeService.serialize(messages),
                handlerProxy.getInnerObject().javaClass.name
            )
        }, null, retryTimes, true)
    }

    private fun handleThreeMessageAsync(
        multiMessageDispatching: MultiMessageDispatching,
        handlerProxy: MessageHandlerProxy3,
        handlerTypeName: String,
        queueHandler: QueuedHandler<MessageHandlerProxy3>?,
        retryTimes: Int
    ) {
        val messages = multiMessageDispatching.messages
        val message1 = messages[0]
        val message2 = messages[1]
        val message3 = messages[2]
        IOHelper.tryAsyncActionRecursively("HandleThreeMessageAsync", {
            CoroutineScope(coroutineDispatcher).async {
                handlerProxy.handleAsync(message1, message2, message3)
            }.asCompletableFuture()
        }, {
            multiMessageDispatching.removeHandledHandler(handlerTypeName)
            queueHandler?.onHandlerFinished(handlerProxy)
            if (logger.isDebugEnabled) {
                logger.debug("ThreeMessage handled success, messages: {}", serializeService.serialize(messages))
            }
        }, {
            String.format(
                "[messages: %s, handlerType: %s]",
                serializeService.serialize(messages),
                handlerProxy.getInnerObject().javaClass.name
            )
        }, null, retryTimes, true)
    }

    companion object {
        private val logger = LoggerFactory.getLogger(DefaultMessageDispatcher::class.java)
    }
}