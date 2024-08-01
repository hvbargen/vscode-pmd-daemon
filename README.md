# vscode-pmd-daemon

## Current state of development

This is work in progress.
At the moment, consider it a proof of concept.
I have only tested it at work once (for a directory witth hundreds of Oracle PL/SQL packages).

The tool is intended for *developers* who know a little bit about Java and PMD and who develop PL/SQL.

There are other, more sophisticated PMD integrations for VS Code available.

However, AFAIK this is developed as one little Java program (I don't know TS good enough) and it avoids
the process creation and Java initialization overhead.
It uses VS Codes built-in "Task Matcher" and "Tasks" technique which allows to run any program as a 
linter or compiler or whatever. 

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

Additional options:

    --exclude pattern ...   glob patterns of file names to be excluded from analysis.
    --include pattern ...   glob patterns of file names to be included.

A file is only considedered if it does not match any of the exclude patterns
and if it matches one of the include patterns.

If you omit the exclude and include patterns, the program uses the following defaults:

   -- exclude ".*" "*.log"
   -- include "*.{sql,pkb,pks,pck,vw,typ,fnc,prc}"
      
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


## Compiling

Compiling it with Maven should be straightforward.
I don't supply binaries.

## Using it with Visual Studio Code

Take a look at `src\plsql`.
Open this folder in Visual Studio Code.
Probably you'll have to adapt some paths in the file `.vscode/tasks.json` there.

Now, when you open the folder next time with VS Code, PMD will run in the background.
It will (more or less instantly) inform you about "when others then null" findings in your PL/SQL code,
once at start and then whenever there are changes in the directory (eg. when you edit and change a file in VS Code).

Of course you can and should use your own rulesets.


