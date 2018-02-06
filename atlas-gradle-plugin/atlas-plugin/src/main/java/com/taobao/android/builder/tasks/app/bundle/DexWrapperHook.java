package com.taobao.android.builder.tasks.app.bundle;

import com.android.SdkConstants;
import com.android.annotations.NonNull;
import com.android.builder.core.DexOptions;
import com.android.builder.core.DexProcessBuilder;
import com.android.builder.internal.compiler.DexWrapper;
import com.android.dx.command.dexer.DxContext;
import com.android.dx.command.dexer.Main;
import com.android.dx.dex.code.PositionList;
import com.android.ide.common.process.ProcessException;
import com.android.ide.common.process.ProcessOutput;
import com.android.ide.common.process.ProcessOutputHandler;
import com.android.ide.common.process.ProcessResult;
import com.google.common.base.MoreObjects;
import com.google.common.collect.Iterables;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;

public class DexWrapperHook {

    public static ProcessResult run(
            @NonNull DexProcessBuilder processBuilder,
            @NonNull DexOptions dexOptions,
            @NonNull ProcessOutputHandler outputHandler) throws IOException, ProcessException {
        ProcessOutput output = null;
        try (Closeable ignored = output = outputHandler.createOutput()) {
            DxContext dxContext = new DxContext(output.getStandardOutput(), output.getErrorOutput());
            Main.Arguments args = buildArguments(processBuilder, dexOptions, dxContext);

            return new DexWrapperHook.DexProcessResult(new Main(dxContext).run(args));
        } finally {
            if (output != null) {
                outputHandler.handleOutput(output);
            }
        }
    }

    @NonNull
    private static Main.Arguments buildArguments(
            @NonNull DexProcessBuilder processBuilder,
            @NonNull DexOptions dexOptions,
            @NonNull DxContext dxContext)
            throws ProcessException {
        Main.Arguments args = new Main.Arguments();

        // Inputs:
        args.fileNames = Iterables.toArray(processBuilder.getFilesToAdd(), String.class);

        // Outputs:
        if (processBuilder.getOutputFile().isDirectory() && !processBuilder.isMultiDex()) {
            args.outName = new File(processBuilder.getOutputFile(), "classes.dex").getPath();
            args.jarOutput = false;
        } else {
            String outputFileAbsolutePath = processBuilder.getOutputFile().getAbsolutePath();
            args.outName = outputFileAbsolutePath;
            args.jarOutput = outputFileAbsolutePath.endsWith(SdkConstants.DOT_JAR);
        }

        // Multi-dex:
        args.multiDex = processBuilder.isMultiDex();
        if (processBuilder.getMainDexList() != null) {
            args.mainDexListFile = processBuilder.getMainDexList().getPath();
        }

        // Other:
        args.verbose = processBuilder.isVerbose();
        // due to b.android.com/82031
        args.optimize = true;
        args.localInfo = false;
        args.positionInfo = PositionList.NONE;
        args.numThreads = MoreObjects.firstNonNull(dexOptions.getThreadCount(), 4);
        args.forceJumbo = dexOptions.getJumboMode();

        args.parseFlags(Iterables.toArray(dexOptions.getAdditionalParameters(), String.class));
        args.makeOptionsObjects(dxContext);

        return args;
    }

    private static class DexProcessResult implements ProcessResult {

        private int mExitValue;

        DexProcessResult(int exitValue) {
            mExitValue = exitValue;
        }

        @NonNull
        @Override
        public ProcessResult assertNormalExitValue()
                throws ProcessException {
            if (mExitValue != 0) {
                throw new ProcessException(
                        String.format("Return code %d for dex process", mExitValue));
            }

            return this;
        }

        @Override
        public int getExitValue() {
            return mExitValue;
        }

        @NonNull
        @Override
        public ProcessResult rethrowFailure()
                throws ProcessException {
            return assertNormalExitValue();
        }
    }

}
