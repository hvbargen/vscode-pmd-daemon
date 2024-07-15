package com.github.hvbargen.dir_watcher_pmd;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

import net.sourceforge.pmd.PmdAnalysis;
import net.sourceforge.pmd.PMDConfiguration;
import net.sourceforge.pmd.lang.LanguageRegistry;
import net.sourceforge.pmd.reporting.Report;

class DirWatcherPMD {

    List<String> languages = new ArrayList<String>();
    List<String> rulesets = new ArrayList<String>();
    List<Path> sources = new ArrayList<Path>();

    private static enum ArgMode {
        None,
        language,
        ruleset,
        source
    }

    DirWatcherPMD(final String[] args) {
        // Parse args
        ArgMode mode = ArgMode.None;
        for (int i=0; i<args.length; i++) {
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
        for (String language: languages) {
            config.setDefaultLanguageVersion(LanguageRegistry.PMD.getLanguageById(language).getDefaultVersion());
        }
        for (Path source: sources) {
            config.addInputPath(source);
        }
        for (String ruleset: rulesets) {
            config.addRuleSet(ruleset);
        }
        // config.prependAuxClasspath("target/classes");
        // config.setMinimumPriority(RulePriority.MEDIUM_LOW);
        //config.setReportFormat("text");
        //config.setReportFile(Paths.get("target/pmd-report.xml"));
        config.setAnalysisCacheLocation(".pmdcache");
        return config;
    }

    void runRepeated() {

        PMDConfiguration config = configure();
        System.out.println("Press Enter to check source files, 'stop' to stop.");
        Scanner scanner = new Scanner(System.in);
        boolean stop = false;
        while (!stop) {
            String userInput = scanner.nextLine();
            if (userInput.equals("stop")) {
                stop = true;
            } else {
                try (PmdAnalysis pmd = PmdAnalysis.create(config)) {
                    // System.out.println("Files: " + pmd.files());
                    Report report = pmd.performAnalysisAndCollectReport();
                    System.out.println("Violations:");
                    for (var violation: report.getViolations()) {
                        System.out.println(violation.getLocation().startPosToStringWithFile() 
                            + " bis " + violation.getEndLine() + ":" + violation.getEndColumn()
                            + " [" + violation.getRule().getName() + "]"
                            + " " + violation.getRule().getPriority()
                            + ": " + violation.getDescription());
                    }
                    System.out.println("End of report.");
                 }
            }
        }
    }

    public static void main(final String[] args) {
        new DirWatcherPMD(args).runRepeated();
    }
}