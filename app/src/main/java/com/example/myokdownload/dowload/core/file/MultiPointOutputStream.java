package com.example.myokdownload.dowload.core.file;

import android.net.Uri;
import android.os.StatFs;
import android.os.SystemClock;
import android.util.SparseArray;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.myokdownload.dowload.DownloadTask;
import com.example.myokdownload.dowload.OKDownload;
import com.example.myokdownload.dowload.core.Util;
import com.example.myokdownload.dowload.core.breakpoint.BlockInfo;
import com.example.myokdownload.dowload.core.breakpoint.BreakpointInfo;
import com.example.myokdownload.dowload.core.breakpoint.DownloadStore;
import com.example.myokdownload.dowload.core.exception.PreAllocateException;
import com.example.myokdownload.dowload.core.log.LogUtil;
import com.example.myokdownload.dowload.core.thread.ThreadUtil;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

public class MultiPointOutputStream {
    private static final String TAG = "MultiPointOutputStream";
    private static final ExecutorService FILE_IO_EXECUTOR = new ThreadPoolExecutor(0, Integer.MAX_VALUE, 60, TimeUnit.SECONDS, new SynchronousQueue<>(), ThreadUtil.threadFactory("OKDownload file io", false));

    final SparseArray<DownloadOutputStream> outputStreamMap = new SparseArray<>();

    final SparseArray<AtomicLong> noSyncLengthMap = new SparseArray<>();
    final AtomicLong allNoSyncLength = new AtomicLong();
    final AtomicLong lastSyncTimestamp = new AtomicLong();
    boolean canceled = false;

    private final int flushBufferSize;
    private final int syncBufferSize;
    private final int syncBufferIntervalMills;
    private final BreakpointInfo info;
    private final DownloadTask task;
    private final DownloadStore store;
    private final boolean supportSeek;
    private final boolean isPreAllocateLength;

    volatile Future syncFuture;
    volatile Thread runSyncThread;
    final SparseArray<Thread> parkedRunBlockThreadMap = new SparseArray<>();

    @NonNull
    private final Runnable syncRunnable;
    private String path;

    IOException syncException;
    @NonNull
    ArrayList<Integer> noMoreStreamList;

    List<Integer> requireStreamBlocks;

    MultiPointOutputStream(@NonNull final DownloadTask task, @NonNull BreakpointInfo info, @NonNull DownloadStore store,
        @Nullable Runnable syncRunnable) {
        this.task = task;
        this.flushBufferSize = task.flushBufferSize;
        this.syncBufferSize = task.syncBufferSize;
        this.syncBufferIntervalMills = task.syncBufferIntervalMills;
        this.info = info;

        this.store = store;
        this.supportSeek = OKDownload.with().outputStreamFactory.supportSeek();
        this.isPreAllocateLength = OKDownload.with().processFileStrategy.isPreAllocateLength(task);
        this.noMoreStreamList = new ArrayList<>();
        if (syncRunnable == null) {
            this.syncRunnable = new Runnable() {
                @Override
                public void run() {
                    runSyncDelayException();
                }
            };
        } else {
            this.syncRunnable = syncRunnable;
        }

        final File file = task.getFile();
        if (file != null) this.path = file.getAbsolutePath();
    }

    public MultiPointOutputStream(@NonNull DownloadTask task,
                                  @NonNull BreakpointInfo info,
                                  @NonNull DownloadStore store) {
        this(task, info, store, null);
    }

    void runSyncDelayException() {

    }

    void inspectAndPersist() throws IOException {
        if (syncException != null) throw syncException;
        if (syncFuture == null) {
            synchronized (syncRunnable) {
                if (syncFuture == null) {
                    syncFuture = executeSyncRunnableAsync();
                }
            }
        }
    }

    public void catchBlockConnectException(int blockIndex) {
        noMoreStreamList.add(blockIndex);
    }

