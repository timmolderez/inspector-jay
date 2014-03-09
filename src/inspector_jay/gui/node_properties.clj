; Copyright (c) 2013-2014 Tim Molderez.
;
; All rights reserved. This program and the accompanying materials
; are made available under the terms of the 3-Clause BSD License
; which accompanies this distribution, and is available at
; http://www.opensource.org/licenses/BSD-3-Clause

(ns inspector-jay.gui.node-properties
  "Defines various visual properties of Inspector Jay's tree nodes"
  {:author "Tim Molderez"}
  (:use 
    [clojure.string :only [join]]
    [clojure.java.io :only [resource]]
    [clojure.java.javadoc]
    [seesaw
     [core :exclude [tree-options]]
     [border]
     [font]]
    [inspector-jay.gui.utils]
    [inspector-jay.model
     [tree-node]
     [tree-model]])
  (:import
    [java.lang.reflect Modifier]))

(defmulti to-string
  "Retrieve a short description of a tree node"
  (fn [node] (-> node .getKind)))

(def crumb-length 32)
(defmulti to-string-breadcrumb
  "Retrieve a string to describe a tree node in a path of breadcrumbs"
  (fn [node] (-> node .getKind)))

(defmulti to-string-verbose
  "Retrieve a detailed description of a tree node"
  (fn [node] (-> node .getKind)))

(defmulti to-string-value
  "Retrieve a node's value as a string"
  (fn [node] (-> node .getCollectionKind)))

(defmulti get-icon
  "Retrieve the icon associated with a tree node"
  (fn [node] (-> node .getKind)))

(defmulti get-javadoc-class
  "Retrieve the class that should be used when looking for a node's javadoc"
  (fn [node] (-> node .getKind)))

(defmethod to-string :default [node]
  (-> node .getValue .toString))
(defmethod to-string :method [node]
  (str
    (-> node .getMethod .getName)
    "("
    (join (interpose ", " (map (memfn getSimpleName) (-> node .getMethod .getParameterTypes))))
    ") : "
    (-> node .getMethod .getReturnType .getSimpleName)))
(defmethod to-string :field [node]
  (str
    (-> node .getField .getName)
    " : "
    (-> node .getValue)))

(defmethod to-string-breadcrumb :default [node]
  (truncate (-> node .getValue .toString) crumb-length))
(defmethod to-string-breadcrumb :method [node]
  (truncate (str
    (-> node .getMethod .getName)
    "("
    (join (interpose ", " (map (memfn getSimpleName) (-> node .getMethod .getParameterTypes))))
    ")")
    crumb-length))
(defmethod to-string-breadcrumb :field [node]
  (truncate (str (-> node .getField .getName)) 
    crumb-length))

(defmethod to-string-verbose :default [node]
  (str
    (-> node .getValue .getClass)
    "\n\n"
    (to-string-value node)))
(defmethod to-string-verbose :method [node]
  (str
    (-> node .getMethod .toString)      
    (if (-> node .hasValue)
           (str 
             "\n\n" 
             (-> node .getValue .getClass .getName) " (dynamic type)"
             "\n\n" 
             (to-string-value node)))))
(defmethod to-string-verbose :field [node]
  (str 
    (-> node .getField)
    (if (-> node .hasValue)
      (str 
        "\n\n"
        (-> node .getValue .getClass .getName) " (dynamic type)"
        "\n\n"
        (to-string-value node)))))

(defmethod to-string-value :atom [node]
  (-> node .getValue .toString))
(defmethod to-string-value :sequence [node]
  (to-string-sequence (-> node .getValue)))
(defmethod to-string-value :collection [node]
  (to-string-sequence (seq(-> node .getValue))))  

(defmethod get-icon :default [node]
  (icon (resource "icons/genericvariable_obj.gif")))
(defmethod get-icon :method [node]
  (let
    [mod (-> node .getMethod .getModifiers)]
  (cond
    (Modifier/isPublic mod) (icon (resource "icons/methpub_obj.gif"))
    (Modifier/isPrivate mod) (icon (resource "icons/methpri_obj.gif"))
    (Modifier/isProtected mod) (icon (resource "icons/methpro_obj.gif"))
    :else (icon (resource "icons/methdef_obj.gif")))))
(defmethod get-icon :field [node]
  (let
    [mod (-> node .getField .getModifiers)]
  (cond
    (Modifier/isPublic mod) (icon (resource "icons/field_public_obj.gif"))
    (Modifier/isPrivate mod) (icon (resource "icons/field_private_obj.gif"))
    (Modifier/isProtected mod) (icon (resource "icons/field_protected_obj.gif"))
    :else (icon (resource "icons/field_default_obj.gif")))))

(defmethod get-javadoc-class :default [node]
  (-> node .getValueClass))
(defmethod get-javadoc-class :method [node]
  (-> node .getMethod .getDeclaringClass))
(defmethod get-javadoc-class :field [node]
  (-> node .getField .getDeclaringClass))
