package com.github.hvbargen.dir_watcher_pmd;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;
import static java.nio.file.StandardWatchEventKinds.OVERFLOW;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.WatchEvent;
import java.nio.file.WatchEvent.Kind;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import net.sourceforge.pmd.PMDConfiguration;
import net.sourceforge.pmd.PmdAnalysis;
import net.sourceforge.pmd.lang.LanguageRegistry;
import net.sourceforge.pmd.lang.document.FileId;
import net.sourceforge.pmd.lang.rule.RulePriority;
import net.sourceforge.pmd.reporting.Report;

class DirWatcherPMD {

    @SuppressWarnings("unchecked")
    static WatchEvent<Path> cast(WatchEvent<?> event) {
        return (WatchEvent<Path>)event;
    }

    List<String> languages = new ArrayList<String>();
    List<String> rulesets = new ArrayList<String>();
    List<Path> sources = new ArrayList<Path>();
    List<FileId> filesWithViolations = new ArrayList<FileId>();
    List<String> excludes = null;
    List<String> includes = null;

    List<PathMatcher> excludeMatchers = null;
    List<PathMatcher> includeMatchers = null;

    /**
     * We use this variable to count changes in the watched directories.
     * Whenever a change occurs, this variable is incremented.
     * The runPMD() method checks this variable. Analysis only starts
     * when the variable has not further incremented within a delay of 0.2 seconds.
     * If the variable is incremented WHILE the analysis is running, then
     * it starts anew.
     * To achieve this behavior, the directory sets an Event if there are changes
     * and the Event is not already set.
     * The second program thread waits for this Event.
     * Once it is notified about this event, it reads changeCounter and stores
     * this value in a comparison variable, then resets the Event.
     * Then it waits for a fraction of second.
     * After this delay, it reads changeCounter again and compares this to the
     * previously stored value.
     * If the values are equal, then it means there have not been any changes,
     * so the analysis should be valid. It then starts the analysis.
     * Otherwise, the analysis will probably invalid, thus it is delayed further.
     */
    private volatile int changeCounter = 0;

    // Java equivalent to Python Event class,
    // see https://stackoverflow.com/questions/1040818/python-event-equivalent-in-java
    // However, we use an IF instead of WHILE in doWait with timeout.
    private static class Event {
        Lock lock = new ReentrantLock();
        Condition cond = lock.newCondition();
        boolean flag;

        public void doWait() throws InterruptedException {
            lock.lock();
            try {
                while (!flag) {
                    cond.await();
                }
            } finally {
                lock.unlock();
            }
        }
        public void doWait(int milliseconds) throws InterruptedException {
            lock.lock();
            try {
                if (!flag) {
                    cond.await(milliseconds, TimeUnit.MILLISECONDS);
                }


            } finally {
                lock.unlock();
            }
        }

        public boolean isSet() {
            lock.lock();
            try {
                return flag;
            } finally {
                lock.unlock();
            }
        }

        public void set() {
            lock.lock();
            try {
                flag = true;
                cond.signalAll();
            } finally {
                lock.unlock();
            }
        }

        public void clear() {
            lock.lock();
            try {
                flag = false;
                cond.signalAll();
            } finally {
                lock.unlock();
            }
        }
    }    

    private static enum ArgMode {
        None, language, ruleset, source, exclude, include
    }

    Event event = new Event();

