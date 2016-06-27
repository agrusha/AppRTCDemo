package org.appspot.apprtc.util;

import android.os.Handler;
import android.os.Looper;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListenableFutureTask;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.Validate;

import java.util.concurrent.*;

public class GuiThreadExecutor implements Executor {
    @Getter private static final GuiThreadExecutor instance = new GuiThreadExecutor();

    @Getter private final Handler handler;
    @Getter private final Thread thread;
    

    public static boolean isUiThread(Thread thread) {
        return thread == Looper.getMainLooper().getThread();
    }

    public static boolean isOnUiThread() {
        return isUiThread(Thread.currentThread());
    }

    private GuiThreadExecutor() {
        this(Looper.getMainLooper());
    }

    public GuiThreadExecutor(Looper looper) {
        this(new Handler(looper));
    }

    public GuiThreadExecutor(Handler handler) {
        this.handler = Validate.notNull(handler);
        this.thread = handler.getLooper().getThread();
    }

    @Override
    public void execute(@NonNull Runnable command) {
        Runnable task = LoggedTask.of(makeStrictTask(command));
        if (Thread.currentThread() == this.thread) {
            task.run();
        } else {
            handler.post(task);
        }
    }

    public void executeSingle(Runnable runnable) {
        handler.removeCallbacks(runnable);
        execute(runnable);
    }

    public void executeLater(Runnable command) {
        handler.post(makeStrictTask(command));
    }

    public void executeLaterOneSecond(Runnable command) {
        executeLater(command, 1, TimeUnit.SECONDS);
    }

    public void executeLater(Runnable command, long time, TimeUnit timeUnit) {
        handler.postDelayed(makeStrictTask(command), timeUnit.toMillis(time));
    }

    public void removeCallbacks(Runnable command) {
        handler.removeCallbacks(command);
    }

    public <T> ListenableFuture<T> submit(@NonNull Callable<T> task) {
        ListenableFutureTask<T> futureTask = ListenableFutureTask.create(task);
        execute(futureTask);
        return futureTask;
    }

    public <T> ListenableFuture<T> submitLater(@NonNull Callable<T> task, long time, TimeUnit timeUnit) {
        ListenableFutureTask<T> futureTask = ListenableFutureTask.create(task);
        executeLater(futureTask, time, timeUnit);
        return futureTask;
    }

    public <T> ListenableFuture<T> submit(@NonNull Runnable task, T result) {
        ListenableFutureTask<T> futureTask = ListenableFutureTask.create(task, result);
        execute(futureTask);
        return futureTask;
    }

    public ScheduledFuture<?> schedule(@NonNull Runnable task, long time, TimeUnit timeUnit) {
        return schedule(task, null, time, timeUnit);
    }

    public <T> ScheduledFuture<T> schedule(@NonNull Runnable task, T result, long time, TimeUnit timeUnit) {
        return scheduleTask(ListenableFutureTask.create(task, result), time, timeUnit);
    }

    public <T> ScheduledFuture<T> schedule(@NonNull final Callable<T> task, long time, TimeUnit timeUnit) {
        return scheduleTask(ListenableFutureTask.create(task), time, timeUnit);
    }

    private <T> ScheduledFuture<T> scheduleTask(ListenableFutureTask<T> futureTask, long time, TimeUnit timeUnit) {
        executeLater(futureTask, time, timeUnit);
        return createScheduledFuture(futureTask, time, timeUnit);
    }

    private <T> GuiScheduledFuture<T> createScheduledFuture(ListenableFutureTask<T> task,
                                                                   long time, TimeUnit timeUnit) {
        return new GuiScheduledFuture<T>(task, time, timeUnit);
    }

    private Runnable makeStrictTask(Runnable task) {
        return StrictTask.of(task);
    }

    @RequiredArgsConstructor(suppressConstructorProperties = true)
    private class GuiScheduledFuture<T> implements ScheduledFuture<T> {
        private final ListenableFutureTask<T> task;
        private final long time;
        private final TimeUnit timeUnit;

        @Override
        public long getDelay(TimeUnit unit) {
            return unit.convert(time, timeUnit);
        }

        @Override
        public int compareTo(Delayed another) {
            if (this == another) {
                return 0;
            } else {
                TimeUnit compareUnit = TimeUnit.MILLISECONDS;
                return Long.compare(getDelay(compareUnit), another.getDelay(compareUnit));
            }
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            if (!task.isCancelled()) {
                handler.removeCallbacks(task);
                task.cancel(mayInterruptIfRunning);
            }
            return isCancelled();
        }

        @Override
        public boolean isCancelled() {
            return task.isCancelled();
        }

        @Override
        public boolean isDone() {
            return task.isDone();
        }

        @Override
        public T get() throws InterruptedException, ExecutionException {
            return task.get();
        }

        @Override
        public T get(long timeout, TimeUnit unit)
                throws InterruptedException, ExecutionException, TimeoutException {
            return task.get(timeout, unit);
        }
    }

    @RequiredArgsConstructor(suppressConstructorProperties = true, staticName = "of")
    private static class StrictTask implements Runnable {
        private final Runnable task;

        @Override
        public void run() {
            task.run();
//            ResponsivenessPolicy.measuredCall(task, taskPost);
        }
    }

    @RequiredArgsConstructor(suppressConstructorProperties = true, staticName = "of")
    private static class LoggedTask implements Runnable {
        private final Runnable task;

        @Override
        public void run() {
            try {
                task.run();
            } catch (Exception any) {
//                log.error("Failure: {}", Utils.prettyStackTrace(any));
                throw any;
            }
        }
    }
}
