package com.cloud.usage;

import com.cloud.utils.component.Manager;

public interface UsageManager extends Manager {
    public void scheduleParse();

    public void parse(UsageJobVO job, long startDateMillis, long endDateMillis);
}