    DirWatcherPMD(final String[] args) {
        // Parse args
        ArgMode mode = ArgMode.None;
        for (int i = 0; i < args.length; i++) {
            final String arg = args[i];
            if (arg.startsWith("--")) {
                mode = ArgMode.valueOf(arg.substring(2));
            } else {
                switch (mode) {
                case None:
                    System.err.println("All arguments must be introduced with an argument name like --source");
                    break;
                case language:
                    languages.add(arg);
                    break;
                case ruleset:
                    rulesets.add(arg);
                    break;
                case source:
                    sources.add(Path.of(arg));
                    break;
                case exclude:
                    if (excludes == null) {
                        excludes = new ArrayList<String>();
                    }
                    excludes.add(arg);
                case include:
                    if (includes == null) {
                        includes = new ArrayList<String>();
                    }
                    includes.add(arg);
                }
            }
        }
        if (sources.isEmpty()) {
            System.err.println("No sources specified. At least one --source must be specified.");
            System.exit(1);
        }
        if (rulesets.isEmpty()) {
            System.err.println("No rulesets specified. At least one --ruleset must be specified.");
            System.exit(1);
        }
        if (languages.isEmpty()) {
            System.err.println("No languages specified. At least one --language must be specified.");
            System.exit(1);
        }
        if (excludes == null) {
            // If ont specified on command line, use default.
            excludes = List.of(".*", "*.log");
        }
        excludeMatchers = new ArrayList<PathMatcher>(excludes.size());
        for (var pattern: excludes) {
            excludeMatchers.add(FileSystems.getDefault().getPathMatcher("glob:" + pattern));
        }
        if (includes == null) {
            // If ont specified on command line, use default.
            includes = List.of("*.{sql,pkb,pks,pck,vw,typ,fnc,prc}");
        }
        includeMatchers = new ArrayList<PathMatcher>(includes.size());
        for (var pattern: includes) {
            includeMatchers.add(FileSystems.getDefault().getPathMatcher("glob:" + pattern));
        }
    }

    private PMDConfiguration configure() {
        PMDConfiguration config = new PMDConfiguration(LanguageRegistry.PMD);
        for (String language : languages) {
            config.setDefaultLanguageVersion(LanguageRegistry.PMD.getLanguageById(language).getDefaultVersion());
        }
        for (String ruleset : rulesets) {
            config.addRuleSet(ruleset);
        }
        // config.prependAuxClasspath("target/classes");
        // config.setMinimumPriority(RulePriority.MEDIUM_LOW);
        // config.setReportFormat("text");
        // config.setReportFile(Paths.get("target/pmd-report.xml"));
        config.setAnalysisCacheLocation(".pmdcache");

        for (Path source : sources) {
            if (source.toFile().isDirectory()) {
                for (var file: source.toFile().listFiles()) {
                    Path p = file.toPath();
                    if (!canIgnore(p)) {
                        config.addInputPath(p);
                    } 
                }
            } else {
                config.addInputPath(source);
            }
        }
        return config;
    }

    private boolean canIgnore(Path path) {
        final Path fPath = path.getFileName();
        for (var matcher: excludeMatchers) {
            if (matcher.matches(fPath)) {
                return true;
            }
        }
        for (var matcher: includeMatchers) {
            if (matcher.matches(fPath)) {
                return false;
            }
        }
        return true;
    }

    private void watch() {

        Path path = sources.get(0);

        // Sanity check - Check if path is a folder
        try {
            Boolean isFolder = (Boolean) Files.getAttribute(path, "basic:isDirectory", NOFOLLOW_LINKS);
            if (!isFolder) {
                throw new IllegalArgumentException("Path: " + path + " is not a folder");
            }
        } catch (IOException ioe) {
            // Folder does not exists
            ioe.printStackTrace();
        }

        // We obtain the file system of the Path
        FileSystem fs = path.getFileSystem();

        // We create the new WatchService using the new try() block
        try (WatchService service = fs.newWatchService()) {

            // We register the path to the service
            // We watch for creation events
            path.register(service, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE);

            // Start the infinite polling loop
            WatchKey key = null;
            while (true) {
                key = service.take();
                boolean isRelevant = false;
                // Dequeueing events
                Kind<?> kind = null;
                for (WatchEvent<?> watchEvent : key.pollEvents()) {
                    // Get the type of the event
                    kind = watchEvent.kind();
                    if (OVERFLOW == kind) {
                        continue; // loop
                    } else if (ENTRY_CREATE == kind) {
                        // A new Path was created
                        Path newPath = cast(watchEvent).context();
                        // Output
                        System.out.println("DBG New path created: " + newPath);
                        // This does not work recursively:
                        // newPath.register(service, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE);
                        if (!canIgnore(newPath)) {
                            isRelevant = true;
                        }
                    } else if (ENTRY_MODIFY == kind) {
                        // modified
                        Path newPath = cast(watchEvent).context();
                        // Output
                        System.out.println("DBG New path modified: " + newPath);
                        if (!canIgnore(newPath)) {
                            isRelevant = true;
                        }
                    } else if (ENTRY_DELETE == kind) {
                        Path deletedPath = cast(watchEvent).context();
                        System.out.println("DBG New path deleted: " + deletedPath);
                    }
                }

                if (!key.reset()) {
                    break; // loop
                }

                // Notify the PMD thread
                if (isRelevant) {
                    event.set();
                }
            }

        } catch (IOException ioe) {
            ioe.printStackTrace();
        } catch (InterruptedException ie) {
            ie.printStackTrace();
        }

    }

