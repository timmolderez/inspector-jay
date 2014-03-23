Inspector Jay
=============

Inspector Jay is an inspection utility that can help you to debug and/or understand Clojure/Java code. Just pass an object or data structure to the `inspect` function and you can quickly examine its contents in a simple graphical user interface.

- Inspect just about anything that is reachable from an object by navigating Inspector Jay's tree structure.
- Examine the field values of objects, as well as the return values (or even exceptions) of invoked methods.
- Similar to `clojure.inspector`, Inspector Jay can examine Clojure data structures and Java collections.
- Inspector Jay can be used in both Clojure and Java applications.

![Inspector Jay logo](https://raw.github.com/timmolderez/inspector-jay/master/resources/images/screenshot-edit.png)

### Installation

If you're using [**Leiningen**](https://github.com/technomancy/leiningen), add `[inspector-jay "0.2.5"]` to the dependencies in your `project.clj` file.

If you're using [**Maven**](http://maven.apache.org/), add the following dependency to your `pom.xml` file:

```xml
<dependency>
  <groupId>inspector-jay</groupId>
  <artifactId>inspector-jay</artifactId>
  <version>0.2.5</version>
</dependency>
```

Finally, you can also [**download Inspector Jay** as a stand-alone .jar](http://timmolderez.be/builds/inspector-jay/) file.

To find out what's new in the latest version of Inspector Jay, have a look at the [release notes](https://github.com/timmolderez/inspector-jay/blob/master/RELEASES.txt).

### Usage

If you're using **Clojure**, give these examples a try in the REPL:

```clojure
(use '(inspector-jay core))
(inspect (new java.io.File "."))
(inspect [[1 :2] {:three 3.0 :four "4"}])
```

If you're using **Java**, you can call Inspector Jay as follows:

```java
inspectorjay.InspectoryJay.inspect(new java.io.File("."));
```

### License

Inspector Jay is released under the [BSD 3-Clause license](http://opensource.org/licenses/BSD-3-Clause).
