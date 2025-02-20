package org.enodeframework.common.scheduling;

import org.enodeframework.common.function.Action;

public interface ScheduleService {
    void startTask(String name, Action action, int dueTime, int period);

    void stopTask(String name);
}
