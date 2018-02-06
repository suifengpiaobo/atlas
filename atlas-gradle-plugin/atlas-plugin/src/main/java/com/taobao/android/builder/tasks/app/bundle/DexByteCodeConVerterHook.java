package com.taobao.android.builder.tasks.app.bundle;

import com.android.annotations.NonNull;
import com.android.annotations.VisibleForTesting;
import com.android.annotations.concurrency.GuardedBy;
import com.android.builder.core.DefaultDexOptions;
import com.android.builder.core.DexByteCodeConverter;
import com.android.builder.core.DexOptions;
import com.android.builder.core.DexProcessBuilder;
import com.android.builder.internal.compiler.DexWrapper;
import com.android.builder.sdk.TargetInfo;
import com.android.ide.common.process.*;
import com.android.utils.ILogger;
import com.android.utils.SdkUtils;
import com.google.common.base.Joiner;
import com.google.common.base.Stopwatch;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class DexByteCodeConVerterHook extends DexByteCodeConverter {

    /**
     * Amount of heap size that an "average" project needs for dexing in-process.
     */
    private static final long DEFAULT_DEX_HEAP_SIZE = 1024 * 1024 * 1024; // 1 GiB

    /**
     * Approximate amount of heap space necessary for non-dexing steps of the build process.
     */
    @VisibleForTesting
    static final long NON_DEX_HEAP_SIZE = 512 * 1024 * 1024; // 0.5 GiB

    private static final Object LOCK_FOR_DEX = new Object();

    /**
     * Default number of dx "instances" running at once.
     *
     * <p>Remember to update the DSL javadoc when changing the default value.
     */
    private static final AtomicInteger DEX_PROCESS_COUNT = new AtomicInteger(4);

    /**
     * {@link ExecutorService} used to run all dexing code (either in-process or out-of-process).
     * Size of the underlying thread pool limits the number of parallel dex "invocations", even
     * though every invocation can spawn many threads, depending on dexing options.
     */
    @GuardedBy("LOCK_FOR_DEX")
    private static ExecutorService sDexExecutorService = null;

    private final boolean mVerboseExec;
    private final JavaProcessExecutor mJavaProcessExecutor;
    private final TargetInfo mTargetInfo;
    private final ILogger mLogger;
    private Boolean mIsDexInProcess = true;

    public DexByteCodeConVerterHook(ILogger logger, TargetInfo targetInfo, JavaProcessExecutor javaProcessExecutor, boolean verboseExec) {
        super(logger, targetInfo, javaProcessExecutor, verboseExec);
        this.mLogger = logger;
        this.mTargetInfo = targetInfo;
        this.mJavaProcessExecutor = javaProcessExecutor;
        this.mVerboseExec = verboseExec;
    }


    @Override
    public void runDexer(DexProcessBuilder builder, DexOptions dexOptions, ProcessOutputHandler processOutputHandler) throws ProcessException, IOException, InterruptedException {

        initDexExecutorService(dexOptions);

        if (dexOptions.getAdditionalParameters().contains("--no-optimize")) {
            mLogger.warning(DefaultDexOptions.OPTIMIZE_WARNING);
        }

        if (shouldDexInProcess(dexOptions)) {
            dexInProcess(builder, dexOptions, processOutputHandler);
        } else {
            dexOutOfProcess(builder, dexOptions, processOutputHandler);
        }
    }


    private void initDexExecutorService(@NonNull DexOptions dexOptions) {
        synchronized (LOCK_FOR_DEX) {
            if (sDexExecutorService == null) {
                if (dexOptions.getMaxProcessCount() != null) {
                    DEX_PROCESS_COUNT.set(dexOptions.getMaxProcessCount());
                }
                mLogger.verbose(
                        "Allocated dexExecutorService of size %1$d.",
                        DEX_PROCESS_COUNT.get());
                sDexExecutorService = Executors.newFixedThreadPool(DEX_PROCESS_COUNT.get());
            } else {
                // check whether our executor service has the same number of max processes as
                // this module requests, and print a warning if necessary.
                if (dexOptions.getMaxProcessCount() != null
                        && dexOptions.getMaxProcessCount() != DEX_PROCESS_COUNT.get()) {
                    mLogger.warning(
                            "dexOptions is specifying a maximum number of %1$d concurrent dx processes,"
                                    + " but the Gradle daemon was initialized with %2$d.\n"
                                    + "To initialize with a different maximum value,"
                                    + " first stop the Gradle daemon by calling ‘gradlew —-stop’.",
                            dexOptions.getMaxProcessCount(),
                            DEX_PROCESS_COUNT.get());
                }
            }
        }
    }

    private void dexInProcess(
            @NonNull final DexProcessBuilder builder,
            @NonNull final DexOptions dexOptions,
            @NonNull final ProcessOutputHandler outputHandler)
            throws IOException, ProcessException {
        final String submission = Joiner.on(',').join(builder.getInputs());
        mLogger.verbose("Dexing in-process : %1$s", submission);
        try {
            sDexExecutorService.submit(() -> {
                Stopwatch stopwatch = Stopwatch.createStarted();
                ProcessResult result = DexWrapperHook.run(builder, dexOptions, outputHandler);
                result.assertNormalExitValue();
                mLogger.verbose("Dexing %1$s took %2$s.", submission, stopwatch.toString());
                return null;
            }).get();
        } catch (Exception e) {
            throw new ProcessException(e);
        }
    }


    synchronized boolean shouldDexInProcess(
            @NonNull DexOptions dexOptions) {
        if (mIsDexInProcess != null) {
            return mIsDexInProcess;
        }
        if (!dexOptions.getDexInProcess()) {
            mIsDexInProcess = false;
            return false;
        }

        // Requested memory for dex.
        long requestedHeapSize;
        if (dexOptions.getJavaMaxHeapSize() != null) {
            Optional<Long> heapSize = parseSizeToBytes(dexOptions.getJavaMaxHeapSize());
            if (heapSize.isPresent()) {
                requestedHeapSize = heapSize.get();
            } else {
                mLogger.warning(
                        "Unable to parse dex options size parameter '%1$s', assuming %2$s bytes.",
                        dexOptions.getJavaMaxHeapSize(),
                        DEFAULT_DEX_HEAP_SIZE);
                requestedHeapSize = DEFAULT_DEX_HEAP_SIZE;
            }
        } else {
            requestedHeapSize = DEFAULT_DEX_HEAP_SIZE;
        }
        // Approximate heap size requested.
        long requiredHeapSizeHeuristic = requestedHeapSize + NON_DEX_HEAP_SIZE;
        // Get the heap size defined by the user. This value will be compared with
        // requiredHeapSizeHeuristic, which we suggest the user set in their gradle.properties file.
        long maxMemory = getUserDefinedHeapSize();

        if (requiredHeapSizeHeuristic > maxMemory) {
            String dexOptionsComment = "";
            if (dexOptions.getJavaMaxHeapSize() != null) {
                dexOptionsComment = String.format(
                        " (based on the dexOptions.javaMaxHeapSize = %s)",
                        dexOptions.getJavaMaxHeapSize());
            }

            mLogger.warning("\nRunning dex as a separate process.\n\n"
                            + "To run dex in process, the Gradle daemon needs a larger heap.\n"
                            + "It currently has %1$d MB.\n"
                            + "For faster builds, increase the maximum heap size for the "
                            + "Gradle daemon to at least %2$s MB%3$s.\n"
                            + "To do this set org.gradle.jvmargs=-Xmx%2$sM in the "
                            + "project gradle.properties.\n"
                            + "For more information see "
                            + "https://docs.gradle.org/current/userguide/build_environment.html\n",
                    maxMemory / (1024 * 1024),
                    // Add -1 and + 1 to round up the division
                    ((requiredHeapSizeHeuristic - 1) / (1024 * 1024)) + 1,
                    dexOptionsComment);
            mIsDexInProcess = false;
            return false;
        }
        mIsDexInProcess = true;
        return true;

    }

    @VisibleForTesting
    @NonNull
    static Optional<Long> parseSizeToBytes(@NonNull String sizeParameter) {
        long multiplier = 1;
        if (SdkUtils.endsWithIgnoreCase(sizeParameter, "k")) {
            multiplier = 1024;
        } else if (SdkUtils.endsWithIgnoreCase(sizeParameter, "m")) {
            multiplier = 1024 * 1024;
        } else if (SdkUtils.endsWithIgnoreCase(sizeParameter, "g")) {
            multiplier = 1024 * 1024 * 1024;
        }

        if (multiplier != 1) {
            sizeParameter = sizeParameter.substring(0, sizeParameter.length() - 1);
        }

        try {
            return Optional.of(multiplier * Long.parseLong(sizeParameter));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }


    private void dexOutOfProcess(
            @NonNull final DexProcessBuilder builder,
            @NonNull final DexOptions dexOptions,
            @NonNull final ProcessOutputHandler processOutputHandler)
            throws ProcessException, InterruptedException {
        final String submission = Joiner.on(',').join(builder.getInputs());
        mLogger.verbose("Dexing out-of-process : %1$s", submission);
        try {
            Callable<Void> task = () -> {
                JavaProcessInfo javaProcessInfo =
                        builder.build(mTargetInfo.getBuildTools(), dexOptions);
                ProcessResult result =
                        mJavaProcessExecutor.execute(javaProcessInfo, processOutputHandler);
                result.rethrowFailure().assertNormalExitValue();
                return null;
            };

            Stopwatch stopwatch = Stopwatch.createStarted();
            // this is a hack, we always spawn a new process for dependencies.jar so it does
            // get built in parallel with the slices, this is only valid for InstantRun mode.
            if (submission.contains("dependencies.jar")) {
                task.call();
            } else {
                sDexExecutorService.submit(task).get();
            }
            mLogger.verbose("Dexing %1$s took %2$s.", submission, stopwatch.toString());
        } catch (Exception e) {
            throw new ProcessException(e);
        }
    }


}
