package net.kdt.pojavlaunch.modloaders.modpacks.api;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Structured CurseForge API/download exception with actionable error codes and user-friendly messages.
 */
public class CurseForgeException extends IOException {

    public enum ErrorCode {
        FILE_NOT_FOUND,
        FILE_UNAVAILABLE,
        DISTRIBUTION_NOT_ALLOWED,
        API_KEY_INVALID,
        API_RATE_LIMITED,
        API_SERVER_ERROR,
        DOWNLOAD_URL_MISSING,
        NETWORK_ERROR
    }

    public static final class FileFailure {
        public final long projectId;
        public final long fileId;
        @Nullable public final String modName;
        @Nullable public final String fileName;
        @NonNull public final ErrorCode errorCode;
        public final int httpStatus;
        @Nullable public final String websiteUrl;
        @Nullable public final Integer retryAfterSeconds;
        public final boolean required;
        @NonNull public final String detailMessage;

        public FileFailure(
                long projectId,
                long fileId,
                @Nullable String modName,
                @Nullable String fileName,
                @NonNull ErrorCode errorCode,
                int httpStatus,
                @Nullable String websiteUrl,
                @Nullable Integer retryAfterSeconds,
                boolean required,
                @NonNull String detailMessage
        ) {
            this.projectId = projectId;
            this.fileId = fileId;
            this.modName = modName;
            this.fileName = fileName;
            this.errorCode = errorCode;
            this.httpStatus = httpStatus;
            this.websiteUrl = websiteUrl;
            this.retryAfterSeconds = retryAfterSeconds;
            this.required = required;
            this.detailMessage = detailMessage;
        }

        public @NonNull String getDisplayLabel() {
            if (modName != null && !modName.isEmpty() && fileName != null && !fileName.isEmpty()) {
                return modName + " (" + fileName + ")";
            }
            if (modName != null && !modName.isEmpty()) {
                return modName + " [project " + projectId + ", file " + fileId + "]";
            }
            if (fileName != null && !fileName.isEmpty()) {
                return fileName + " [project " + projectId + ", file " + fileId + "]";
            }
            if (projectId > 0 || fileId > 0) {
                return "project " + projectId + ", file " + fileId;
            }
            return "requested file";
        }
    }

    @NonNull private final ErrorCode mErrorCode;
    private final long mProjectId;
    private final long mFileId;
    private final int mHttpStatus;
    @Nullable private final String mModName;
    @Nullable private final String mFileName;
    @Nullable private final String mWebsiteUrl;
    @Nullable private final Integer mRetryAfterSeconds;
    @NonNull private final List<FileFailure> mFailedFiles;

    public CurseForgeException(
            @NonNull ErrorCode errorCode,
            @NonNull String message
    ) {
        this(errorCode, 0L, 0L, 0, null, null, null, null, Collections.emptyList(), message, null);
    }

    public CurseForgeException(
            @NonNull ErrorCode errorCode,
            @NonNull String message,
            @Nullable Throwable cause
    ) {
        this(errorCode, 0L, 0L, 0, null, null, null, null, Collections.emptyList(), message, cause);
    }

    public CurseForgeException(
            @NonNull ErrorCode errorCode,
            long projectId,
            long fileId,
            int httpStatus,
            @Nullable String modName,
            @Nullable String fileName,
            @Nullable String websiteUrl,
            @Nullable Integer retryAfterSeconds,
            @NonNull String message,
            @Nullable Throwable cause
    ) {
        this(errorCode, projectId, fileId, httpStatus, modName, fileName, websiteUrl, retryAfterSeconds,
                Collections.emptyList(), message, cause);
    }

    private CurseForgeException(
            @NonNull ErrorCode errorCode,
            long projectId,
            long fileId,
            int httpStatus,
            @Nullable String modName,
            @Nullable String fileName,
            @Nullable String websiteUrl,
            @Nullable Integer retryAfterSeconds,
            @NonNull List<FileFailure> failedFiles,
            @NonNull String message,
            @Nullable Throwable cause
    ) {
        super(message, cause);
        mErrorCode = errorCode;
        mProjectId = projectId;
        mFileId = fileId;
        mHttpStatus = httpStatus;
        mModName = modName;
        mFileName = fileName;
        mWebsiteUrl = websiteUrl;
        mRetryAfterSeconds = retryAfterSeconds;
        mFailedFiles = Collections.unmodifiableList(new ArrayList<>(failedFiles));
    }

