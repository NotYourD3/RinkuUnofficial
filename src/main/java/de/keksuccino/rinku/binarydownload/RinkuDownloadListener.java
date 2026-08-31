package de.keksuccino.rinku.binarydownload;

import net.minecraft.util.IChatComponent;
import java.util.Objects;

public class RinkuDownloadListener {

    public static final RinkuDownloadListener INSTANCE = new RinkuDownloadListener();

    private volatile IChatComponent task = null;
    private volatile float percent;
    private volatile boolean done;
    private volatile boolean failed;

    private RinkuDownloadListener() {}

    public void setTask(IChatComponent task) {
        this.task = Objects.requireNonNull(task, "Downloader task must not be null");
        this.percent = 0;
    }

    public IChatComponent getTask() {
        return task;
    }

    public void setProgress(float percent) {
        this.percent = percent;
    }

    public float getProgress() {
        return percent;
    }

    public void setDone(boolean done) {
        this.done = done;
    }

    public boolean isDone() {
        return done;
    }

    public void setFailed(boolean failed) {
        this.failed = failed;
    }

    public boolean isFailed() {
        return failed;
    }

}
