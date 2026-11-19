package net.kdt.pojavlaunch.modloaders.modpacks.api;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.utils.DownloadUtils;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class ModDownloader {
    private static final ThreadLocal<byte[]> sThreadLocalBuffer = new ThreadLocal<>();
    private static final int MAX_QUERY_ATTEMPTS = 3;
    private static final int MAX_DOWNLOAD_ATTEMPTS = 4;

    private ThreadPoolExecutor mDownloadPool = createPool();
    private final AtomicBoolean mTerminator = new AtomicBoolean(false);
    private final AtomicLong mDownloadSize = new AtomicLong(0);
    private final AtomicInteger mSucceededCount = new AtomicInteger(0);
    private final Object mExceptionSyncPoint = new Object();
    private final File mDestinationDirectory;
    private final boolean mUseFileCount;
    private final boolean mAllowPartialFailure;
    private final List<FailedTask> mFailedTasks = Collections.synchronizedList(new ArrayList<>());
    private final List<CurseForgeException.FileFailure> mOptionalFailures = Collections.synchronizedList(new ArrayList<>());
    private IOException mFirstIOException;
    private long mTotalSize;

    public ModDownloader(File destinationDirectory) {
        this(destinationDirectory, false, false);
    }

    public ModDownloader(File destinationDirectory, boolean useFileCount) {
        this(destinationDirectory, useFileCount, false);
    }

    public ModDownloader(File destinationDirectory, boolean useFileCount, boolean allowPartialFailure) {
        this.mDestinationDirectory = destinationDirectory;
        this.mUseFileCount = useFileCount;
        this.mAllowPartialFailure = allowPartialFailure;
    }

    private static ThreadPoolExecutor createPool() {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(4, 4, 100, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>());
        pool.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
        return pool;
    }

    public void submitDownload(int fileSize, String relativePath, @Nullable String downloadHash, String... url) {
        if (!net.kdt.pojavlaunch.utils.ZipUtils.isSafeEntryName(relativePath)) {
            throw new IllegalArgumentException("Blocked unsafe modpack file path: " + relativePath);
        }
        File target = new File(mDestinationDirectory, relativePath);
        try {
            String destCanonical = mDestinationDirectory.getCanonicalPath();
            String prefix = destCanonical.endsWith(File.separator) ? destCanonical : destCanonical + File.separator;
            if (!target.getCanonicalPath().startsWith(prefix)) {
                throw new IllegalArgumentException("Blocked path traversal in modpack file: " + relativePath);
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Unable to validate modpack file path: " + relativePath, e);
        }
        if (mUseFileCount) mTotalSize += 1;
        else mTotalSize += Math.max(fileSize, 0);
        mDownloadPool.execute(new DownloadTask(url, target, downloadHash, null, -1L, null, true, 0L, 0L, null, null));
    }

    public void submitDownload(FileInfoProvider infoProvider) {
        submitDownload(0L, 0L, true, infoProvider);
    }

    public void submitDownload(long projectId, long fileId, boolean required, FileInfoProvider infoProvider) {
        if (!mUseFileCount) throw new RuntimeException("This method can only be used in a file-counting ModDownloader");
        mTotalSize += 1;
        mDownloadPool.execute(new FileInfoQueryTask(projectId, fileId, required, infoProvider));
    }

    public void awaitFinish(Tools.DownloaderFeedback feedback) throws IOException {
        awaitPoolFinish(mDownloadPool, feedback);
    }

    public void retryFailed(Tools.DownloaderFeedback feedback) throws IOException {
        List<FailedTask> toRetry;
        synchronized (mFailedTasks) {
            if (mFailedTasks.isEmpty()) return;
            toRetry = new ArrayList<>(mFailedTasks);
            mFailedTasks.clear();
        }
        mTerminator.set(false);
        mFirstIOException = null;
        if (mUseFileCount) {
            mDownloadSize.addAndGet(-toRetry.size());
        }
        ThreadPoolExecutor retryPool = createPool();
        for (FailedTask failed : toRetry) {
            retryPool.execute(failed.taskRunnable);
        }
        awaitPoolFinish(retryPool, feedback);
    }

    private void awaitPoolFinish(ThreadPoolExecutor pool, Tools.DownloaderFeedback feedback) throws IOException {
        try {
            pool.shutdown();
            while (!pool.awaitTermination(20, TimeUnit.MILLISECONDS) && !mTerminator.get()) {
                if (feedback != null) {
                    emitScaledProgress(feedback, mDownloadSize.get(), mTotalSize);
                }
            }
            if (mTerminator.get()) {
                pool.shutdownNow();
                synchronized (mExceptionSyncPoint) {
                    if (mFirstIOException == null) mExceptionSyncPoint.wait();
                    throw mFirstIOException;
                }
            }
            if (feedback != null) {
                emitScaledProgress(feedback, mDownloadSize.get(), mTotalSize);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pool.shutdownNow();
            throw new InterruptedIOException("Modpack download interrupted");
        }
    }

    private static void emitScaledProgress(Tools.DownloaderFeedback feedback, long current, long total) {
        if (total <= Integer.MAX_VALUE && current <= Integer.MAX_VALUE) {
            feedback.updateProgress((int) Math.max(0L, current), (int) Math.max(0L, total));
            return;
        }
        // Avoid 32-bit int overflow for multi-gigabyte modpacks by scaling to basis points (0..10000)
        int maxScale = 10_000;
        int scaled = total > 0L ? (int) Math.min(maxScale, (Math.max(0L, current) * maxScale) / total) : 0;
        feedback.updateProgress(scaled, maxScale);
    }

    public int getSucceededCount() {
        return mSucceededCount.get();
    }

    public int getTotalCount() {
        return (int) mTotalSize;
    }

    @NonNull
    public List<CurseForgeException.FileFailure> getFailedDownloads() {
        synchronized (mFailedTasks) {
            List<CurseForgeException.FileFailure> out = new ArrayList<>(mFailedTasks.size());
            for (FailedTask t : mFailedTasks) {
                out.add(t.failure);
            }
            return out;
        }
    }

    @NonNull
    public List<CurseForgeException.FileFailure> getOptionalFailures() {
        synchronized (mOptionalFailures) {
            return new ArrayList<>(mOptionalFailures);
        }
    }

    public boolean hasRetryableFailures() {
        synchronized (mFailedTasks) {
            for (FailedTask t : mFailedTasks) {
                if (CurseForgeException.isRetryableCode(t.failure.errorCode)) {
                    return true;
                }
            }
            return false;
        }
    }

    private static byte[] getThreadLocalBuffer() {
        byte[] buffer = sThreadLocalBuffer.get();
        if (buffer != null) return buffer;
        buffer = new byte[8192];
        sThreadLocalBuffer.set(buffer);
        return buffer;
    }

    private void downloadFailed(IOException exception) {
        mTerminator.set(true);
        synchronized (mExceptionSyncPoint) {
            if (mFirstIOException == null) {
                mFirstIOException = exception;
                mExceptionSyncPoint.notifyAll();
            }
        }
    }

    private void recordTaskFailure(
            long projectId,
            long fileId,
            @Nullable String modName,
            @Nullable String fileName,
            @Nullable String websiteUrl,
            boolean required,
            @NonNull IOException exception,
            @NonNull Runnable retryRunnable
    ) {
        CurseForgeException.FileFailure failure = toFileFailure(
                projectId, fileId, modName, fileName, websiteUrl, required, exception
        );
        if (!required) {
            mOptionalFailures.add(failure);
            if (mUseFileCount) mDownloadSize.addAndGet(1);
            return;
        }
        if (mAllowPartialFailure) {
            mFailedTasks.add(new FailedTask(failure, retryRunnable));
            if (mUseFileCount) mDownloadSize.addAndGet(1);
        } else {
            downloadFailed(exception);
        }
    }

    private static CurseForgeException.FileFailure toFileFailure(
            long projectId,
            long fileId,
            @Nullable String modName,
            @Nullable String fileName,
            @Nullable String websiteUrl,
            boolean required,
            @NonNull IOException exception
    ) {
        if (exception instanceof CurseForgeException) {
            CurseForgeException cfe = (CurseForgeException) exception;
            long effProjectId = cfe.getProjectId() > 0 ? cfe.getProjectId() : projectId;
            long effFileId = cfe.getFileId() > 0 ? cfe.getFileId() : fileId;
            String effModName = cfe.getModName() != null ? cfe.getModName() : modName;
            String effFileName = cfe.getFileName() != null ? cfe.getFileName() : fileName;
            String effWebsiteUrl = cfe.getWebsiteUrl() != null ? cfe.getWebsiteUrl() : websiteUrl;
            String msg = cfe.getMessage() != null ? cfe.getMessage() : cfe.getErrorCode().name();
            return new CurseForgeException.FileFailure(
                    effProjectId,
                    effFileId,
                    effModName,
                    effFileName,
                    cfe.getErrorCode(),
                    cfe.getHttpStatus(),
                    effWebsiteUrl,
                    cfe.getRetryAfterSeconds(),
                    required,
                    msg
            );
        }
        String msg = exception.getMessage() != null ? exception.getMessage() : exception.getClass().getSimpleName();
        return new CurseForgeException.FileFailure(
                projectId,
                fileId,
                modName,
                fileName,
                CurseForgeException.ErrorCode.NETWORK_ERROR,
                0,
                websiteUrl,
                null,
                required,
                msg
        );
    }

    private static boolean isRetryableException(@NonNull IOException e) {
        if (e instanceof InterruptedIOException) return false;
        if (e instanceof CurseForgeException) {
            return ((CurseForgeException) e).isRetryable();
        }
        return true;
    }

    private static long computeBackoffMs(int attempt, @NonNull IOException e) {
        if (e instanceof CurseForgeException) {
            Integer retryAfter = ((CurseForgeException) e).getRetryAfterSeconds();
            if (retryAfter != null && retryAfter > 0) {
                return Math.min(retryAfter * 1000L, 5000L);
            }
        }
        return Math.min(400L * (1L << attempt), 2500L);
    }

    private static boolean isAlreadyVerified(
            @NonNull File destination,
            @Nullable String sha1,
            @Nullable String md5,
            long expectedSize
    ) {
        if (!destination.isFile() || destination.length() <= 0) return false;
        if (expectedSize > 0 && destination.length() != expectedSize) return false;
        if (sha1 != null && !sha1.trim().isEmpty()) {
            return Tools.compareSHA1(destination, sha1);
        }
        if (md5 != null && !md5.trim().isEmpty()) {
            return DownloadUtils.compareMD5(destination, md5);
        }
        return true;
    }

    private static final class FailedTask {
        final CurseForgeException.FileFailure failure;
        final Runnable taskRunnable;

        FailedTask(CurseForgeException.FileFailure failure, Runnable taskRunnable) {
            this.failure = failure;
            this.taskRunnable = taskRunnable;
        }
    }

    class FileInfoQueryTask implements Runnable {
        private final long mProjectId;
        private final long mFileId;
        private final boolean mRequired;
        private final FileInfoProvider mFileInfoProvider;

        public FileInfoQueryTask(FileInfoProvider fileInfoProvider) {
            this(0L, 0L, true, fileInfoProvider);
        }

        public FileInfoQueryTask(long projectId, long fileId, boolean required, FileInfoProvider fileInfoProvider) {
            this.mProjectId = projectId;
            this.mFileId = fileId;
            this.mRequired = required;
            this.mFileInfoProvider = fileInfoProvider;
        }

        @Override
        public void run() {
            FileInfo fileInfo = null;
            IOException lastException = null;
            for (int attempt = 0; attempt < MAX_QUERY_ATTEMPTS; attempt++) {
                if (Thread.currentThread().isInterrupted() || mTerminator.get()) return;
                try {
                    fileInfo = mFileInfoProvider.getFileInfo();
                    lastException = null;
                    break;
                } catch (InterruptedIOException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (IOException e) {
                    lastException = e;
                    if (!isRetryableException(e) || attempt >= MAX_QUERY_ATTEMPTS - 1) {
                        break;
                    }
                    try {
                        Thread.sleep(computeBackoffMs(attempt, e));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }

            if (lastException != null) {
                recordTaskFailure(mProjectId, mFileId, null, null, null, mRequired, lastException, this);
                return;
            }
            if (fileInfo == null) {
                if (!mRequired) {
                    if (mUseFileCount) mDownloadSize.addAndGet(1);
                    return;
                }
                CurseForgeException missingUrlEx = CurseForgeException.forFileError(
                        CurseForgeException.ErrorCode.DOWNLOAD_URL_MISSING,
                        mProjectId, mFileId, 0, null, null, null, null, null, null
                );
                recordTaskFailure(mProjectId, mFileId, null, null, null, true, missingUrlEx, this);
                return;
            }

            File destination = new File(mDestinationDirectory, fileInfo.relativePath);
            new DownloadTask(
                    new String[]{fileInfo.url},
                    destination,
                    fileInfo.sha1,
                    fileInfo.md5,
                    fileInfo.expectedSize,
                    fileInfo.headers,
                    mRequired,
                    fileInfo.projectId > 0 ? fileInfo.projectId : mProjectId,
                    fileInfo.fileId > 0 ? fileInfo.fileId : mFileId,
                    fileInfo.modName,
                    fileInfo.websiteUrl
            ).run();
        }
    }

    class DownloadTask implements Runnable, Tools.DownloaderFeedback {
        private final String[] mDownloadUrls;
        private final File mDestination;
        @Nullable private final String mSha1;
        @Nullable private final String mMd5;
        private final long mExpectedSize;
        @Nullable private final Map<String, String> mHeaders;
        private final boolean mRequired;
        private final long mProjectId;
        private final long mFileId;
        @Nullable private final String mModName;
        @Nullable private final String mWebsiteUrl;
        private int last = 0;

        public DownloadTask(String[] downloadurls,
                            File downloadDestination, String downloadHash) {
            this(downloadurls, downloadDestination, downloadHash, null, -1L, null, true, 0L, 0L, null, null);
        }

        public DownloadTask(
                String[] downloadUrls,
                File downloadDestination,
                @Nullable String sha1,
                @Nullable String md5,
                long expectedSize,
                @Nullable Map<String, String> headers,
                boolean required,
                long projectId,
                long fileId,
                @Nullable String modName,
                @Nullable String websiteUrl
        ) {
            this.mDownloadUrls = downloadUrls;
            this.mDestination = downloadDestination;
            this.mSha1 = sha1;
            this.mMd5 = md5;
            this.mExpectedSize = expectedSize;
            this.mHeaders = headers;
            this.mRequired = required;
            this.mProjectId = projectId;
            this.mFileId = fileId;
            this.mModName = modName;
            this.mWebsiteUrl = websiteUrl;
        }

        @Override
        public void run() {
            if (isAlreadyVerified(mDestination, mSha1, mMd5, mExpectedSize)) {
                mSucceededCount.incrementAndGet();
                if (mUseFileCount) mDownloadSize.addAndGet(1);
                else mDownloadSize.addAndGet(mDestination.length());
                return;
            }

            IOException lastError = null;
            for (String sourceUrl : mDownloadUrls) {
                try {
                    DownloadUtils.ensureSha1(mDestination, mSha1, (Callable<Void>) () -> {
                        IOException exception = tryDownload(sourceUrl);
                        if (exception != null) {
                            throw exception;
                        }
                        return null;
                    });
                    if (mMd5 != null && !DownloadUtils.compareMD5(mDestination, mMd5)) {
                        mDestination.delete();
                        throw new IOException("MD5 verification failed for " + mDestination.getName());
                    }
                    if (mExpectedSize > 0 && mDestination.length() != mExpectedSize) {
                        mDestination.delete();
                        throw new IOException("File size mismatch for " + mDestination.getName()
                                + ": expected " + mExpectedSize + " bytes, got " + mDestination.length());
                    }
                    mSucceededCount.incrementAndGet();
                    if (mUseFileCount) mDownloadSize.addAndGet(1);
                    return;
                } catch (IOException e) {
                    lastError = e;
                }
            }

            if (lastError != null) {
                recordTaskFailure(
                        mProjectId,
                        mFileId,
                        mModName,
                        mDestination.getName(),
                        mWebsiteUrl,
                        mRequired,
                        lastError,
                        this
                );
            }
        }

        private IOException tryDownload(String sourceUrl) throws InterruptedException {
            IOException exception = null;
            for (int i = 0; i < MAX_DOWNLOAD_ATTEMPTS; i++) {
                if (Thread.currentThread().isInterrupted() || mTerminator.get()) {
                    throw new InterruptedException();
                }
                try {
                    DownloadUtils.downloadFileMonitored(
                            sourceUrl,
                            mDestination,
                            getThreadLocalBuffer(),
                            this,
                            mHeaders,
                            mExpectedSize
                    );
                    return null;
                } catch (InterruptedIOException e) {
                    throw new InterruptedException();
                } catch (IOException e) {
                    exception = e;
                    if (!isRetryableException(e) || i >= MAX_DOWNLOAD_ATTEMPTS - 1) {
                        break;
                    }
                }
                if (!mUseFileCount) {
                    mDownloadSize.addAndGet(-last);
                    last = 0;
                }
                Thread.sleep(computeBackoffMs(i, exception));
            }
            return exception;
        }

        @Override
        public void updateProgress(int curr, int max) {
            if (mUseFileCount) return;
            mDownloadSize.addAndGet(curr - last);
            last = curr;
        }
    }

    public static class FileInfo {
        public final String url;
        public final String relativePath;
        @Nullable public final String sha1;
        @Nullable public final String md5;
        public final long expectedSize;
        @Nullable public final Map<String, String> headers;
        public final long projectId;
        public final long fileId;
        @Nullable public final String modName;
        @Nullable public final String websiteUrl;

        public FileInfo(String url, String relativePath, @Nullable String sha1) {
            this(url, relativePath, sha1, null, -1L, null, 0L, 0L, null, null);
        }

        public FileInfo(
                String url,
                String relativePath,
                @Nullable String sha1,
                @Nullable String md5,
                long expectedSize,
                @Nullable Map<String, String> headers,
                long projectId,
                long fileId,
                @Nullable String modName,
                @Nullable String websiteUrl
        ) {
            this.url = url;
            this.relativePath = relativePath;
            this.sha1 = sha1;
            this.md5 = md5;
            this.expectedSize = expectedSize;
            this.headers = headers;
            this.projectId = projectId;
            this.fileId = fileId;
            this.modName = modName;
            this.websiteUrl = websiteUrl;
        }
    }

    public interface FileInfoProvider {
        FileInfo getFileInfo() throws IOException;
    }
}