    @NonNull
    public ErrorCode getErrorCode() {
        return mErrorCode;
    }

    public long getProjectId() {
        return mProjectId;
    }

    public long getFileId() {
        return mFileId;
    }

    public int getHttpStatus() {
        return mHttpStatus;
    }

    @Nullable
    public String getModName() {
        return mModName;
    }

    @Nullable
    public String getFileName() {
        return mFileName;
    }

    @Nullable
    public String getWebsiteUrl() {
        return mWebsiteUrl;
    }

    @Nullable
    public Integer getRetryAfterSeconds() {
        return mRetryAfterSeconds;
    }

    @NonNull
    public List<FileFailure> getFailedFiles() {
        return mFailedFiles;
    }

    public boolean isRetryable() {
        return isRetryableCode(mErrorCode);
    }

    public static boolean isRetryableCode(@NonNull ErrorCode code) {
        return code == ErrorCode.API_RATE_LIMITED
                || code == ErrorCode.API_SERVER_ERROR
                || code == ErrorCode.NETWORK_ERROR;
    }

    @NonNull
    public FileFailure toFileFailure(boolean required) {
        String msg = getMessage() != null ? getMessage() : mErrorCode.name();
        return new FileFailure(
                mProjectId,
                mFileId,
                mModName,
                mFileName,
                mErrorCode,
                mHttpStatus,
                mWebsiteUrl,
                mRetryAfterSeconds,
                required,
                msg
        );
    }

    @NonNull
    public static CurseForgeException forFileError(
            @NonNull ErrorCode errorCode,
            long projectId,
            long fileId,
            int httpStatus,
            @Nullable String modName,
            @Nullable String fileName,
            @Nullable String websiteUrl,
            @Nullable Integer retryAfterSeconds,
            @Nullable String serverDetail,
            @Nullable Throwable cause
    ) {
        String message = buildUserMessage(
                errorCode, projectId, fileId, httpStatus,
                modName, fileName, websiteUrl, retryAfterSeconds, serverDetail
        );
        return new CurseForgeException(
                errorCode,
                projectId,
                fileId,
                httpStatus,
                modName,
                fileName,
                websiteUrl,
                retryAfterSeconds,
                message,
                cause
        );
    }

    @NonNull
    public static CurseForgeException partialFailure(
            int totalFiles,
            int succeededFiles,
            @NonNull List<FileFailure> requiredFailures,
            @NonNull List<FileFailure> optionalFailures
    ) {
        if (requiredFailures.size() == 1 && totalFiles <= 1) {
            FileFailure only = requiredFailures.get(0);
            return new CurseForgeException(
                    only.errorCode,
                    only.projectId,
                    only.fileId,
                    only.httpStatus,
                    only.modName,
                    only.fileName,
                    only.websiteUrl,
                    only.retryAfterSeconds,
                    requiredFailures,
                    only.detailMessage,
                    null
            );
        }

        ErrorCode primaryCode = selectPrimaryErrorCode(requiredFailures);
        FileFailure first = requiredFailures.isEmpty() ? null : requiredFailures.get(0);
        StringBuilder sb = new StringBuilder();
        if (requiredFailures.size() == 1 && first != null) {
            sb.append(first.detailMessage);
            if (succeededFiles > 0) {
                sb.append("\n\n").append(succeededFiles).append(" of ").append(totalFiles)
                        .append(" modpack files were downloaded and kept so you can retry without starting over.");
            }
        } else {
            sb.append("Downloaded ").append(succeededFiles).append(" of ").append(totalFiles)
                    .append(" modpack files. ").append(requiredFailures.size())
                    .append(" required file(s) failed:");
            int shown = Math.min(requiredFailures.size(), 5);
            for (int i = 0; i < shown; i++) {
                FileFailure f = requiredFailures.get(i);
                sb.append("\n- ").append(f.getDisplayLabel())
                        .append(" [").append(f.errorCode.name()).append("]: ")
                        .append(shortReason(f));
            }
            if (requiredFailures.size() > shown) {
                sb.append("\n... and ").append(requiredFailures.size() - shown).append(" more.");
            }
            if (succeededFiles > 0) {
                sb.append("\n\nAlready-downloaded mods and overrides were preserved; retrying will only download the missing file(s).");
            }
        }
        if (!optionalFailures.isEmpty()) {
            sb.append("\n(").append(optionalFailures.size()).append(" optional file(s) were also unavailable and skipped.)");
        }
        return new CurseForgeException(
                primaryCode,
                first != null ? first.projectId : 0L,
                first != null ? first.fileId : 0L,
                first != null ? first.httpStatus : 0,
                first != null ? first.modName : null,
                first != null ? first.fileName : null,
                first != null ? first.websiteUrl : null,
                first != null ? first.retryAfterSeconds : null,
                requiredFailures,
                sb.toString(),
                null
        );
    }

