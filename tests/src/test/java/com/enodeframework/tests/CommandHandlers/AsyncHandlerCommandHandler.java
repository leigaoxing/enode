package com.enodeframework.tests.CommandHandlers;

import com.enodeframework.annotation.Command;
import com.enodeframework.annotation.Subscribe;
import com.enodeframework.common.io.AsyncTaskResult;
import com.enodeframework.common.io.AsyncTaskStatus;
import com.enodeframework.common.io.IORuntimeException;
import com.enodeframework.infrastructure.IApplicationMessage;
import com.enodeframework.tests.Commands.AsyncHandlerCommand;

@Command
public class AsyncHandlerCommandHandler {
    public boolean CheckCommandHandledFirst;
    private int _count;

    @Subscribe
    public AsyncTaskResult<IApplicationMessage> HandleAsync(AsyncHandlerCommand command) throws Exception {
        if (command.ShouldGenerateApplicationMessage) {
            return new AsyncTaskResult<IApplicationMessage>(AsyncTaskStatus.Success, new TestApplicationMessage(command.getAggregateRootId()));
        } else if (command.ShouldThrowException) {
            throw new Exception("AsyncCommandException");
        } else if (command.ShouldThrowIOException) {
            _count++;
            if (_count <= 5) {
                throw new IORuntimeException("AsyncCommandIOException" + _count);
            }
            _count = 0;
            return new AsyncTaskResult<IApplicationMessage>(AsyncTaskStatus.Success);
        } else {
            return new AsyncTaskResult<IApplicationMessage>(AsyncTaskStatus.Success);
        }
    }
}