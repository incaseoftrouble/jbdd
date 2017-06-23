# JBDD

JBDD (Java Binary Decision Diagrams) is (yet another) native implementation of BDDs in Java.
It is inspired by [JDD](https://bitbucket.org/vahidi/jdd/wiki/Home), but more or less rewritten from scratch, since JDD contained some bugs and was missing features like, for example, substitution.
The design goals are simplicity, reasonable performance and light dependencies (as of now, only [Guava](https://github.com/google/guava) is required during runtime).

This also implies that some more fancy BDD features and variants (like variable reordering or z-BDDs) are missing here, too.
They might get added over time, but if you require such features, consider using optimized implementations like [CUDD](http://vlsi.colorado.edu/~fabio/), [BuDDy](http://buddy.sourceforge.net/manual/main.html) or [Sylvan](http://fmt.cs.utwente.nl/tools/sylvan/) instead.

## Usage

You can either build the jar using gradle (see below) or fetch it from maven central:

    <dependency>
      <groupId>de.tum.in</groupId>
      <artifactId>jbdd</artifactId>
      <version>0.1.1</version>
    </dependency>

## Building

Build the project using gradle.
All dependencies are downloaded automatically.

    $ ./gradlew build

Or, if you are on windows,

    # gradlew.bat build

## Referencing

If you use JBDD for your experiments, I would appreciate a BibTeX citation akin to the following.

    @misc{jbdd,
        author = {Tobias Meggendorfer},
        title = {{JBDD}: A Java {BDD} Library},
        howpublished = "\url{https://github.com/incaseoftrouble/jbdd}",
        year = 2017
    }