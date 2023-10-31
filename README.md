# JBDD

![Build Status](https://github.com/incaseoftrouble/jbdd/actions/workflows/build.yml/badge.svg)

JBDD (Java Binary Decision Diagrams) is (yet another) native implementation of (Reduced Ordered) BDDs in Java.
It is loosely inspired by [JDD](https://bitbucket.org/vahidi/jdd/wiki/Home), but completely rewritten from scratch, since JDD contained some bugs and was missing features like, for example, substitution.
The design goals are simplicity, performance, and no dependencies.

## Performance

Internally, JBDD does not use any objects, only primitive arrays, and uses manual memory management.
During standard operations, JBDD does not allocate any objects.
JBDD also provides an object-oriented high-level interface with automatic reference management through weak references (instead of `finalize()`, which has a hefty performance penalty).
The overhead incurred by the object-oriented interface largely depends on the number of referenced objects, but was hardly measurable in several synthetic benchmarks.

Compared to other libraries, JBDD beats most Java implementations and is on-par or faster than some established, C-based libraries such as BuDDy.
(Measured on several synthetic benchmarks such as the N-queens problem.)

## Features

Some more fancy BDD features and variants are missing.
Most notably, these are ZDDs, MTBDDs, and variable reordering.
They might get added over time, but if you require such features, consider using optimized implementations like [CUDD](http://vlsi.colorado.edu/~fabio/), [BuDDy](http://buddy.sourceforge.net/manual/main.html) or [Sylvan](http://fmt.cs.utwente.nl/tools/sylvan/) instead.

## Usage

You can either build the jar using gradle (see below) or fetch the current version from maven central:

```kotlin
// https://mvnrepository.com/artifact/de.tum.in/jbdd
implementation("de.tum.in:jbdd:0.6.0")
```

## Building

Build the project using gradle.
All dependencies are downloaded automatically.

    $ ./gradlew build

Or, if you are on windows,

    # gradlew.bat build

## Referencing

If you use JBDD for your experiments, I would appreciate a citation akin to the following.

```
@misc{jbdd,
    author = {Tobias Meggendorfer},
    title = {{JBDD}: A Java {BDD} Library},
    howpublished = "\url{https://github.com/incaseoftrouble/jbdd}",
    year = 2017
}
```