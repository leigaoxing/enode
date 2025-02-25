package org.enodeframework.test.commandhandler;

import org.enodeframework.annotation.Command;
import org.enodeframework.annotation.Subscribe;
import org.enodeframework.commanding.CommandContext;
import org.enodeframework.common.container.DefaultObjectContainer;
import org.enodeframework.common.io.Task;
import org.enodeframework.domain.MemoryCache;
import org.enodeframework.test.command.AggregateThrowExceptionCommand;
import org.enodeframework.test.command.BaseCommand;
import org.enodeframework.test.command.ChangeInheritTestAggregateTitleCommand;
import org.enodeframework.test.command.ChangeMultipleAggregatesCommand;
import org.enodeframework.test.command.ChangeNothingCommand;
import org.enodeframework.test.command.ChangeTestAggregateTitleCommand;
import org.enodeframework.test.command.ChangeTestAggregateTitleWhenDirtyCommand;
import org.enodeframework.test.command.ChildCommand;
import org.enodeframework.test.command.CreateInheritTestAggregateCommand;
import org.enodeframework.test.command.CreateTestAggregateCommand;
import org.enodeframework.test.command.NotCheckAsyncHandlerExistCommand;
import org.enodeframework.test.command.NotCheckAsyncHandlerExistWithResultCommand;
import org.enodeframework.test.command.SetResultCommand;
import org.enodeframework.test.command.TestEventPriorityCommand;
import org.enodeframework.test.command.ThrowExceptionCommand;
import org.enodeframework.test.command.TwoHandlersCommand;
import org.enodeframework.test.domain.InheritTestAggregate;
import org.enodeframework.test.domain.TestAggregate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

@Command
public class TestCommandHandler {

    private static final Logger logger = LoggerFactory.getLogger(TestCommandHandler.class);

    @Subscribe
    public CompletableFuture<TestAggregate> handleAsync(CommandContext context, ChangeTestAggregateTitleWhenDirtyCommand command) {
        CompletableFuture<TestAggregate> future = context.getAsync(command.getAggregateRootId(), TestAggregate.class);
        if (command.isFirstExecute()) {
            DefaultObjectContainer.resolve(MemoryCache.class).refreshAggregateFromEventStoreAsync(TestAggregate.class.getName(), command.getAggregateRootId());
        }
        future.thenAccept(testAggregate -> {
            testAggregate.changeTitle(command.getTitle());
            command.setFirstExecute(false);
        });
        return future;
    }

    @Subscribe
    public void handleAsync(CommandContext context, CreateTestAggregateCommand command) {
        if (command.sleepMilliseconds > 0) {
            Task.sleep(command.sleepMilliseconds);
        }
        context.addAsync(new TestAggregate(command.getAggregateRootId(), command.title));
    }

    @Subscribe
    public CompletableFuture<Void> handleAsync(CommandContext context, ChangeTestAggregateTitleCommand command) {
        return context.getAsync(command.aggregateRootId, TestAggregate.class).thenAccept(testAggregate -> {
            testAggregate.changeTitle(command.title);
        });
    }

    @Subscribe
    public void handleAsync(CommandContext context, CreateInheritTestAggregateCommand command) {
        context.addAsync(new InheritTestAggregate(command.getAggregateRootId(), command.Title));
    }

    @Subscribe
    public CompletableFuture<Void> handleAsync(CommandContext context, ChangeInheritTestAggregateTitleCommand command) {
        return context.getAsync(command.getAggregateRootId(), InheritTestAggregate.class).thenAccept(inheritTestAggregate -> {
            inheritTestAggregate.changeMyTitle(command.Title);
        });
    }

    @Subscribe
    public void handleAsync(CommandContext context, ChangeNothingCommand command) {
        logger.info("nothing changed exec, {}", command);
    }

    @Subscribe
    public void handleAsync(CommandContext context, SetResultCommand command) {
        context.addAsync(new TestAggregate(command.getAggregateRootId(), ""));
        context.setResult(command.Result);
    }

    @Subscribe
    public CompletableFuture<Boolean> handleAsync(CommandContext context, ChangeMultipleAggregatesCommand command) {
        context.getAsync(command.getAggregateRootId1(), TestAggregate.class).thenAccept(testAggregate1 -> {
            testAggregate1.testEvents();
        });
        context.getAsync(command.getAggregateRootId2(), TestAggregate.class).thenAccept(testAggregate2 -> {
            testAggregate2.testEvents();
        });
        return Task.completedTask;
    }

    @Subscribe
    public void handleAsync(CommandContext context, ThrowExceptionCommand command) throws Exception {
        throw new Exception("CommandException");
    }

    @Subscribe
    public CompletableFuture<TestAggregate> handleAsync(CommandContext context, AggregateThrowExceptionCommand command) throws Exception {
        return context.getAsync(command.getAggregateRootId(), TestAggregate.class).thenApply(testAggregate -> {
            testAggregate.throwException(command.publishableException);
            return testAggregate;
        });
    }

    @Subscribe
    public CompletableFuture<Void> handleAsync(CommandContext context, TestEventPriorityCommand command) {
        return context.getAsync(command.getAggregateRootId(), TestAggregate.class).thenAccept(testAggregate -> {
            testAggregate.testEvents();
        });
    }

    @Subscribe
    public void handleAsync1(CommandContext context, TwoHandlersCommand command) {
    }

    @Subscribe
    public void handleAsync(CommandContext context, BaseCommand command) {
        context.setResult("ResultFromBaseCommand");
    }

    @Subscribe
    public void handleAsync(CommandContext context, ChildCommand command) {
        context.setResult("ResultFromChildCommand");
    }

    @Subscribe
    public void handleAsync(CommandContext context, NotCheckAsyncHandlerExistCommand command) {

    }

    @Subscribe
    public void handleAsync(CommandContext context, NotCheckAsyncHandlerExistWithResultCommand command) {
        context.setApplicationMessage(new TestApplicationMessage(command.getAggregateRootId()));
    }
}