package com.github.hvbargen.dir_watcher_pmd;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;
import static java.nio.file.StandardWatchEventKinds.OVERFLOW;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchEvent.Kind;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.List;
// import java.util.Scanner;

import net.sourceforge.pmd.PMDConfiguration;
import net.sourceforge.pmd.PmdAnalysis;
import net.sourceforge.pmd.lang.LanguageRegistry;
import net.sourceforge.pmd.lang.document.FileId;
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

    private static enum ArgMode {
        None, language, ruleset, source
    }

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

    }

    private PMDConfiguration configure() {
        PMDConfiguration config = new PMDConfiguration(LanguageRegistry.PMD);
        for (String language : languages) {
            config.setDefaultLanguageVersion(LanguageRegistry.PMD.getLanguageById(language).getDefaultVersion());
        }
        for (Path source : sources) {
            config.addInputPath(source);
        }
        for (String ruleset : rulesets) {
            config.addRuleSet(ruleset);
        }
        // config.prependAuxClasspath("target/classes");
        // config.setMinimumPriority(RulePriority.MEDIUM_LOW);
        // config.setReportFormat("text");
        // config.setReportFile(Paths.get("target/pmd-report.xml"));
        config.setAnalysisCacheLocation(".pmdcache");
        return config;
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

        System.out.println("Watching path: " + path);

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
                        System.out.println("New path created: " + newPath);
                        // This does not work recursively:
                        // newPath.register(service, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE);
                    } else if (ENTRY_MODIFY == kind) {
                        // modified
                        Path newPath = cast(watchEvent).context();
                        // Output
                        System.out.println("New path modified: " + newPath);
                    } else if (ENTRY_DELETE == kind) {
                        Path deletedPath = cast(watchEvent).context();
                        System.out.println("New path deleted: " + deletedPath);
                    }
                }

                if (!key.reset()) {
                    break; // loop
                }

                runPMD();
            }

        } catch (IOException ioe) {
            ioe.printStackTrace();
        } catch (InterruptedException ie) {
            ie.printStackTrace();
        }

    }

    private void runPMD() {

        List<FileId> newFilesWithViolations = new ArrayList<FileId>();
        PMDConfiguration config = configure();
        try (PmdAnalysis pmd = PmdAnalysis.create(config)) {
            // System.out.println("Files: " + pmd.files());
            Report report = pmd.performAnalysisAndCollectReport();
            FileId oldFileId = null;
            for (var violation : report.getViolations()) {
                if (!violation.getFileId().equals(oldFileId)) {
                    if (oldFileId != null) {
                        System.out.println("END-ANALYSIS " + oldFileId.getOriginalPath());
                    }
                    oldFileId = violation.getFileId();
                    newFilesWithViolations.add(violation.getFileId());
                    System.out.println("BEGIN-ANALYSIS " + oldFileId.getOriginalPath());
                }
                System.out.println("MSG " +
                        violation.getBeginLine() + ":" + violation.getBeginColumn() +
                        " to " + violation.getEndLine() + ":" + violation.getEndColumn() +
                        " " + violation.getRule().getPriority() +
                        " [" + violation.getRule().getName() + "] " + violation.getDescription());
                if (oldFileId != null) {
                    System.out.println("END-ANALYSIS " + oldFileId.getOriginalPath());
                }
            }
            for (FileId fid: filesWithViolations) {
                if (!newFilesWithViolations.contains(fid)) {
                    System.out.println("END-ANALYSIS " + fid.getOriginalPath());
                    System.out.println("END-ANALYSIS " + fid.getOriginalPath());
                }
            }
            filesWithViolations = newFilesWithViolations; 
        }
    }

    // void runRepeated() {

    //     PMDConfiguration config = configure();
    //     System.out.println("Press Enter to check source files, 'stop' to stop.");
    //     try (Scanner scanner = new Scanner(System.in)) {
    //         boolean stop = false;
    //         while (!stop) {
    //             String userInput = scanner.nextLine();
    //             if (userInput.equals("stop")) {
    //                 stop = true;
    //             } else {
    //                 try (PmdAnalysis pmd = PmdAnalysis.create(config)) {
    //                     // System.out.println("Files: " + pmd.files());
    //                     Report report = pmd.performAnalysisAndCollectReport();
    //                     System.out.println("Violations:");
    //                     for (var violation : report.getViolations()) {
    //                         System.out.println(
    //                                 violation.getLocation().startPosToStringWithFile() + " bis " + violation.getEndLine()
    //                                         + ":" + violation.getEndColumn() + " [" + violation.getRule().getName() + "]"
    //                                         + " " + violation.getRule().getPriority() + ": " + violation.getDescription());
    //                     }
    //                     System.out.println("End of report.");
    //                 }
    //             }
    //         }
    //     }
    // }

    public static void main(final String[] args) {
        //       new DirWatcherPMD(args).runRepeated();
        var watcher = new DirWatcherPMD(args);
        System.out.println("STARTUP Watching directories: " + watcher.sources.toString());
        watcher.runPMD();
        watcher.watch();
        System.out.println("SHUTDOWN Thanks for using this program.");
    }
}