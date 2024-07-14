package com.github.hvbargen.dir_watcher_pmd;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Scanner;

import net.sourceforge.pmd.PmdAnalysis;
import net.sourceforge.pmd.PMDConfiguration;
import net.sourceforge.pmd.lang.LanguageRegistry;
import net.sourceforge.pmd.reporting.Report;

class DirWatcherPMD {

    DirWatcherPMD(final String[] args) {

    }

    private PMDConfiguration configure() {
        PMDConfiguration config = new PMDConfiguration(LanguageRegistry.PMD);
        config.setDefaultLanguageVersion(LanguageRegistry.PMD.getLanguageById("plsql").getDefaultVersion());
        config.addInputPath(Path.of("src/plsql/beispiel"));
        // config.prependAuxClasspath("target/classes");
        // config.setMinimumPriority(RulePriority.MEDIUM_LOW);
        config.addRuleSet("category/plsql/bestpractices.xml");
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
        for (int i = 0; i < args.length; i++) {
            System.out.println(args[i]);
        }
        new DirWatcherPMD(args).runRepeated();
    }
}