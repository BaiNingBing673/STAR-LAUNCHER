package net.kdt.pojavlaunch;

import static net.kdt.pojavlaunch.Tools.shareLog;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.Keep;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.star.launcher.ui.dashboard.AetherDashboardActivity;

@Keep
public class ExitActivity extends AppCompatActivity {
    private boolean mLaunchedShareChooser = false;
    private boolean mReturnedToAether = false;

    @SuppressLint("StringFormatInvalid") //invalid on some translations but valid on most, cant fix that atm
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int code = -1;
        boolean isSignal = false;
        Bundle extras = getIntent().getExtras();
        if(extras != null) {
            code = extras.getInt("code",-1);
            isSignal = extras.getBoolean("isSignal", false);
        }

        int message = isSignal ? R.string.mcn_signal_title : R.string.mcn_exit_title;

        new AlertDialog.Builder(this)
                .setMessage(getString(message,code))
                .setPositiveButton(R.string.main_share_logs, (dialog, which) -> {
                    mLaunchedShareChooser = true;
                    shareLog(this);
                })
                .setNegativeButton(android.R.string.ok, (dialog, which) -> returnToAether())
                .setOnDismissListener(dialog -> {
                    if (!mLaunchedShareChooser) {
                        returnToAether();
                    }
                })
                .show();
    }

    @Override
    protected void onRestart() {
        super.onRestart();
        if (mLaunchedShareChooser) {
            mLaunchedShareChooser = false;
            returnToAether();
        }
    }

    public void returnToAether() {
        if (mReturnedToAether || isFinishing() || isDestroyed()) return;
        mReturnedToAether = true;
        Intent intent = AetherDashboardActivity.createIntent(this);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        finish();
    }

    public static void showExitMessage(Context ctx, int code, boolean isSignal) {
        Intent i = new Intent(ctx,ExitActivity.class);
        i.putExtra("code",code);
        i.putExtra("isSignal", isSignal);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(i);
    }

}
