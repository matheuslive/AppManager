// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.runner;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;

import com.topjohnwu.superuser.Shell;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

class NormalShell extends Runner {
    /**
     * libsu jobs run here, never on the caller's thread. If a thread waiting in {@code Job#exec()}
     * is interrupted (e.g. a cancelled loader), libsu returns early and leaves the job's output
     * gobbler on the shell's stdout. The next job's {@code cleanInputStream()} then throws away
     * that gobbler's end marker, so the gobbler blocks forever while holding the stdout lock: every
     * later root command (and anything waiting on the {@link Runner} lock, restores included)
     * hangs until the app is killed. This thread is never interrupted.
     */
    private static final ExecutorService sExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "NormalShell");
        t.setDaemon(true);
        return t;
    });

    private final Shell mShell;

    public NormalShell(boolean isRoot) {
        if (isRoot == Shell.getShell().isRoot()) {
            mShell = Shell.getShell();
            return;
        }
        int flags = isRoot ? Shell.FLAG_MOUNT_MASTER : Shell.FLAG_NON_ROOT_SHELL;
        mShell = Shell.Builder.create().setFlags(flags).setTimeout(10).build();
    }

    @Override
    public boolean isRoot() {
        return mShell.isRoot();
    }

    @WorkerThread
    @NonNull
    @Override
    protected synchronized Result runCommand() {
        List<String> stdout = new ArrayList<>();
        List<String> stderr = new ArrayList<>();
        Shell.Job job = mShell.newJob().to(stdout, stderr);
        if (inputStreams.isEmpty()) {
            // The shell reads its commands (and libsu's end-of-job marker) from stdin, which every
            // command inherits. A command that reads stdin swallows the marker and the job never
            // ends, e.g. "cmd package dump" (system_server reads the forwarded fd). Give the
            // commands /dev/null instead.
            for (String command : commands) {
                job.add("{\n" + command + "\n} </dev/null");
            }
        } else {
            // The input streams are meant to be read by the commands
            job.add(commands.toArray(new String[0]));
            for (InputStream is : inputStreams) {
                job.add(is);
            }
        }
        Future<Shell.Result> future = sExecutor.submit(job::exec);
        Shell.Result result;
        boolean interrupted = false;
        while (true) {
            try {
                result = future.get();
                break;
            } catch (InterruptedException e) {
                // Keep waiting: abandoning the job would break the shell (see sExecutor)
                interrupted = true;
            } catch (ExecutionException e) {
                throw new RuntimeException(e.getCause());
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        return new Result(stdout, stderr, result.getCode());
    }
}