    // This runs as a background thread
    private void pmdInBackground()  {
        try {
            int lastCounter = 0;
            while (true) {
                // Wait for an event
                event.doWait();
                int counter = changeCounter;
                event.clear();
                while (true) {
                    try {
                        event.doWait(200);
                        if (!event.isSet()) {
                            break;
                        }
                        // A new event while we waited, so keep on waiting
                        counter = changeCounter;
                        event.clear();
                    } catch (InterruptedException e) {
                        break;
                    }
                }
                // immerhin bis jetzt unverändert.
                lastCounter = counter;
                runPMD();
                if (lastCounter != counter) {
                    // Es hat in der Zwischenzeit Veränderungen gegeben.
                    event.set();
                }
            }
        } catch (InterruptedException e) {
            System.out.println("INFO Background thread interrupted.");
        }
    }

    private String mapPriority(RulePriority priority) {
        final String ret;
        switch(priority) {
        case HIGH:
            ret = "FATAL";
            break;
        case MEDIUM_HIGH:
            ret = "ERROR";
            break;
        case MEDIUM:
            ret = "WARNING";
            break;
        case MEDIUM_LOW:
            ret = "WARNING";
            break;
        case LOW:
            ret = "INFO";
            break;
        default:
            ret = "ERROR";
        }
        return ret;
    }

    private void runPMD() {

        List<FileId> newFilesWithViolations = new ArrayList<FileId>();
        PMDConfiguration config = configure();
        System.out.println("BEGIN-SCAN");
        try (PmdAnalysis pmd = PmdAnalysis.create(config)) {
            Report report = pmd.performAnalysisAndCollectReport();
            FileId oldFileId = null;
            for (var violation : report.getViolations()) {
                if (!violation.getFileId().equals(oldFileId)) {
                    // if (oldFileId != null) {
                    //     System.out.println("END-ANALYSIS");
                    // }
                    oldFileId = violation.getFileId();
                    newFilesWithViolations.add(violation.getFileId());
                    // System.out.println("BEGIN-ANALYSIS");
                    System.out.println("Findings in " + oldFileId.getOriginalPath() + ":");
                }
                System.out.println("MSG " +
                        violation.getBeginLine() + ":" + violation.getBeginColumn() +
                        " to " + violation.getEndLine() + ":" + violation.getEndColumn() +
                        " " + mapPriority(violation.getRule().getPriority()) +
                        " [" + violation.getRule().getName() + "] " + violation.getDescription());
            }
            // if (oldFileId != null) {
            //     System.out.println("END-ANALYSIS");
            // }
            for (FileId fid: filesWithViolations) {
                if (!newFilesWithViolations.contains(fid)) {
                    System.out.println("BEGIN-ANALYSIS");
                    System.out.println("Findings in " + fid.getOriginalPath() + ":");
                    System.out.println("END-ANALYSIS");
                }
            }
            filesWithViolations = newFilesWithViolations; 
        }
        finally {
            System.out.println("END-SCAN");
        }
    }

    
    public static void main(final String[] args) {
        var watcher = new DirWatcherPMD(args);
        System.out.println("STARTUP Watching directories: " + watcher.sources.toString());
        watcher.runPMD();
        Thread background = new Thread(() -> { watcher.pmdInBackground(); } );
        background.setDaemon(true);
        background.start();
        watcher.watch();
        System.out.println("SHUTDOWN Thanks for using this program.");
    }
}