    Future executeSyncRunnableAsync() {
        return FILE_IO_EXECUTOR.submit(syncRunnable);
    }

    public synchronized void write(int blockIndex, byte[] bytes, int length) throws IOException {
        if (canceled) return;

        outputStream(blockIndex).write(bytes, 0, length);

        allNoSyncLength.addAndGet(length);
        noSyncLengthMap.get(blockIndex).addAndGet(length);
        inspectAndPersist();
    }

    final StreamsState doneState = new StreamsState();

    public void done(int blockIndex) throws IOException {
        noMoreStreamList.add(blockIndex);
        try {
            if (syncException != null) throw syncException;
            if (syncFuture != null && !syncFuture.isDone()) {
                final AtomicLong noSyncLength = noSyncLengthMap.get(blockIndex);
                if (noSyncLength != null && noSyncLength.get() > 0) {
                    inspectStreamState(doneState);
                    final boolean isNoMoreStream = doneState.isNoMoreStream;

                    ensureSync(isNoMoreStream, blockIndex);
                }
            } else {
                if (syncFuture == null) {
                    LogUtil.d(TAG, "OutputStream done but no need to ensure sync, because the "
                            + "sync job not run yet. task[" + task.getId()
                            + "] block[" + blockIndex + "]");
                } else {
                    LogUtil.d(TAG, "OutputStream done but no need to ensure sync, because the "
                            + "syncFuture.isDone[" + syncFuture.isDone() + "] task[" + task.getId()
                            + "] block[" + blockIndex + "]");
                }
            }
        } finally {
            close(blockIndex);
        }
    }

    void ensureSync(boolean isNoMoreStream, int blockIndex) {
        if (syncFuture == null || syncFuture.isDone()) return;
        if (!isNoMoreStream) {
            parkedRunBlockThreadMap.put(blockIndex, Thread.currentThread());
        }

        if (runSyncThread != null) {
            unparkThread(runSyncThread);
        } else {
            while (true) {
                if (isRunSyncThreadValid()) {
                    unparkThread(runSyncThread);
                    break;
                } else {
                    parkThread(25);
                }
            }
        }
        if (isNoMoreStream) {
            unparkThread(runSyncThread);
            try {
                syncFuture.get();
            } catch (InterruptedException ignored) {

            } catch (ExecutionException ignored) {

            }
        } else {
            parkThread();
        }
    }

    void parkThread() {
        LockSupport.park();
    }