    @NonNull
    private static ErrorCode selectPrimaryErrorCode(@NonNull List<FileFailure> failures) {
        if (failures.isEmpty()) return ErrorCode.API_SERVER_ERROR;
        for (FileFailure f : failures) {
            if (f.errorCode == ErrorCode.API_KEY_INVALID) return ErrorCode.API_KEY_INVALID;
        }
        for (FileFailure f : failures) {
            if (f.errorCode == ErrorCode.DISTRIBUTION_NOT_ALLOWED) return ErrorCode.DISTRIBUTION_NOT_ALLOWED;
        }
        return failures.get(0).errorCode;
    }

    @NonNull
    private static String shortReason(@NonNull FileFailure f) {
        switch (f.errorCode) {
            case DISTRIBUTION_NOT_ALLOWED:
                return f.websiteUrl != null && !f.websiteUrl.isEmpty()
                        ? "Third-party distribution disabled by author (download manually: " + f.websiteUrl + ")"
                        : "Third-party distribution disabled by author on CurseForge";
            case FILE_NOT_FOUND:
                return "File not found on CurseForge (HTTP 404)";
            case FILE_UNAVAILABLE:
                return "File is marked unavailable on CurseForge";
            case API_KEY_INVALID:
                return "CurseForge API key is missing or invalid";
            case API_RATE_LIMITED:
                return f.retryAfterSeconds != null
                        ? "Rate limited by CurseForge (retry after " + f.retryAfterSeconds + "s)"
                        : "Rate limited by CurseForge (HTTP 429)";
            case DOWNLOAD_URL_MISSING:
                return "CurseForge did not return a download URL";
            case NETWORK_ERROR:
                return "Network connection failed";
            case API_SERVER_ERROR:
            default:
                return f.httpStatus > 0 ? "CurseForge returned HTTP " + f.httpStatus : "CurseForge server error";
        }
    }

