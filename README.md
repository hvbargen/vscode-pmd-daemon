# vscode-pmd-daemon

## Current state of development

This is work in progress.

**It does not yet work as described below!**

## Purpose

This is a little Java program that can run in the background.

It watches a source directory and uses PMD for static code analysis.

This happens automatically.

As soon as a file changes (is saved in the editor), the program runs the code analysis again.

The output is (unless explicitly configured otherwise) to STDOUT, in a format that enables the use 
of a multi-line problem matcher with Visual Studio Code.

The language, the watched folder and the rulesets can be specified on the command line.

In comparison to running PMD on the command-line, this should be significantly faster,
because the overhead of starting the JVM and loading the rules happens only once.

## Usage on the command line

The program needs at least the following options:

    --language plsql   One of the languages which PMD support
    --ruleset  resource ... 
    --source   dirOrFile ... dirOrFile

The resource rulesets are searched in the Java classpath.
A typical example would be `category/plsql/bestpractices.xml`.
If you want to use your own XML files, you have to use a file-URL.
One or more ruleset paths must be specified.

The sources can be files or directories.
Directories are NOT scanned recursively.

Additional options that may be added later:

    --excludelist textfile   A text file containing filename patterns which shuld be ignored
    --includelist textfile   A text file containing filename patterns which should be considered.
      
To stop the program, hit `Ctrl-C` on Windows.

### Example

    java -cp pmd-core*.jar pmd-plsql*.jar -jar vscode-pmd-daemon.jar -Dlog4j.configuration=file:log4j.properties --language plsql --ruleset category/plsql/bestpractices.xml --source c:\my_workspace\ddl

## Output format

The program emits the following text lines while working.
The first word in each line can be used to detect the type of the line.

At program startup:

    STARTUP message

At beginning of scan:

    BEGIN-SCAN
    
At the beginning of the examination a new or changed file:

    Findings in filename:

For each rule violation in the file:

    MSG begin-line:begin-column to end-line:end-column severity [rule-name] violation description

At the end of scan:

    END-SCAN

At program shutdown:

    SHUTDOWN
    
    

## Logging

By creating a file `log4j.properties`, one can debug what happens.
Output on STDOUT is only meant as input for a problem matcher.

## Caveats

The program is meant to watch only local directories.
It is not tested with network directories.

At the moment, the program only supports the `plsql` language,
but this is only because it uses a shrinked binary distribution of PMD.
