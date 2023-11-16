# JLeaks: A Featured Resource Leak Repository Collected From Hundreds of Open-Source Java Projects

## General Introdution
JLeaks is a resource leaks repository collected from real-world projects which facilitates in-depth researches and evaluation of defect-related algorithms. Each defect in Leaks includes four aspects key information: project information, defect information, code characteristics, and file information. 

## Repository Structure
```
├─ JLeaksDataset                   // full data
│  ├─ JLeaks.xlsx                  // detail information for each defect
│  ├─ all_bug_method.zip           // buggy methods
│  └─ all_fix_method.zip           // fixed methods
│  └─ all_bug_files.zip            // buggy files
│  └─ all_fix_files.zip            // fixed files
│  └─ bug_bytecode_files.zip       // buggy bytecode files
└─ quality                         // code used to compute uniqueness, consistency and currentness for JLeaks and DroidLeaks
│   ├─ analysis
│   │  ├─ DuplicateCodeDetector    // clone detection tool
│   │  ├─ analysis.py         
│   │  ├─ currency.py         
│   │  ├─ data.py
│   │  ├─ prepare.py
│   │  ├─ toJsonl.py
│   └─ dataset                     // input files used in computing quality
│       ├─ DroidLeaks
│       │  ├─ DroidLeaks_bug_method.zip
│       │  ├─ DroidLeaks_fix_method.zip
│       │  ├─ DroidLeaks.csv
│       └─ JLeaks
│           ├─ JLeaks_bug_method.zip
│           ├─ JLeaks_fix_method.zip
│           ├─ JLeaks.csv
└─ evaluationTools                // code used to evaluate PMD, Infer, and SpotBugs based on JLeaks
│    ├─ file
│    │  ├─ data-tool.xlsx          // save tool result for each defect
│    │  ├─ pmd_resource_leak.xml   // the custom PMD rule file        
│    │  ├─ spotbugs_filterFile.xml // the custom SpotBugs filter file      
│    ├─ toolAnalysis.py            // analysis the results of defect detection tools
│    ├─ toolResult.zip             // the results of defect detection tools
└─ openjdk8-javac                  // code used to compile defect files
│    ├─ src
│    │  ├─ com                     // modified javac code 
│    │  ├─ compile.py                  
```

## Contents of JLeaks
So far, JLeaks contains **`1,094`** real-world resource leaks from 321 open-source Java projects. Detailed information about these defects can be found in the **`JLeaks.xlsx`**.

Item  |  Description
----------------------- | -----------------------
ID                      | defect ID
projects                | Github project name in the format owner/repository (e.g., "aaberg/sql2o")
\# of commits           | the number of version control commits for the project
UTC of create           | UTC of the project creation
UTC of last modify      | UTC of last modification of the project
\# of stars             | the number of stars
\# of issues            | the number of issues
\# of forks             | the number of forks
\# of releases          | the number of releases
\# of contributors      | the number of contributes
\# of requests          | the number of requests
about                   | project description
commit url              | the URL including the commit details, defect code, and patch code
UTC of buggy commit     | UTC of defect code submission
UTC of fix commit       | UTC of fixed code submission
start line              | the start line of defect method
end line                | the end line of defect method
defect method           | the location and name of defect method (e.g., "src/main/java/org/sql2o/Query.java:executeAndFetchFirst")
change lines            | the change line between defect code and fixed code (e.g., "src/main/java/org/sql2o/Query.java:@@ -271,151 +271,180 @@")
resource types          | the type of system resource (options: **`file`**, **`socket`**, and **`thread`**)
root causes             | root causes of defect.
fix approaches          | approaches used to fixed the defect
patch correction        | indication of whether the patch is correct or not
standard libraries      | standard libraries related to defects
third-party libraries   | third-party libraries related to defects
is inter-procedural     | whether the resource leak is inter-procedural
key variable name       | the name of the key variable holding the system resource
key variable location   | the location of key variable (e.g., "src/main/java/org/sql2o/Query.java:413")
key variable attribute  | the attribute of key variable (options: **`anonymous variable`**, **`local variable`**, **`parameter`**, **`class variable`**, and **`instance variable`**) 
defect file hash        | hash value of the defect file
fix file hash           | hash value of the fixed file
defect file url         | url to download the defect file
fix file url            | url to download the fixed file

The root causes are displayed in the table below.
Causes  |  Description
------------- | -------------
noCloseEPath  | No close on exception paths
noCloseRPath  | No close on regular paths
notProClose   | Not provided close()
noCloseCPath  | No close for all branches paths

The fixed approaches are shown in the table below.
Fixed Approaches  |  Description
--------------- | ---------------
try-with        | Use try-with-resources
CloseInFinally  | Close in finally
CloseOnEPath    | Close on exception paths
CloseOnRPath    | Close on regular paths
AoRClose        | Add or rewrite close

