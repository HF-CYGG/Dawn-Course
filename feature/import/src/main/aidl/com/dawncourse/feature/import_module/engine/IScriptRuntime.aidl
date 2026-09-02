package com.dawncourse.feature.import_module.engine;

import android.os.ParcelFileDescriptor;
import com.dawncourse.feature.import_module.engine.IScriptRuntimeCallback;

interface IScriptRuntime {
    int getProcessId();
    oneway void execute(
        in ParcelFileDescriptor request,
        in ParcelFileDescriptor response,
        in IScriptRuntimeCallback callback
    );
}
