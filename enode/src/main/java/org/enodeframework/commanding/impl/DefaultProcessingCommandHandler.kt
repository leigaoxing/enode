package org.enodeframework.commanding.impl

import com.google.common.base.Strings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.future.asCompletableFuture
import org.enodeframework.commanding.*
import org.enodeframework.common.exception.AggregateRootReferenceChangedException
import org.enodeframework.common.io.IOHelper
import org.enodeframework.common.io.Task
import org.enodeframework.common.serializing.SerializeService
import org.enodeframework.configurations.SysProperties
import org.enodeframework.domain.AggregateRoot
import org.enodeframework.domain.DomainExceptionMessage
import org.enodeframework.domain.MemoryCache
import org.enodeframework.eventing.*
import org.enodeframework.infrastructure.TypeNameProvider
import org.enodeframework.messaging.ApplicationMessage
import org.enodeframework.messaging.MessageHandlerData
import org.enodeframework.messaging.MessagePublisher
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.function.Function

/**
 * @author anruence@gmail.com
 */
class DefaultProcessingCommandHandler(
    private val eventStore: EventStore,
    private val commandHandlerProvider: CommandHandlerProvider,
    private val typeNameProvider: TypeNameProvider,
    private val eventCommittingService: EventCommittingService,
    private val memoryCache: MemoryCache,
    private val applicationMessagePublisher: MessagePublisher<ApplicationMessage>,
    private val exceptionPublisher: MessagePublisher<DomainExceptionMessage>,
    private val serializeService: SerializeService,
    private val coroutineDispatcher: CoroutineDispatcher
) : ProcessingCommandHandler {
    override fun handleAsync(processingCommand: ProcessingCommand): CompletableFuture<Boolean> {
        val command = processingCommand.message
        if (Strings.isNullOrEmpty(command.getAggregateRootIdAsString())) {
            val errorMessage = String.format(
                "The aggregateRootId of command cannot be null or empty. commandType:%s, commandId:%s",
                command.javaClass.name,
                command.id
            )
            logger.error(errorMessage)
            return completeCommand(processingCommand, CommandStatus.Failed, String::class.java.name, errorMessage)
        }
        val findResult = getCommandHandler(processingCommand) { commandType: Class<*> ->
            commandHandlerProvider.getHandlers(commandType)
        }
        when (findResult.findStatus) {
            HandlerFindStatus.Found -> {
                return handleCommandInternal(processingCommand, findResult.findHandler as CommandHandlerProxy, 0)
            }
            HandlerFindStatus.TooManyHandlerData -> {
                logger.error(
                    "Found more than one command handler data, commandType:{}, commandId:{}",
                    command.javaClass.name,
                    command.id
                )
                return completeCommand(
                    processingCommand,
                    CommandStatus.Failed,
                    String::class.java.name,
                    "More than one command handler data found."
                )
            }
            HandlerFindStatus.TooManyHandler -> {
                logger.error(
                    "Found more than one command handler, commandType:{}, commandId:{}",
                    command.javaClass.name,
                    command.id
                )
                return completeCommand(
                    processingCommand,
                    CommandStatus.Failed,
                    String::class.java.name,
                    "More than one command handler found."
                )
            }
            HandlerFindStatus.NotFound -> {
                val errorMessage = String.format(
                    "No command handler found of command. commandType:%s, commandId:%s",
                    command.javaClass.name,
                    command.id
                )
                logger.error(errorMessage)
                return completeCommand(processingCommand, CommandStatus.Failed, String::class.java.name, errorMessage)
            }
            else -> return Task.completedTask
        }
    }

    private fun handleCommandInternal(
        processingCommand: ProcessingCommand, commandHandler: CommandHandlerProxy, retryTimes: Int
    ): CompletableFuture<Boolean> {
        val command = processingCommand.message
        val commandContext = processingCommand.commandExecuteContext
        if (processingCommand.isDuplicated) {
            return republishCommandEvents(processingCommand, 0)
        }
        val taskSource = CompletableFuture<Boolean>()
        IOHelper.tryAsyncActionRecursivelyWithoutResult("HandleCommandAsync", {
            commandContext.clear()
            CoroutineScope(coroutineDispatcher).async {
                commandHandler.handleAsync(commandContext, command)
            }.asCompletableFuture()
        }, {
            if (logger.isDebugEnabled) {
                logger.debug(
                    "Handle command success. handlerType:{}, commandType:{}, commandId:{}, aggregateRootId:{}",
                    commandHandler.getInnerObject().javaClass.name,
                    command.javaClass.name,
                    command.id,
                    command.aggregateRootId
                )
            }
            if (commandContext.applicationMessage != null) {
                commitChangesAsync(
                    processingCommand, true, commandContext.applicationMessage, ""
                ).whenComplete { _, _ -> taskSource.complete(true) }
            } else {
                try {
                    commitAggregateChanges(processingCommand).whenComplete { _, _ -> taskSource.complete(true) }
                        .exceptionally { ex: Throwable ->
                            logger.error(
                                "Commit aggregate changes has unknown exception, this should not be happen, and we just complete the command, handlerType:{}, commandType:{}, commandId:{}, aggregateRootId:{}",
                                commandHandler.getInnerObject().javaClass.name,
                                command.javaClass.name,
                                command.id,
                                command.aggregateRootId,
                                ex
                            )
                            completeCommand(
                                processingCommand,
                                CommandStatus.Failed,
                                ex.javaClass.name,
                                "Unknown exception caught when committing changes of command."
                            ).whenComplete { _, _ -> taskSource.complete(true) }
                            null
                        }
                } catch (aggregateRootReferenceChangedException: AggregateRootReferenceChangedException) {
                    logger.info(
                        "Aggregate root reference changed when processing command, try to re-handle the command. aggregateRootId: {}, aggregateRootType: {}, commandId: {}, commandType: {}, handlerType: {}",
                        aggregateRootReferenceChangedException.aggregateRoot.uniqueId,
                        aggregateRootReferenceChangedException.aggregateRoot.javaClass.name,
                        command.id,
                        command.javaClass.name,
                        commandHandler.getInnerObject().javaClass.name
                    )
                    handleCommandInternal(
                        processingCommand, commandHandler, 0
                    ).whenComplete { _, _ -> taskSource.complete(true) }
                } catch (e: Exception) {
                    logger.error(
                        "Commit aggregate changes has unknown exception, this should not be happen, and we just complete the command, handlerType:{}, commandType:{}, commandId:{}, aggregateRootId:{}",
                        commandHandler.getInnerObject().javaClass.name,
                        command.javaClass.name,
                        command.id,
                        command.aggregateRootId,
                        e
                    )
                    completeCommand(
                        processingCommand,
                        CommandStatus.Failed,
                        e.javaClass.name,
                        "Unknown exception caught when committing changes of command."
                    ).whenComplete { _, _ -> taskSource.complete(true) }
                }
            }
        }, {
            String.format(
                "[command:[id:%s,type:%s],handlerType:%s,aggregateRootId:%s]",
                command.id,
                command.javaClass.name,
                commandHandler.getInnerObject().javaClass.name,
                command.aggregateRootId
            )
        }, { ex: Throwable, errorMessage: String ->
            handleExceptionAsync(
                processingCommand, commandHandler, ex, errorMessage, 0
            ).whenComplete { _, _ -> taskSource.complete(true) }
        }, retryTimes, false)
        return taskSource
    }

    private fun commitAggregateChanges(processingCommand: ProcessingCommand): CompletableFuture<Boolean> {
        val command = processingCommand.message
        val context = processingCommand.commandExecuteContext
        val trackedAggregateRoots = context.trackedAggregateRoots
        var dirtyAggregateRootCount = 0
        var dirtyAggregateRoot: AggregateRoot? = null
        var changedEvents: List<DomainEventMessage<*>> = ArrayList()
        for (aggregateRoot in trackedAggregateRoots) {
            val events = aggregateRoot.changes
            if (events.size > 0) {
                dirtyAggregateRootCount++
                if (dirtyAggregateRootCount > 1) {
                    val errorMessage = String.format(
                        "Detected more than one aggregate created or modified by command. commandType:%s, commandId:%s",
                        command.javaClass.name,
                        command.id
                    )
                    logger.error(errorMessage)
                    return completeCommand(
                        processingCommand, CommandStatus.Failed, String::class.java.name, errorMessage
                    )
                }
                dirtyAggregateRoot = aggregateRoot
                changedEvents = events
            }
        }
        //如果当前command没有对任何聚合根做修改，框架仍然需要尝试获取该command之前是否有产生事件，
        //如果有，则需要将事件再次发布到MQ；如果没有，则完成命令，返回command的结果为NothingChanged。
        //之所以要这样做是因为有可能当前command上次执行的结果可能是事件持久化完成，但是发布到MQ未完成，然后那时正好机器断电宕机了；
        //这种情况下，如果机器重启，当前command对应的聚合根从EventStore恢复的聚合根是被当前command处理过后的；
        //所以如果该command再次被处理，可能对应的聚合根就不会再产生事件了；
        //所以，我们要考虑到这种情况，尝试再次发布该命令产生的事件到MQ；
        //否则，如果我们直接将当前command设置为完成，即对MQ进行ack操作，那该command的事件就永远不会再发布到MQ了，这样就无法保证CQRS数据的最终一致性了。
        if (dirtyAggregateRootCount == 0 || changedEvents.isEmpty()) {
            return republishCommandEvents(processingCommand, 0)
        }
        dirtyAggregateRoot!!
        val eventStream = DomainEventStream(
            processingCommand.message.id,
            dirtyAggregateRoot.uniqueId,
            typeNameProvider.getTypeName(dirtyAggregateRoot.javaClass),
            Date(),
            changedEvents,
            command.items
        )
        //内存先接受聚合根的更新，需要检查聚合根引用是否已变化，如果已变化，会抛出异常
        memoryCache.acceptAggregateRootChanges(dirtyAggregateRoot)
        val commandResult = processingCommand.commandExecuteContext.result
        processingCommand.items[SysProperties.ITEMS_COMMAND_RESULT_KEY] = commandResult
        //提交事件流进行后续的处理
        eventCommittingService.commitDomainEventAsync(EventCommittingContext(eventStream, processingCommand))
        return Task.completedTask
    }

    private fun republishCommandEvents(
        processingCommand: ProcessingCommand, retryTimes: Int
    ): CompletableFuture<Boolean> {
        val future = CompletableFuture<Boolean>()
        val command = processingCommand.message
        IOHelper.tryAsyncActionRecursively("ProcessIfNoEventsOfCommand", {
            eventStore.findAsync(command.getAggregateRootIdAsString(), command.id)
        }, { result: DomainEventStream? ->
            if (result != null) {
                eventCommittingService.publishDomainEventAsync(processingCommand, result)
                    .whenComplete { _, _ -> future.complete(true) }
            } else {
                completeCommand(
                    processingCommand,
                    CommandStatus.NothingChanged,
                    String::class.java.name,
                    processingCommand.commandExecuteContext.result
                ).whenComplete { _, _ -> future.complete(true) }
            }
        }, {
            String.format("[commandId:%s]", command.id)
        }, null, retryTimes, true)
        return future
    }

    private fun handleExceptionAsync(
        processingCommand: ProcessingCommand,
        commandHandler: CommandHandlerProxy,
        exception: Throwable,
        errorMessage: String,
        retryTimes: Int
    ): CompletableFuture<Boolean> {
        val command = processingCommand.message
        val future = CompletableFuture<Boolean>()
        IOHelper.tryAsyncActionRecursively("FindEventByCommandIdAsync", {
            eventStore.findAsync(command.getAggregateRootIdAsString(), command.id)
        }, { result: DomainEventStream? ->
            if (result != null) {
                //这里，我们需要再重新做一遍发布事件这个操作；
                //之所以要这样做是因为虽然该command产生的事件已经持久化成功，但并不表示事件已经发布出去了；
                //因为有可能事件持久化成功了，但那时正好机器断电了，则发布事件就没有做；
                eventCommittingService.publishDomainEventAsync(processingCommand, result)
                    .whenComplete { _, _ -> future.complete(true) }
            } else {
                //到这里，说明当前command执行遇到异常，然后当前command之前也没执行过，是第一次被执行。
                //那就判断当前异常是否是需要被发布出去的异常，如果是，则发布该异常给所有消费者；
                //否则，就记录错误日志，然后认为该command处理失败即可；
                val realException = getRealException(exception)
                if (realException is DomainExceptionMessage) {
                    publishExceptionAsync(
                        processingCommand, realException as DomainExceptionMessage, 0
                    ).whenComplete { _, _ -> future.complete(true) }
                } else {
                    completeCommand(
                        processingCommand, CommandStatus.Failed, realException.javaClass.name, realException.message
                    ).whenComplete { _, _ -> future.complete(true) }
                }
            }
        }, {
            String.format(
                "[command:[id:%s,type:%s],handlerType:%s,aggregateRootId:%s]",
                command.id,
                command.javaClass.name,
                commandHandler.getInnerObject().javaClass.name,
                command.aggregateRootId
            )
        }, null, retryTimes, true)
        return future
    }

    private fun getRealException(exception: Throwable): Throwable {
        if (exception is CompletionException) {
            if (exception.cause is DomainExceptionMessage) {
                return exception.cause!!
            }
            return Arrays.stream(exception.suppressed).filter { x: Throwable? -> x is DomainExceptionMessage }
                .findFirst()
                .orElse(exception)
        }
        return exception
    }

    private fun publishExceptionAsync(
        processingCommand: ProcessingCommand, exception: DomainExceptionMessage, retryTimes: Int
    ): CompletableFuture<Boolean> {
        exception.mergeItems(processingCommand.message.items)
        val future = CompletableFuture<Boolean>()
        IOHelper.tryAsyncActionRecursivelyWithoutResult("PublishExceptionAsync", {
            exceptionPublisher.publishAsync(exception)
        }, {
            completeCommand(
                processingCommand, CommandStatus.Failed, exception.javaClass.name, (exception as Exception).message
            ).whenComplete { _, _ -> future.complete(true) }
        }, {
            "[commandId: ${processingCommand.message.id}]"
        }, null, retryTimes, true)
        return future
    }

    private fun commitChangesAsync(
        processingCommand: ProcessingCommand, success: Boolean, message: ApplicationMessage?, errorMessage: String
    ): CompletableFuture<Boolean> {
        if (success) {
            if (message != null) {
                message.mergeItems(processingCommand.message.items)
                return publishMessageAsync(processingCommand, message, 0)
            }
            return completeCommand(processingCommand, CommandStatus.Success, "", "")
        }
        return completeCommand(processingCommand, CommandStatus.Failed, String::class.java.name, errorMessage)
    }

    private fun publishMessageAsync(
        processingCommand: ProcessingCommand, message: ApplicationMessage, retryTimes: Int
    ): CompletableFuture<Boolean> {
        val command = processingCommand.message
        val future = CompletableFuture<Boolean>()
        IOHelper.tryAsyncActionRecursivelyWithoutResult("PublishApplicationMessageAsync", {
            applicationMessagePublisher.publishAsync(message)
        }, {
            completeCommand(
                processingCommand, CommandStatus.Success, message.javaClass.name, serializeService.serialize(message)
            ).whenComplete { _, _ -> future.complete(true) }
        }, {
            String.format(
                "[application message:[id:%s,type:%s],command:[id:%s,type:%s]]",
                message.id,
                message.javaClass.name,
                command.id,
                command.javaClass.name
            )
        }, null, retryTimes, true)
        return future
    }

    private fun getCommandHandler(
        processingCommand: ProcessingCommand,
        getHandlersFunc: Function<Class<*>, List<MessageHandlerData<CommandHandlerProxy>>>
    ): HandlerFindResult {
        val command = processingCommand.message
        val handlerDataList = getHandlersFunc.apply(command.javaClass)
        if (handlerDataList.isEmpty()) {
            return HandlerFindResult.NotFound
        } else if (handlerDataList.size > 1) {
            return HandlerFindResult.TooManyHandlerData
        }
        val handlerData = handlerDataList.firstOrNull() ?: (MessageHandlerData())
        if (handlerData.listHandlers.isEmpty()) {
            return HandlerFindResult.NotFound
        }
        if (handlerData.listHandlers.size > 1) {
            return HandlerFindResult.TooManyHandler
        }
        return HandlerFindResult(HandlerFindStatus.Found, handlerData.listHandlers.first())
    }

    private fun completeCommand(
        processingCommand: ProcessingCommand, commandStatus: CommandStatus, resultType: String, result: String?
    ): CompletableFuture<Boolean> {
        val commandResult = CommandResult(
            commandStatus,
            processingCommand.message.id,
            processingCommand.message.getAggregateRootIdAsString(),
            result,
            resultType
        )
        return processingCommand.mailBox.completeMessage(processingCommand, commandResult)
    }

    companion object {
        private val logger = LoggerFactory.getLogger(DefaultProcessingCommandHandler::class.java)
    }
}