(defproject inspector-jay "0.1.5"
  :description "Graphical inspector for Java objects and Clojure data structures"
  :url "https://github.com/timmolderez/inspector-jay"
  :license {:name "BSD 3-Clause License"
            :url "http://opensource.org/licenses/BSD-3-Clause"}
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [seesaw "1.4.3"
                  :exclusions [com.miglayout/miglayout
                               com.jgoodies/forms
                               org.swinglabs.swingx/swingx-core
                               org.fife.ui/rsyntaxtextarea]]
                 [net.java.balloontip/balloontip "1.2.4.1"]]
  :plugins [[codox "0.6.4"]]
  
  ; Compile these namespaces to Java
  :aot [inspector-jay.core]
  
  ; Don't include these files in the jar
  :jar-exclusions [#"images/*̀"]
  
  ; Java compiler options 
  :javac-options ["-target" "1.6" "-source" "1.6" "-Xlint:-options"])