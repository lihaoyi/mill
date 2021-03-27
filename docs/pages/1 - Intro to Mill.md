[Mill](https://github.com/com-lihaoyi/mill) is your shiny new Java/Scala build tool!
[Scared of SBT](http://www.lihaoyi.com/post/SowhatswrongwithSBT.html)? Melancholy over Maven? Grumbling about Gradle?
Baffled by Bazel? Give Mill a try!

Mill aims for simplicity by re-using concepts you are already
[familiar with](http://www.lihaoyi.com/post/BuildToolsasPureFunctionalPrograms.html), borrowing ideas from modern tools
like [Bazel](https://bazel.build/), to let you build your projects in a way that's simple, fast, and predictable.

Mill has built in support for the [Scala](https://www.scala-lang.org/)
programming language, and can serve as a replacement for
[SBT](http://www.scala-sbt.org/), but can also be
[extended](https://com-lihaoyi.github.io/mill/page/extending-mill.html) to support any other language or platform via modules (
written in Java or Scala) or through external subprocesses.

If you are using Mill, you will probably find the following book by the Author useful in using Mill to the fullest:

- [https://handsonscala.com/](https://handsonscala.com/)

## Installation

### OS X

Installation via [homebrew](https://github.com/Homebrew/homebrew-core/blob/master/Formula/mill.rb):

```sh
brew install mill
```

### Arch Linux

Arch Linux has a [Community package for mill](https://www.archlinux.org/packages/community/any/mill/):

```bash
pacman -S mill
```

### FreeBSD

Installation via [pkg(8)](http://man.freebsd.org/pkg/8):

```sh
pkg install mill
```

### Windows

To get started, download Mill from:
https://github.com/com-lihaoyi/mill/releases/download/0.9.5/0.9.5-assembly, and save it as `mill.bat`.

If you're using [Scoop](https://scoop.sh) you can install Mill via

```bash
scoop install mill
```

Mill also works on a sh environment on Windows (e.g.,
[MSYS2](https://www.msys2.org),
[Cygwin](https://www.cygwin.com),
[Git-Bash](https://gitforwindows.org),
[WSL](https://docs.microsoft.com/en-us/windows/wsl); to get started, follow the instructions in the [manual](#manual)
section below. Note that:

* In some environments (such as WSL), Mill might have to be run without a server (using `-i`, `--interactive`
  , `--no-server`, or
  `--repl`.)

* On Cygwin, run the following after downloading Mill:

```bash
sed -i '0,/-cp "\$0"/{s/-cp "\$0"/-cp `cygpath -w "\$0"`/}; 0,/-cp "\$0"/{s/-cp "\$0"/-cp `cygpath -w "\$0"`/}' /usr/local/bin/mill
```

### Docker

You can download and run
a [Docker image containing OpenJDK, Scala and Mill](https://hub.docker.com/r/nightscape/scala-mill/) using

```bash
docker pull nightscape/scala-mill
docker run -it nightscape/scala-mill
```

### Manual

To get started, download Mill and install it into your system via the following
`curl`/`chmod` command:

```bash
sudo curl -L https://github.com/com-lihaoyi/mill/releases/download/0.9.5/0.9.5 > /usr/local/bin/mill && sudo chmod +x /usr/local/bin/mill
```

### Bootstrap Scripts (Linux/OS-X Only)

If you are using Mill in a codebase, you can commit the bootstrap launcher as a
`./mill` script in the project folder:

```bash
curl -L https://github.com/com-lihaoyi/mill/releases/download/0.9.5/0.9.5 > mill && chmod +x mill
```

Now, anyone who wants to work with the project can simply use the `./mill`
script directly:

```bash
./mill version
./mill __.compile # double-underscore
```

The `mill` command will automatically use the version specified by the bootstrap script, even if you installed it via
other means. The `./mill` file has a version number embedded within it, which you can update simply by editing the
script. Note this only works for versions 0.5.0 and above.

Bootstrap scripts are also useful for running Mill in CI, ensuring that your Jenkins/Travis/etc. box has the correct
version of Mill present to build/compile/test your code.

### millw

Instead of installing Mill directly, you can also use [lefou/millw](https://github.com/lefou/millw) as drop-in
replacement for Mill. It provides a small shell script and also a Windows batch file, that transparently downloads Mill
and executes it on your behalf. It respects various ways to configure the preferred Mill version (`MILL_VERSION` env
var, `.mill-version` file, `--mill-version` option) and can also be used as bootstrap script in your project.

### Coursier (unsupported)

Installing Mill via `coursier` or `cs` is currently not officially supported. There are various issues, especially with
interactive mode.

## Updating Mill

Once installed Mill is able to use newer or different versions for each project automatically. You don't need to install
multiple versions of Mill yourself.

See section [Overriding Mill Versions](#overriding-mill-versions) how to do it.

## Getting Started

The simplest Mill build for a Java project looks as follows:

```scala
// build.sc

import mill._, scalalib._

object foo extends JavaModule {}

```

The simplest Mill build for a Scala project looks as follows:

```scala
// build.sc

import mill._, scalalib._

object foo extends ScalaModule {
  def scalaVersion = "2.13.1"
}

```

Both of these would build a project laid out as follows:

```
build.sc
foo/
    src/
        FileA.java
        FileB.scala
    resources/
        ...
out/
    foo/
        ... 
```

You can download an example project with this layout here:

- [Example 1](https://github.com/com-lihaoyi/mill/releases/download/0.9.5/0.9.5-example-1.zip)

The source code for this module would live in the `foo/src/` folder, matching the name you assigned to the module.
Output for this module (compiled files, resolved dependency lists, ...) would live in `out/foo/`.

This can be run from the Bash shell via:

```bash
$ mill foo.compile                 # compile sources into classfiles

$ mill foo.run                     # run the main method, if any

$ mill foo.runBackground           # run the main method in the background

$ mill foo.launcher                # prepares a foo/launcher/dest/run you can run later

$ mill foo.jar                     # bundle the classfiles into a jar

$ mill foo.assembly                # bundle classfiles and all dependencies into a jar

$ mill -i foo.console              # start a Scala console within your project (in interactive mode: "-i")
 
$ mill -i foo.repl                 # start an Ammonite REPL within your project (in interactive mode: "-i")
```

You can run `mill resolve __` (double-underscore) to see a full list of the different tasks that are available, `mill resolve foo._` to see
the tasks within `foo`, `mill inspect foo.compile` to inspect a task's doc-comment documentation or what it depends on,
or `mill show foo.scalaVersion` to show the output of any task.

The most common **tasks** that Mill can run are cached **targets**, such as
`compile`, and un-cached **commands** such as `foo.run`. Targets do not re-evaluate unless one of their inputs changes,
whereas commands re-run every time.

## Output

Mill puts all its output in the top-level `out/` folder. The above commands would end up in:

```text
out/
    foo/
        compile/
        run/
        runBackground/
        launcher/
        jar/
        assembly/
```

Within the output folder for each task, there's a `meta.json` file containing the metadata returned by that task, and
a `dest/` folder containing any files that the task generates. For example, `out/foo/compile/dest/` contains the
compiled classfiles, while `out/foo/assembly/dest/` contains the self-contained assembly with the project's classfiles
jar-ed up with all its dependencies.

Given a task `foo.bar`, all its output and results can be found be within its respective `out/foo/bar/` folder.

## Multiple Modules

### Java Example

```scala
// build.sc

import mill._, scalalib._

object foo extends JavaModule

object bar extends JavaModule {
  def moduleDeps = Seq(foo)
}
```

### Scala Example

```scala
// build.sc

import mill._, scalalib._

object foo extends ScalaModule {
  def scalaVersion = "2.13.1"
}

object bar extends ScalaModule {
  def moduleDeps = Seq(foo)

  def scalaVersion = "2.13.1"
}
```

You can define multiple modules the same way you define a single module, using
`def moduleDeps` to define the relationship between them. The above builds expect the following project layout:

```
build.sc
foo/
    src/
        Main.scala
    resources/
        ...
bar/
    src/
        Main2.scala
    resources/
        ...
out/
    foo/
        ... 
    bar/
        ... 
```

And can be built/run using:

```bash
$ mill foo.compile        
$ mill bar.compile        

$ mill foo.run            
$ mill bar.run            

$ mill foo.jar            
$ mill bar.jar            

$ mill foo.assembly        
$ mill bar.assembly        
```

Mill's evaluator will ensure that the modules are compiled in the right order, and re-compiled as necessary when source
code in each module changes.

Modules can also be nested:

```scala
// build.sc

import mill._, scalalib._

object foo extends ScalaModule {
  def scalaVersion = "2.13.1"

  object bar extends ScalaModule {
    def moduleDeps = Seq(foo)

    def scalaVersion = "2.13.1"
  }

}

```

Which would result in a similarly nested project layout:

```
build.sc
foo/
    src/
        Main.scala
    resources/
        ...
    bar/
        src/
            Main2.scala
        resources/
            ...
out/
    foo/
        ...
        bar/
            ...
```

Where the nested modules can be run via:

```bash
$ mill foo.compile        
$ mill foo.bar.compile        

$ mill foo.run            
$ mill foo.bar.run            

$ mill foo.jar            
$ mill foo.bar.jar            

$ mill foo.assembly        
$ mill foo.bar.assembly        
```

## Watch and Re-evaluate

You can use the `--watch` flag to make Mill watch a task's inputs, re-evaluating the task as necessary when the inputs
change:

```bash
$ mill --watch foo.compile 
$ mill --watch foo.run 
$ mill -w foo.compile 
$ mill -w foo.run 
```

Mill's `--watch` flag watches both the files you are building using Mill, as well as Mill's own `build.sc` file and
anything it imports, so any changes to your `build.sc` will automatically get picked up.

For long-running processes like web servers, you can use `.runBackground` to make sure they re-compile and re-start when
code changes, forcefully terminating the previous process even though it may be still alive:

```bash
$ mill -w foo.compile 
$ mill -w foo.runBackground 
```

## Parallel Task Execution (Experimental)

By default, Mill will evaluate all tasks in sequence. But Mill also supports processing tasks in parallel. This
feature is currently experimental and we encourage you to report any issues you find on our bug tracker.

To enable parallel task execution, use the `--jobs` (`-j`) option followed by a number of maximal parallel threads.

Example: Use up to 4 parallel thread to compile all modules:

```bash
mill -j 4 __.compile # double-underscore
```

To use as many threads as your machine has (logical) processor cores use `--jobs 0`. To disable parallel execution
use `--jobs 1`. This is currently the default.

Please note that the maximal possible parallelism depends on your project. Tasks that depend on each other can't be
processes in parallel.

## Command-line Tools

Mill comes with a small number of useful command-line utilities built into it:

### all

```bash
mill all foo.{compile,run}
mill all "foo.{compile,run}"
mill all foo.compile foo.run
mill all _.compile # run compile for every top-level module
mill all __.compile  # run compile for every module (double-underscore)
```

`all` runs multiple tasks in a single command

### resolve

```bash
$ mill resolve _
[1/1] resolve
all
clean
foo
inspect
par
path
plan
resolve
show
shutdown
version
visualize
visualizePlan

$ mill resolve _.compile
[1/1] resolve
foo.compile

$ mill resolve foo._
[1/1] resolve
foo.allSourceFiles
foo.allSources
foo.ammoniteReplClasspath
foo.ammoniteVersion
foo.artifactId
foo.artifactName
...
```

`resolve` lists the tasks that match a particular query, without running them. This is useful for "dry running"
an `mill all` command to see what would be run before you run them, or to explore what modules or tasks are available
from the command line using `resolve _`, `resolve foo._`, etc.

```bash
mill resolve foo.{compile,run}
mill resolve "foo.{compile,run}"
mill resolve foo.compile foo.run
mill resolve _.compile          # list the compile tasks for every top-level module
mill resolve __.compile         # list the compile tasks for every module (double-underscore)
mill resolve _                  # list every top level module and task
mill resolve foo._              # list every task directly within the foo module
mill resolve __                 # list every module and task recursively (double-underscore)
```

### inspect

```bash
$ mill inspect foo.run
[1/1] inspect
foo.run(JavaModule.scala:442)
    Runs this module's code in a subprocess and waits for it to finish

Inputs:
    foo.finalMainClass
    foo.runClasspath
    foo.forkArgs
    foo.forkEnv
    foo.forkWorkingDir
```

`inspect` is a more verbose version of [resolve](#resolve). In addition to printing out the name of one-or-more tasks,
it also displays its source location and a list of input tasks. This is very useful for debugging and interactively
exploring the structure of your build from the command line.

`inspect` works with the same  `_`/`__` wildcard/query syntax used for [all](#all) and [resolve](#resolve):

```bash
mill inspect foo.compile
mill inspect foo.{compile,run}
mill inspect "foo.{compile,run}"
mill inspect foo.compile foo.run
mill inspect _.compile
mill inspect __.compile # double-underscore
mill inspect _
mill inspect foo._
mill inspect __ # double-underscore
```

### show

```bash
$ mill show foo.scalaVersion
[1/1] show
"2.13.1"
```

By default, Mill does not print out the metadata from evaluating a task. Most people would not be interested in e.g.
viewing the metadata related to incremental compilation: they just want to compile their code! However, if you want to
inspect the build to debug problems, you can make Mill show you the metadata output for a task using the `show` command:

`show` is not just for showing configuration values. All tasks return values that can be shown with `show`. e.g. `compile` returns the paths to the `classes` folder and `analysisFile` file produced by the compilation:

```bash
$ mill show foo.compile
[1/1] show
[10/25] foo.resources
{
    "analysisFile": "/Users/lihaoyi/Dropbox/Github/test//out/foo/compile/dest/zinc",
    "classes": "ref:07960649:/Users/lihaoyi/Dropbox/Github/test//out/foo/compile/dest/classes"
}
```

`show` is generally useful as a debugging tool, to see what is going on in your build:

```bash
$ mill show foo.sources
[1/1] show
[1/1] foo.sources
[
    "ref:8befb7a8:/Users/lihaoyi/Dropbox/Github/test/foo/src"
]

$ mill show foo.compileClasspath
[1/1] show
[2/11] foo.resources
[
    "ref:c984eca8:/Users/lihaoyi/Dropbox/Github/test/foo/resources",
    ".../org/scala-lang/scala-library/2.13.1/scala-library-2.13.1.jar"
]
```

`show` is also useful for interacting with Mill from external tools, since the JSON it outputs is structured and easily
parsed and manipulated.

### path

```bash
$ mill path foo.assembly foo.sources
[1/1] path
foo.sources
foo.allSources
foo.allSourceFiles
foo.compile
foo.localClasspath
foo.assembly
```

`mill path` prints out a dependency chain between the first task and the second. It is very useful for exploring the
build graph and trying to figure out how data gets from one task to another. Note that if there are multiple possible dependency
chains, one of them is picked arbitrarily.

### plan

```bash
$ mill plan foo.compileClasspath
[1/1] plan
foo.transitiveLocalClasspath
foo.resources
foo.unmanagedClasspath
foo.scalaVersion
foo.platformSuffix
foo.compileIvyDeps
foo.scalaOrganization
foo.scalaLibraryIvyDeps
foo.ivyDeps
foo.transitiveIvyDeps
foo.compileClasspath
```

`mill plan foo` shows which tasks would be evaluated, and in what order, if you ran `mill foo`, but without actually
running them. This is a useful tool for debugging your build: e.g. if you suspect a task `foo` is running things that it
shouldn't be running, a quick `mill plan` will list out all the upstream tasks that `foo` needs to run, and you can then
follow up with `mill path` on any individual upstream task to see exactly how `foo` depends on it.

### visualize

```bash
$ mill show visualize foo._
[1/1] show
[3/3] visualize
[
    ".../out/visualize/dest/out.txt",
    ".../out/visualize/dest/out.dot",
    ".../out/visualize/dest/out.json",
    ".../out/visualize/dest/out.png",
    ".../out/visualize/dest/out.svg"
]
```

`mill show visualize` takes a subset of the Mill build graph (e.g. `core._` is every task directly under the `core`
module) and draws out their relationships in `.svg` and `.png` form for you to inspect. It also generates `.txt`, `.dot`
and `.json` for easy processing by downstream tools.

The above command generates the following diagram:

![VisualizeFoo.svg](VisualizeFoo.svg)

### visualizePlan

```bash
$ mill show visualizePlan foo.compile
[1/1] show
[3/3] visualizePlan
[
    ".../out/visualizePlan/dest/out.txt",
    ".../out/visualizePlan/dest/out.dot",
    ".../out/visualizePlan/dest/out.json",
    ".../out/visualizePlan/dest/out.png",
    ".../out/visualizePlan/dest/out.svg"
]
```

`mill show visualizePlan` is similar to `mill show visualize` except that it shows a graph of the entire build plan,
including tasks not directly resolved by the query. Tasks directly resolved are shown with a solid border, and
dependencies are shown with a dotted border.

The above command generates the following diagram:

![VisualizePlan.svg](VisualizePlan.svg)

Another use case is to view the relationships between modules. For the following two modules:

```scala
// build.sc

import mill._, scalalib._

object foo extends ScalaModule {
  def scalaVersion = "2.13.1"
}

object bar extends ScalaModule {
  def moduleDeps = Seq(foo)

  def scalaVersion = "2.13.1"
}
```

`mill show visualizePlan _.compile` diagrams the relationships between the compile tasks of each module, which
illustrates which module depends on which other module's compilation output:

![VisualizeCompile.svg](VisualizeCompile.svg)

### clean

```bash
$ mill clean
```

`clean` deletes all the cached outputs of previously executed tasks. It can apply to the entire project, entire modules,
or specific tasks.

```bash
mill clean                     # clean all outputs
mill clean foo                 # clean all outputs for module 'foo' (including nested modules)
mill clean foo.compile         # only clean outputs for task 'compile' in module 'foo'
mill clean foo.{compile,run}
mill clean "foo.{compile,run}"
mill clean foo.compile foo.run
mill clean _.compile
mill clean __.compile # double-underscore
```

### Search for dependency updates

```bash
$ mill mill.scalalib.Dependency/showUpdates
```

Mill can search for updated versions of your project's dependencies, if available from your project's configured
repositories. Note that it uses heuristics based on common versionning schemes, so it may not work as expected for
dependencies with particularly weird version numbers.

Current limitations:

- Only works for `JavaModule` modules (including `ScalaModule`,
  `CrossScalaModule`, etc.) and Maven repositories.
- Always applies to all modules in the build.
- Doesn't apply to `$ivy` dependencies used in the build definition itself.

```bash
mill mill.scalalib.Dependency/showUpdates
mill mill.scalalib.Dependency/showUpdates --allowPreRelease true # also show pre-release versions
```

## IDE Support

Mill supports any IDE that is compatible with [BSP](https://build-server-protocol.github.io/), such as IntelliJ.  
Use `mill mill.bsp.BSP/install` to generate the BSP project config for your build.

It also enables Intellij to provide navigation and code-completion features within your build file itself.

## IntelliJ Support (legacy)

Mill supports IntelliJ configuration generation. Use `mill mill.scalalib.GenIdea/idea` to generate an IntelliJ project
config for your build.

This also configures IntelliJ to allow easy navigation and code-completion within your build file itself.

## The Build REPL

```bash
$ mill --repl
Loading...
@ foo
res0: foo.type = ammonite.predef.build#foo:4
Commands:
    .ideaJavaModuleFacets(ideaConfigVersion: Int)()
    .ideaConfigFiles(ideaConfigVersion: Int)()
    .ivyDepsTree(inverse: Boolean, withCompile: Boolean, withRuntime: Boolean)()
    .runLocal(args: String*)()
    .run(args: String*)()
    .runBackground(args: String*)()
    .runMainBackground(mainClass: String, args: String*)()
    .runMainLocal(mainClass: String, args: String*)()
    .runMain(mainClass: String, args: String*)()
    .console()()
    .repl(replOptions: String*)()
Targets:
...

@ foo.compile
res1: mill.package.T[mill.scalalib.api.CompilationResult] = foo.compile(ScalaModule.scala:143)
    Compiles the current module to generate compiled classfiles/bytecode

Inputs:
    foo.upstreamCompileOutput
    foo.allSourceFiles
    foo.compileClasspath
...
    
@ foo.compile()
[25/25] foo.compile
res2: mill.scalalib.api.CompilationResult = CompilationResult(
  /Users/lihaoyi/Dropbox/Github/test/out/foo/compile/dest/zinc,
  PathRef(/Users/lihaoyi/Dropbox/Github/test/out/foo/compile/dest/classes, false, -61934706)
)
```

You can run `mill --repl` to open a build REPL; this is a Scala console with your `build.sc` loaded, which lets you run
tasks interactively. The task-running syntax is slightly different from the command-line, but more in line with how you
would depend on tasks from within your build file.

You can use this REPL to interactively explore your build to see what is available.

## Deploying your code

The two most common things to do once your code is complete is to make an assembly (e.g. for deployment/installation) or
publishing (e.g. to Maven Central). Mill comes with both capabilities built in.

Mill comes with the built-in ability to make assemblies. Given a simple Mill build:

```scala
// build.sc

import mill._, scalalib._

object foo extends ScalaModule {
  def scalaVersion = "2.13.1"
}
```

You can make a self-contained assembly via:

```bash
$ mill foo.assembly

$ ls -lh out/foo/assembly/dest/out.jar
-rw-r--r--  1 lihaoyi  staff   5.0M Feb 17 11:14 out/foo/assembly/dest/out.jar
```

You can then move the `out.jar` file anywhere you would like, and run it standalone using `java`:

```bash
$ java -cp out/foo/assembly/dest/out.jar foo.Example
Hello World!
```

To publish to Maven Central, you need to make `foo` also extend Mill's
`PublishModule` trait:

```scala
// build.sc

import mill._, scalalib._, publish._

object foo extends ScalaModule with PublishModule {
  def scalaVersion = "2.13.1"

  def publishVersion = "0.0.1"

  def pomSettings = PomSettings(
    description = "Hello",
    organization = "com.lihaoyi",
    url = "https://github.com/lihaoyi/example",
    licenses = Seq(License.MIT),
    versionControl = VersionControl.github("lihaoyi", "example"),
    developers = Seq(
      Developer("lihaoyi", "Li Haoyi", "https://github.com/lihaoyi")
    )
  )
}

```

You can change the name of the published artifact (artifactId in the Maven POM)
by overriding `artifactName` in the module you want to publish.

You can download an example project with this layout here:

- [Example 2](https://github.com/com-lihaoyi/mill/releases/download/0.9.5/0.9.5-example-2.zip)

Which you can then publish using the `mill foo.publish` command, which takes your sonatype credentials (
e.g. `lihaoyi:foobarbaz`) and GPG password as inputs:

```bash
$ mill foo.publish
Missing arguments: (--sonatypeCreds: String, --release: Boolean)

Arguments provided did not match expected signature:

publish
  --sonatypeCreds   String (format: "username:password")
  --signed          Boolean (default true)
  --gpgArgs         Seq[String] (default Seq("--batch", "--yes", "-a", "-b"))
  --readTimeout     Int (default 60000)
  --release         Boolean (default true)
  --connectTimeout  Int (default 5000) 
  --awaitTimeout    Int (default 120000)
  --stagingRelease  Boolean (default true)
```

You also need to specify `release` as `true` or `false`, depending on whether you just want to stage your module
on `oss.sonatype.org` or you want Mill to complete the release process to Maven Central.

If you are publishing multiple artifacts, you can also use `mill mill.scalalib.PublishModule/publishAll` as described
[here](https://com-lihaoyi.github.io/mill/page/common-project-layouts.html#publishing)

## Structure of the `out/` folder

The `out/` folder contains all the generated files and metadata for your build. It is structured with one folder
per `Target`/`Command`, that is run, e.g.:

- `out/core/compile/`
- `out/main/test/compile/`
- `out/main/test/forkTest/`
- `out/scalalib/compile/`

There are also top-level build-related files in the `out/` folder, prefixed as
`mill-*`. The most useful is `mill-profile.json`, which logs the tasks run and time taken for the last Mill command you
executed. This is very useful if you want to find out exactly what tasks are being run and Mill is being slow.

Each folder currently contains the following files:

- `dest/`: a path for the `Task` to use either as a scratch space, or to place generated files that are returned
  using `PathRef` references. A `Task` should only output files within its own given `dest/` folder (available as `T.dest`), to avoid
  conflicting with another `Task`, but can name files within `dest/` arbitrarily.

- `log`: the `stdout`/`stderr` of the `Task`. This is also streamed to the console during evaluation.

- `meta.json`: the cache-key and JSON-serialized return-value of the
  `Target`/`Command`. The return-value can also be retrieved via `mill show foo.compile`. Binary blobs are typically not
  included in `meta.json`, and instead stored as separate binary files in `dest/` which are then referenced
  by `meta.json` via `PathRef` references.

The `out/` folder is intentionally kept simple and user-readable. If your build is not behaving as you would expect,
feel free to poke around the various
`dest/` folders to see what files are being created, or the `meta.json` files to see what is being returned by a
particular task. You can also simply delete folders within `out/` if you want to force portions of your project to be
re-built, e.g. by deleting the `out/main/` or `out/main/test/compile/` folders.

## Overriding Mill Versions

Apart from downloading and installing new versions of Mill globally, there are a few ways of selecting/updating your
Mill version:

- Create a `.mill-version` file to specify the version of Mill you wish to use:

```bash
echo "0.5.0" > .mill-version
```

`.mill-version` takes precedence over the version of Mill specified in the
`./mill` script.

- Pass in a `MILL_VERSION` environment variable, e.g.

```bash
MILL_VERSION=0.5.0-3-4faefb mill __.compile # double-underscore
```

or

```bash
MILL_VERSION=0.5.0-3-4faefb ./mill __.compile # double-underscore
```

to override the Mill version manually. This takes precedence over the version specified in `./mill` or `.mill-version`

Note that both of these overrides only work for versions 0.5.0 and above.

### Development Releases

In case you want to try out the latest features and improvements that are currently in master, unstable versions of Mill
are
[available](https://github.com/com-lihaoyi/mill/releases) as binaries named
`#.#.#-n-hash` linked to the latest tag. Installing the latest unstable release is recommended for bootstrapping Mill.

The easiest way to use a development release is by updating the [Bootstrap Script](#bootstrap-scripts-linuxos-x-only),
or
[Overriding Mill Versions](#overriding-mill-versions) via an environment variable or `.mill-version` file.

Come by our [Gitter Channel](https://gitter.im/lihaoyi/mill) if you want to ask questions or say hi!

## Running Mill with custom JVM options

It's possible to pass JVM options to the Mill launcher. To do this you need to create a `.mill-jvm-opts` file in your
project's root. This file should contain JVM options (strings, starting with `-X`), one per line. All other lines will
be ignored.

For example, if your build requires a lot of memory and bigger stack size, your `.mill-jvm-opts` could look like this:

```
-Xss10m
-Xmx10G
```

The file name for passing JVM options to the Mill launcher is configurable. If for some reason you don't want to
use `.mill-jvm-opts` file name, add `MILL_JVM_OPTS_PATH` environment variable with any other file name.
