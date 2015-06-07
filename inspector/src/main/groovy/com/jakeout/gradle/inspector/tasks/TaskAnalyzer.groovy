package com.jakeout.gradle.inspector.tasks

import com.jakeout.gradle.inspector.InspectorConfig
import com.jakeout.gradle.inspector.tasks.model.TaskDiffResults
import com.jakeout.gradle.inspector.tasks.model.TaskExecutionResults
import com.jakeout.gradle.inspector.tasks.model.AnalysisResult
import com.jakeout.gradle.utils.DiffUtil
import com.zutubi.diff.Patch
import com.zutubi.diff.PatchFile
import com.zutubi.diff.unified.UnifiedHunk
import com.zutubi.diff.unified.UnifiedPatch
import org.apache.commons.io.FileUtils
import org.apache.commons.io.FilenameUtils
import org.gradle.api.Task
import org.gradle.api.file.FileCollection
import org.gradle.api.logging.Logging
import org.gradle.api.tasks.TaskState

class TaskAnalyzer {

    private final InspectorConfig config
    private final Task task
    private final long buildStarted

    public AnalysisResult results = null

    public TaskAnalyzer(InspectorConfig config, Task t, long buildStarted) {
        this.config = config
        this.task = t
        this.buildStarted = buildStarted
    }

    public AnalysisResult onAfterExecute(TaskState state) {
        try {
            TaskExecutionResults execution = getExecutionResults()

            Optional<PatchFile> patchFile = DiffUtil.diff(
                    config.projectBuildDir,
                    config.taskDir(task),
                    config.taskOut(task))

            this.results = new AnalysisResult(
                    executionResults: execution,
                    diffResults: evaluateDiff(execution, patchFile),
                    comparisonResults: getComparisonResults(execution))
        } catch (Exception e) {
            Logging.getLogger(TaskAnalyzer.class).error("Analyzer failed to diff task: " + task.name, e);
        } finally {
            // Don't clean up, e.g., if the user wants to preserve this data to compare against the next build.
            if (config.cleanUpEachTask) {
                FileUtils.deleteDirectory(config.taskDir(task))
            }
        }
    }

    private Optional<TaskDiffResults> getComparisonResults(TaskExecutionResults execution) {
        if (!config.compareBuild) {
            Optional.empty()
        } else {
            def compareTaskOut = config.compareTaskOut(task)
            Optional<PatchFile> comparePatchFile = DiffUtil.diff(config.compareTaskDir(task), config.taskDir(task), compareTaskOut)
            Optional.of(evaluateDiff(execution, comparePatchFile))
        }
    }

    private TaskExecutionResults getExecutionResults() {
        long endTime = System.currentTimeMillis()
        List<Task> dependsOnTasks = new LinkedList<Task>()
        task.getDependsOn().each { t ->
            if (t instanceof Task) {
                dependsOnTasks.add((Task) t)
            }
        }

        String tReportRel = config.taskReport(task)

        new TaskExecutionResults(
                name: task.name,
                path: tReportRel,
                dependsOnTasks: dependsOnTasks,
                task: task,
                startTime: buildStarted,
                endTime: endTime)
    }

    private static TaskDiffResults evaluateDiff(TaskExecutionResults executionResults, Optional<PatchFile> patch) {

        int filesTouched = 0
        int added = 0
        int removed = 0
        def changesByType = new HashMap<String, Integer>()

        boolean anyUndeclaredChanges = false
        if (patch.isPresent()) {
            List<Patch> patches = patch.get().getPatches()
            for (Patch p : patches) {
                filesTouched++

                Optional<String> rootDirOfDeclaredOutput = findOutput(p.newFile, executionResults.task.getOutputs().files)

                if (!rootDirOfDeclaredOutput.isPresent()) {
                    anyUndeclaredChanges = true
                }

                def extension = FilenameUtils.getExtension(p.newFile)

                Integer count = changesByType.get(extension)
                if (count == null) {
                    changesByType.put(extension, 1)
                } else {
                    changesByType.put(extension, count + 1)
                }

                if (p instanceof UnifiedPatch) {
                    UnifiedPatch up = (UnifiedPatch) p
                    for (UnifiedHunk h : up.getHunks()) {
                        for (UnifiedHunk.Line l : h.getLines()) {
                            switch (l.type) {
                                case UnifiedHunk.LineType.ADDED:
                                    added++
                                    break;
                                case UnifiedHunk.LineType.DELETED:
                                    removed++
                                    break;
                                case UnifiedHunk.LineType.COMMON:
                                    break;
                            }
                        }
                    }
                }
            }
        }

        new TaskDiffResults(
                filesTouched: filesTouched,
                hunksAdded: added,
                hunksRemoved: removed,
                anyUndeclaredChanges: anyUndeclaredChanges,
                changesByType: changesByType,
                patchFile: patch)
    }

    private static Optional<String> findOutput(String file, FileCollection files) {
        for (File f : files) {
            if (file.equals(f.absolutePath) || file.startsWith(f.absolutePath + '/')) {
                return Optional.of(f.getAbsolutePath());
            }
        }
        Optional.empty()
    }
}