package dev.codeservergo;

import android.app.Application;

/** Installs the crash reporter before any activity starts. */
public class App extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        CrashLog.install(this);
    }
}