    void parkThread(long milliseconds) {
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(milliseconds));
    }

    boolean isRunSyncThreadValid() {
        return runSyncThread != null;
    }

    void unparkThread(Thread thread) {
        LockSupport.unpark(thread);
    }

    public void setRequireStreamBlocks(List<Integer> requireStreamBlocks) {
        this.requireStreamBlocks = requireStreamBlocks;
    }

    public void inspectComplete(int blockIndex) throws IOException {
        final BlockInfo blockInfo = info.getBlock(blockIndex);
        if (!(blockInfo.getCurrentOffset() == blockInfo.getContentLength())) {
            throw new IOException("The current offset on block-info isn't update correct, "
                    + blockInfo.getCurrentOffset() + " != " + blockInfo.getContentLength()
                    + " on " + blockIndex);
        }
    }

    void inspectStreamState(StreamsState state) {
        state.newNoMoreStreamBlockList.clear();

        final List<Integer> clonedList = (List<Integer>) noMoreStreamList.clone();
        final Set<Integer> uniqueBlockList = new HashSet<>(clonedList);
        final int noMoreStreamBlockCount = uniqueBlockList.size();
        if (noMoreStreamBlockCount != requireStreamBlocks.size()) {
            LogUtil.d(TAG, "task[" + task.getId() + "] current need fetching block count "
                    + requireStreamBlocks.size() + " is not equal to no more stream block count "
                    + noMoreStreamBlockCount);
            state.isNoMoreStream = false;
        } else {
            LogUtil.d(TAG, "task[" + task.getId() + "] current need fetching block count "
                    + requireStreamBlocks.size() + " is equal to no more stream block count "
                    + noMoreStreamBlockCount);
            state.isNoMoreStream = true;
        }

        final SparseArray<DownloadOutputStream> streamMap = outputStreamMap.clone();
        final int size = streamMap.size();
        for (int i = 0; i < size; i++) {
            final int blockIndex = streamMap.keyAt(i);
            if (noMoreStreamList.contains(blockIndex) && !state.noMoreStreamBlockList.contains(blockIndex)) {
                state.noMoreStreamBlockList.add(blockIndex);
                state.newNoMoreStreamBlockList.add(blockIndex);
            }
        }
    }

    synchronized void close(int blockIndex) throws IOException {
        final DownloadOutputStream outputStream = outputStreamMap.get(blockIndex);
        if (outputStream != null) {
            outputStream.close();
            outputStreamMap.remove(blockIndex);
            LogUtil.d(TAG, "OutputStream close task[" + task.getId() + "] block[" + blockIndex + "]");
        }
    }

    private volatile boolean firstOutputStream = true;

    synchronized DownloadOutputStream outputStream(int blockIndex) throws IOException {
        DownloadOutputStream outputStream = outputStreamMap.get(blockIndex);

        if (outputStream == null) {
            @NonNull final Uri uri;
            final boolean isFileScheme = Util.isUriFileScheme(task.uri);
            if (isFileScheme) {
                final File file = task.getFile();
                if (file == null) throw new FileNotFoundException("Filename is not ready!");

                final File parentFile = task.getParentFile();
                if (!parentFile.exists() && !parentFile.mkdirs()) {
                    throw new IOException("Create parent folder failed");
                }

                if (file.createNewFile()) {
                    LogUtil.d(TAG, "Create new file: " + file.getName());
                }

                uri = Uri.fromFile(file);
            } else {
                uri = task.uri;
            }

            outputStream = OKDownload.with().outputStreamFactory.create(OKDownload.with().context, uri, flushBufferSize);
            if (supportSeek) {
                final long seekPoint = info.getBlock(blockIndex).getRangeLeft();
                if (seekPoint > 0) {
                    outputStream.seek(seekPoint);
                    LogUtil.d(TAG, "Create output stream write from (" + task.getId()
                            + ") block(" + blockIndex + ") " + seekPoint);
                }
            }

            if (firstOutputStream) {
                store.markFileDirty(task.getId());
            }

            if (!info.chunked && firstOutputStream && isPreAllocateLength) {
                final long totalLength = info.getTotalLength();
                if (isFileScheme) {
                    final File file = task.getFile();
                    final long requireSpace = totalLength - file.length();
                    if (requireSpace > 0) {
                        inspectFreeSpace(new StatFs(file.getAbsolutePath()), requireSpace);
                        outputStream.setLength(totalLength);
                    }
                } else {
                    outputStream.setLength(totalLength);
                }
            }

            synchronized (noSyncLengthMap) {
                outputStreamMap.put(blockIndex, outputStream);
                noSyncLengthMap.put(blockIndex, new AtomicLong());
            }

            firstOutputStream = false;
        }
        return outputStream;
    }

    void inspectFreeSpace(StatFs statFs, long requireSpace) throws PreAllocateException {
        final long freeSpace = Util.getFreeSpaceBytes(statFs);
        if (freeSpace < requireSpace) {
            throw new PreAllocateException(requireSpace, freeSpace);
        }
    }

    static class StreamsState {
        boolean isNoMoreStream;

        List<Integer> noMoreStreamBlockList = new ArrayList<>();
        List<Integer> newNoMoreStreamBlockList = new ArrayList<>();

        boolean isStreamsEndOrChanged() {
            return isNoMoreStream || newNoMoreStreamBlockList.size() > 0;
        }
    }
}
