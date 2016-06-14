package bolts;

import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;

public interface BoltsExecutors {
    ScheduledExecutorService scheduled();
    Executor immediate();
    Executor background();
    Executor uiTread();
}
