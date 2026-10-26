package com.chrauto.ai

import android.app.Application
import com.chrauto.ai.tasks.TaskRunner

class ChrautoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Loads the task queue, marks tasks interrupted by a killed process, and re-arms the next alarm.
        TaskRunner.init(this)
    }
}