    @NonNull
    public static String buildUserMessage(
            @NonNull ErrorCode errorCode,
            long projectId,
            long fileId,
            int httpStatus,
            @Nullable String modName,
            @Nullable String fileName,
            @Nullable String websiteUrl,
            @Nullable Integer retryAfterSeconds,
            @Nullable String serverDetail
    ) {
        String target = formatTarget(projectId, fileId, modName, fileName);
        switch (errorCode) {
            case DISTRIBUTION_NOT_ALLOWED: {
                StringBuilder sb = new StringBuilder();
                sb.append("[").append(errorCode.name()).append("] ")
                        .append(target)
                        .append(" cannot be downloaded through STAR because the author disabled third-party distribution on CurseForge.");
                if (websiteUrl != null && !websiteUrl.isEmpty()) {
                    sb.append(" Download it manually from ").append(websiteUrl)
                            .append(" and place it in the instance's mods folder.");
                } else {
                    sb.append(" Download it manually from CurseForge in your browser and place it in the instance's mods folder.");
                }
                return sb.toString();
            }
            case API_KEY_INVALID: {
                String statusText = httpStatus > 0 ? " (HTTP " + httpStatus + ")" : "";
                String base = "[" + errorCode.name() + "] CurseForge API authentication failed" + statusText
                        + ". A valid CurseForge API key or Backend Proxy is required to download CurseForge mods and CDN files. "
                        + "Please configure a valid key or proxy in STAR Settings.";
                return appendDetail(base, serverDetail);
            }
            case FILE_NOT_FOUND:
                return appendDetail(
                        "[" + errorCode.name() + "] " + target + " was not found on CurseForge (HTTP 404). It may have been deleted or archived.",
                        serverDetail
                );
            case FILE_UNAVAILABLE:
                return appendDetail(
                        "[" + errorCode.name() + "] " + target + " is marked unavailable for download on CurseForge.",
                        serverDetail
                );
            case DOWNLOAD_URL_MISSING: {
                StringBuilder sb = new StringBuilder();
                sb.append("[").append(errorCode.name()).append("] CurseForge did not provide an official download URL for ")
                        .append(target).append(".");
                if (websiteUrl != null && !websiteUrl.isEmpty()) {
                    sb.append(" Check the project page at ").append(websiteUrl).append(".");
                }
                return appendDetail(sb.toString(), serverDetail);
            }
            case API_RATE_LIMITED: {
                String retryText = retryAfterSeconds != null
                        ? " Please wait " + retryAfterSeconds + " seconds and retry."
                        : " Please wait a moment and retry.";
                return appendDetail(
                        "[" + errorCode.name() + "] CurseForge API rate limit reached (HTTP 429) while fetching "
                                + target + "." + retryText,
                        serverDetail
                );
            }
            case NETWORK_ERROR:
                return appendDetail(
                        "[" + errorCode.name() + "] Network error while contacting CurseForge for "
                                + target + ". Check your internet connection and retry.",
                        serverDetail
                );
            case API_SERVER_ERROR:
            default: {
                String statusText = httpStatus > 0 ? " (HTTP " + httpStatus + ")" : "";
                return appendDetail(
                        "[" + errorCode.name() + "] CurseForge service error" + statusText + " while requesting " + target + ".",
                        serverDetail
                );
            }
        }
    }

    @NonNull
    private static String formatTarget(
            long projectId,
            long fileId,
            @Nullable String modName,
            @Nullable String fileName
    ) {
        if (modName != null && !modName.isEmpty() && fileName != null && !fileName.isEmpty()) {
            return "'" + modName + "' (" + fileName + ", project " + projectId + ", file " + fileId + ")";
        }
        if (modName != null && !modName.isEmpty()) {
            return "'" + modName + "' (project " + projectId + ", file " + fileId + ")";
        }
        if (fileName != null && !fileName.isEmpty()) {
            return "'" + fileName + "' (project " + projectId + ", file " + fileId + ")";
        }
        if (projectId > 0 || fileId > 0) {
            return "file " + fileId + " of project " + projectId;
        }
        return "the requested CurseForge file";
    }

    @NonNull
    private static String appendDetail(@NonNull String base, @Nullable String detail) {
        if (detail == null || detail.trim().isEmpty()) return base;
        return base + " Details: " + detail.trim();
    }

    @NonNull
    public static String redactSecrets(@Nullable String input, @Nullable String... secrets) {
        if (input == null) return "";
        String out = input;
        if (secrets != null) {
            for (String secret : secrets) {
                if (secret != null && !secret.trim().isEmpty()) {
                    out = out.replace(secret, "[REDACTED]");
                }
            }
        }
        out = out.replaceAll("(?i)(x-api-key\\s*[:=]\\s*)(\\S+)", "$1[REDACTED]");
        out = out.replaceAll("(?i)(Authorization\\s*:\\s*Bearer\\s+)(\\S+)", "$1[REDACTED]");
        return out;
    }
}
