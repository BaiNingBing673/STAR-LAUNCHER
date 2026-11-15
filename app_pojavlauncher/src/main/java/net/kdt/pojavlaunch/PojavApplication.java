package net.kdt.pojavlaunch;

import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.content.res.*;
import android.os.*;
import androidx.core.app.*;

import android.util.*;
import java.io.*;
import java.text.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.star.launcher.diagnostics.CrashReportCoordinator;
import com.star.launcher.diagnostics.StartupProfiler;

import net.kdt.pojavlaunch.lifecycle.ContextExecutor;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.tasks.AsyncAssetManager;
import net.kdt.pojavlaunch.utils.*;
import net.kdt.pojavlaunch.utils.FileUtils;

public class PojavApplication extends Application {
	public static final String CRASH_REPORT_TAG = "PojavCrashReport";
	public static final ThreadPoolExecutor sExecutorService = new ThreadPoolExecutor(4, 4, 500, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
	static {
		sExecutorService.allowCoreThreadTimeOut(true);
	}

	private static boolean isLauncherUiProcess(Context context) {
		String processName = null;
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
			processName = Application.getProcessName();
		}
		if (processName == null) {
			try (BufferedReader br = new BufferedReader(new FileReader("/proc/self/cmdline"))) {
				String line = br.readLine();
				if (line != null) processName = line.trim();
			} catch (IOException ignored) {
			}
		}
		if (processName == null || processName.isEmpty()) return true;
		String pkg = context.getPackageName();
		return processName.equals(pkg) || processName.endsWith(":launcher");
	}
	
	@Override
	public void onCreate() {
		StartupProfiler.markAppStart();
		final long appInitStart = StartupProfiler.beginStage();
		ContextExecutor.setApplication(this);
		Thread.setDefaultUncaughtExceptionHandler((thread, th) -> {
			boolean storagePermAllowed = (Build.VERSION.SDK_INT < 23 || Build.VERSION.SDK_INT >= 29 ||
					ActivityCompat.checkSelfPermission(PojavApplication.this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) && Tools.checkStorageRoot(PojavApplication.this);
			File crashFile = CrashReportCoordinator.recordUnhandledCrash(PojavApplication.this, thread, th, storagePermAllowed);
			FatalErrorActivity.showError(PojavApplication.this, crashFile.getAbsolutePath(), storagePermAllowed, th);
			Tools.fullyExit();
		});
		
		try {
			super.onCreate();
			final long prefsStart = StartupProfiler.beginStage();
			if(Tools.checkStorageRoot(this)){
				// Implicitly initializes early constants and storage constants.
				// Required to run the main activity properly.
				LauncherPreferences.loadPreferences(this);
			} else {
				// In other cases, only initialize enough for the basicmost basics to work
				// and not explode.
				Tools.initEarlyConstants(this);
			}
			StartupProfiler.endStage("storage_and_prefs_init", prefsStart);
			Tools.DEVICE_ARCHITECTURE = Architecture.getDeviceArchitecture();
			//Force x86 lib directory for Asus x86 based zenfones
			if(Architecture.isx86Device() && Architecture.is32BitsDevice()){
				String originalJNIDirectory = getApplicationInfo().nativeLibraryDir;
				getApplicationInfo().nativeLibraryDir = originalJNIDirectory.substring(0,
												originalJNIDirectory.lastIndexOf("/"))
												.concat("/x86");
			}
			if (isLauncherUiProcess(this)) {
				final long unpackDispatchStart = StartupProfiler.beginStage();
				AsyncAssetManager.unpackRuntime(getAssets());
				StartupProfiler.endStage("runtime_asset_unpack_dispatch", unpackDispatchStart);
			}
			StartupProfiler.endStage("application_on_create", appInitStart, isLauncherUiProcess(this) ? "launcher" : "worker");
		} catch (Throwable throwable) {
			Intent ferrorIntent = new Intent(this, FatalErrorActivity.class);
			ferrorIntent.putExtra("throwable", throwable);
			ferrorIntent.setFlags(FLAG_ACTIVITY_NEW_TASK);
			startActivity(ferrorIntent);
		}
	}

	@Override
	public void onTerminate() {
		super.onTerminate();
		ContextExecutor.clearApplication();
	}

	@Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LocaleUtils.setLocale(base));
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        LocaleUtils.setLocale(this);
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        com.star.launcher.ui.components.AetherImageCache.trimMemory(level >= TRIM_MEMORY_RUNNING_LOW);
    }
}
