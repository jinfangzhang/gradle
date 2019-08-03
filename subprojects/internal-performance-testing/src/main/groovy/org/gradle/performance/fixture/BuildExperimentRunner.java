/*
 * Copyright 2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.performance.fixture;

import com.google.common.collect.MapDifference.ValueDifference;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.apache.commons.lang.StringUtils;
import org.gradle.api.Action;
import org.gradle.internal.concurrent.CompositeStoppable;
import org.gradle.internal.os.OperatingSystem;
import org.gradle.performance.measure.MeasuredOperation;
import org.gradle.performance.results.MeasuredOperationList;
import org.gradle.util.GFileUtils;

import java.io.File;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;
import static org.gradle.performance.fixture.DurationMeasurementImpl.executeProcess;
import static org.gradle.performance.fixture.DurationMeasurementImpl.printProcess;
import static org.gradle.performance.fixture.DurationMeasurementImpl.sync;

public class BuildExperimentRunner {

    private final GradleSessionProvider executerProvider;
    private final Profiler profiler;

    public enum Phase {
        WARMUP,
        MEASUREMENT
    }

    public BuildExperimentRunner(GradleSessionProvider executerProvider) {
        this.executerProvider = executerProvider;
        profiler = Profiler.create();
    }

    public Profiler getProfiler() {
        return profiler;
    }

    public void run(BuildExperimentSpec experiment, MeasuredOperationList results) {
        System.out.println();
        System.out.println(String.format("%s ...", experiment.getDisplayName()));
        System.out.println();

        InvocationSpec invocationSpec = experiment.getInvocation();
        File workingDirectory = invocationSpec.getWorkingDirectory();
        workingDirectory.mkdirs();
        copyTemplateTo(experiment, workingDirectory);

        if (experiment.getListener() != null) {
            experiment.getListener().beforeExperiment(experiment, workingDirectory);
        }

        if (invocationSpec instanceof GradleInvocationSpec) {
            GradleInvocationSpec invocation = (GradleInvocationSpec) invocationSpec;
            final List<String> additionalJvmOpts = profiler.getAdditionalJvmOpts(experiment);
            final List<String> additionalArgs = new ArrayList<String>(profiler.getAdditionalGradleArgs(experiment));
            additionalArgs.add("-PbuildExperimentDisplayName=" + experiment.getDisplayName());

            GradleInvocationSpec buildSpec = invocation.withAdditionalJvmOpts(additionalJvmOpts).withAdditionalArgs(additionalArgs);
            GradleSession session = executerProvider.session(buildSpec);
            session.prepare();

            String previousMinFreeKbytes = executeProcess("cat /proc/sys/vm/min_free_kbytes");
            System.out.println("previousMinFreeKbytes = " + previousMinFreeKbytes);

            try {
                beforeIterations();
                performMeasurements(session, experiment, results, workingDirectory);
            } finally {
                afterIterations(previousMinFreeKbytes);
                CompositeStoppable.stoppable(profiler).stop();
                session.cleanup();
            }
        }
    }

    private void copyTemplateTo(BuildExperimentSpec experiment, File workingDir) {
        File templateDir = new TestProjectLocator().findProjectDir(experiment.getProjectName());
        GFileUtils.cleanDirectory(workingDir);
        GFileUtils.copyDirectory(templateDir, workingDir);
    }

    protected void performMeasurements(InvocationExecutorProvider session, BuildExperimentSpec experiment, MeasuredOperationList results, File projectDir) {
        doWarmup(experiment, projectDir, session);
        profiler.start(experiment);
        doMeasure(experiment, results, projectDir, session);
        profiler.stop(experiment);
    }

    // TODO move to string utils
    private static final Pattern NEW_LINE_PATTERN = Pattern.compile("\n");

    public static Stream<String> splitLines(String values) {
        return NEW_LINE_PATTERN.splitAsStream(values);
    }

    private void doMeasure(BuildExperimentSpec experiment, MeasuredOperationList results, File projectDir, InvocationExecutorProvider session) {
        Map<String, String> previousAllProcessIds = allProcesses();
        Set<String> previousProcessIds = gradleProcessesIds();

        int invocationCount = invocationsForExperiment(experiment);
        for (int i = 0; i < invocationCount; i++) {
            System.out.println();
            System.out.println(String.format("Test run #%s", i + 1));

            killLeftoverGradleProcesses(previousProcessIds);

            displayInfo(previousAllProcessIds);

            BuildExperimentInvocationInfo info = new DefaultBuildExperimentInvocationInfo(experiment, projectDir, Phase.MEASUREMENT, i + 1, invocationCount);
            runOnce(session, results, info);
        }
    }

    private static void killLeftoverGradleProcesses(Set<String> previousGradleProcessIds) {
        Set<String> leftoverGradleProcessIds = Sets.difference(gradleProcessesIds(), previousGradleProcessIds);
        if (!leftoverGradleProcessIds.isEmpty()) {
//            for (String leftoverGradleProcessId : leftoverGradleProcessIds) {
//                printProcess("Killing leftover Gradle processes: " + leftoverGradleProcessId, "ps -eu --pid " + leftoverGradleProcessId);
//            }

            executeProcess("kill -9 " + StringUtils.join(leftoverGradleProcessIds, " "));
        }
    }

    private static Set<String> gradleProcessesIds() {
        return splitLines(executeProcess("ps aux | egrep '[Gg]radle' | awk '{print $2}'"))
            .collect(toSet());
    }

    @SuppressWarnings("unchecked")
    protected InvocationCustomizer createInvocationCustomizer(final BuildExperimentInvocationInfo info) {
        if (info.getBuildExperimentSpec() instanceof GradleBuildExperimentSpec) {
            return new InvocationCustomizer() {
                @Override
                public <T extends InvocationSpec> T customize(BuildExperimentInvocationInfo info, T invocationSpec) {
                    final List<String> iterationInfoArguments = createIterationInfoArguments(info.getPhase(), info.getIterationNumber(), info.getIterationMax());
                    GradleInvocationSpec gradleInvocationSpec = ((GradleInvocationSpec) invocationSpec).withAdditionalArgs(iterationInfoArguments);
                    System.out.println("Run Gradle using JVM opts: " + gradleInvocationSpec.getJvmOpts());
                    if (info.getBuildExperimentSpec().getInvocationCustomizer() != null) {
                        gradleInvocationSpec = info.getBuildExperimentSpec().getInvocationCustomizer().customize(info, gradleInvocationSpec);
                    }
                    return (T) gradleInvocationSpec;
                }
            };
        }
        return null;
    }

    private void doWarmup(BuildExperimentSpec experiment, File projectDir, InvocationExecutorProvider session) {
        int warmUpCount = warmupsForExperiment(experiment);
        for (int i = 0; i < warmUpCount; i++) {
            System.out.println();
            System.out.println(String.format("Warm-up #%s", i + 1));
            BuildExperimentInvocationInfo info = new DefaultBuildExperimentInvocationInfo(experiment, projectDir, Phase.WARMUP, i + 1, warmUpCount);
            runOnce(session, new MeasuredOperationList(), info);
        }
    }

    private static void beforeIterations() {
        if (!OperatingSystem.current().isLinux()) {
            return;
        }
        printProcess("Turning swap off", "sudo swapoff --all --verbose"); // Disable devices and files for paging and swapping.
        printProcess("Turning dockerd off", "sudo systemctl stop docker");

        runGcOnAllJvms();
        setOSSchedulerStates(false);
        dropFileCaches();

        System.out.println(executeProcess("sudo sysctl vm.min_free_kbytes=1000000"));
    }

    /**
     * Trigger a garbage collection and a finalization event for every running java virtual machine
     */
    private static void runGcOnAllJvms() {
        printProcess("Run GC on all JVMs", "pgrep java | xargs -I{} sh -c 'jcmd {} GC.run; jcmd {} GC.run_finalization'");
    }

    /**
     * Show the currently running services, CPU speeds, temperatures, free space, etc.
     */
    private static void displayInfo(Map<String, String> previousAllProcessIds) {
        printAllChangedProcesses(previousAllProcessIds);

        printProcess("CPU temperatures", "sensors | grep 'Core ' | awk '{print $3}' | xargs");
        printProcess("CPU speed", " lscpu | grep 'CPU MHz:' | awk '{print $3 \" Mhz\"}'");
        printProcess("Used memory", "awk '/MemFree/ { printf \"%.3f Gb\\n\", $2/1024/1024 }' /proc/meminfo");
        printProcess("Disk space", "df --human | awk '{print $1 \" \" $4}' | xargs");
        printProcess("Running service count", "systemctl | grep 'running' | awk '{print $1}' | wc --lines");
        printProcess("Gradle process count", "ps aux | egrep '[Gg]radle' | wc --lines");
        printProcess("Temp directory gradle file count", "find /tmp -type f -name '*gradle*' 2>/dev/null | wc --lines");
    }

    /**
     * Clear PageCache, dentries and inodes.
     */
    private static void dropFileCaches() {
        System.out.println("Dropping file caches");
        sync();
        executeProcess("sudo sysctl vm.drop_caches=3");
    }

    private static void afterIterations(String previousMinFreeKbytes) {
        if (!OperatingSystem.current().isLinux()) {
            return;
        }

        setOSSchedulerStates(true);
        System.out.println(executeProcess(String.format("sudo sysctl vm.min_free_kbytes=%s", previousMinFreeKbytes)));
        printProcess("Turning swap on", "sudo swapon --all --verbose"); // Disable devices and files for paging and swapping.
        printProcess("Turning dockerd on", "sudo systemctl start docker");
    }

    /**
     * Temporarily disable cron and atd to make sure nothing unexpected happens during benchmarking.
     * See: https://github.com/softdevteam/krun#step-4-audit-system-services
     */
    private static void setOSSchedulerStates(boolean enabled) {
        String command = enabled ? "start" : "stop";
        System.out.println(String.format("Cron & Atd will %s now.", command));
        executeProcess(String.format("sudo systemctl %s cron", command)); // daemon to execute scheduled commands
        executeProcess(String.format("sudo systemctl %s atd", command)); // run jobs queued for later execution
    }

    private static void printAllChangedProcesses(Map<String, String> previousAllProcesses) {
        Collection<ValueDifference<String>> difference = Maps.difference(allProcesses(), previousAllProcesses).entriesDiffering().values();
        if (!difference.isEmpty()) {
            System.out.println("The following processes were just changed: ");
            for (ValueDifference<String> diff : difference) {
                if (diff.leftValue() != null) {
                    System.out.println(String.format("+ '%s'", diff.leftValue()));
                }
                if (diff.rightValue() != null) {
                    System.out.println(String.format("- '%s'", diff.rightValue()));
                }
            }
        }
    }

    private static Map<String, String> allProcesses() {
        return splitLines(executeProcess("ps -eo pid,command"))
            .map(String::trim)
            .map(line -> {
                int splitIndex = line.indexOf(' ');
                return new AbstractMap.SimpleImmutableEntry<>(line.substring(0, splitIndex), line.substring(splitIndex).trim());
            })
            .collect(toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private static String getExperimentOverride(String key) {
        String value = System.getProperty("org.gradle.performance.execution." + key);
        if (value != null && !"defaults".equals(value)) {
            return value;
        }
        return null;
    }

    protected Integer invocationsForExperiment(BuildExperimentSpec experiment) {
        String overriddenInvocationCount = getExperimentOverride("runs");
        if (overriddenInvocationCount != null) {
            return Integer.valueOf(overriddenInvocationCount);
        }
        if (experiment.getInvocationCount() != null) {
            return experiment.getInvocationCount();
        }
        return 40;
    }

    protected int warmupsForExperiment(BuildExperimentSpec experiment) {
        String overriddenWarmUpCount = getExperimentOverride("warmups");
        if (overriddenWarmUpCount != null) {
            return Integer.valueOf(overriddenWarmUpCount);
        }
        if (experiment.getWarmUpCount() != null) {
            return experiment.getWarmUpCount();
        }
        if (usesDaemon(experiment)) {
            return 10;
        } else {
            return 1;
        }
    }

    private boolean usesDaemon(BuildExperimentSpec experiment) {
        InvocationSpec invocation = experiment.getInvocation();
        if (invocation instanceof GradleInvocationSpec) {
            if (((GradleInvocationSpec) invocation).getBuildWillRunInDaemon()) {
                return true;
            }
        }
        return false;
    }

    protected void runOnce(
        final InvocationExecutorProvider session,
        final MeasuredOperationList results,
        final BuildExperimentInvocationInfo invocationInfo) {
        BuildExperimentSpec experiment = invocationInfo.getBuildExperimentSpec();
        final Action<MeasuredOperation> runner = session.runner(invocationInfo, wrapInvocationCustomizer(invocationInfo, createInvocationCustomizer(invocationInfo)));

        if (experiment.getListener() != null) {
            experiment.getListener().beforeInvocation(invocationInfo);
        }

        MeasuredOperation operation = new MeasuredOperation();
        runner.execute(operation);

        final AtomicBoolean omitMeasurement = new AtomicBoolean();
        if (experiment.getListener() != null) {
            experiment.getListener().afterInvocation(invocationInfo, operation, new BuildExperimentListener.MeasurementCallback() {
                @Override
                public void omitMeasurement() {
                    omitMeasurement.set(true);
                }
            });
        }

        if (!omitMeasurement.get()) {
            results.add(operation);
        }
    }

    private InvocationCustomizer wrapInvocationCustomizer(BuildExperimentInvocationInfo invocationInfo, final InvocationCustomizer invocationCustomizer) {
        final InvocationCustomizer experimentSpecificCustomizer = invocationInfo.getBuildExperimentSpec().getInvocationCustomizer();
        if (experimentSpecificCustomizer != null) {
            if (invocationCustomizer != null) {
                return new InvocationCustomizer() {
                    @Override
                    public <T extends InvocationSpec> T customize(BuildExperimentInvocationInfo info, T invocationSpec) {
                        return experimentSpecificCustomizer.customize(info, invocationCustomizer.customize(info, invocationSpec));
                    }
                };
            } else {
                return experimentSpecificCustomizer;
            }
        } else {
            return invocationCustomizer;
        }
    }

    protected List<String> createIterationInfoArguments(Phase phase, int iterationNumber, int iterationMax) {
        List<String> args = new ArrayList<String>(3);
        args.add("-PbuildExperimentPhase=" + phase.toString().toLowerCase());
        args.add("-PbuildExperimentIterationNumber=" + iterationNumber);
        args.add("-PbuildExperimentIterationMax=" + iterationMax);
        return args;
    }
}
