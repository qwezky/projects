package dev.example.dshclient.ui;

import java.io.File;
import java.util.Objects;

/** 下载行数据；回调为空时对应图标以禁用态显示。 */
public final class DownloadRow {
    public final String name;
    public final String status;
    public final int percent;
    public final File path;
    public final boolean done;
    public final Runnable onOpen;
    public final Runnable onFolder;
    /** 删除回调：删磁盘文件并移除这条记录；null = 不允许删。 */
    public final Runnable onDelete;

    public DownloadRow(String name, String status, int percent, File path, boolean done,
            Runnable onOpen, Runnable onFolder, Runnable onDelete) {
        this.name = Objects.requireNonNull(name, "name");
        this.status = Objects.requireNonNull(status, "status");
        this.percent = Math.max(0, Math.min(100, percent));
        this.path = Objects.requireNonNull(path, "path");
        this.done = done;
        this.onOpen = onOpen;
        this.onFolder = onFolder;
        this.onDelete = onDelete;
    }

    public DownloadRow(String name, String status, int percent, File path, boolean done,
            Runnable onOpen, Runnable onFolder) {
        this(name, status, percent, path, done, onOpen, onFolder, null);
    }

    public DownloadRow(String name, String status, int percent, File path, boolean done) {
        this(name, status, percent, path, done, null, null, null);
    }
}
