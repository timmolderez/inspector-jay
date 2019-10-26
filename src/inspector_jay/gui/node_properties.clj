; Copyright (c) 2013-2015 Tim Molderez.
;
; All rights reserved. This program and the accompanying materials
; are made available under the terms of the 3-Clause BSD License
; which accompanies this distribution, and is available at
; http://www.opensource.org/licenses/BSD-3-Clause

(ns inspector-jay.gui.node-properties
  "Defines various visual properties of Inspector Jay's tree nodes"
  {:author "Tim Molderez"}
  (:require
    [clojure.string :as s]
    [clojure.java.io :as io]
    [inspector-jay.gui.utils :as utils]
    [inspector-jay.model
     [tree-node :as node]
     [tree-model :as model]]
    [seesaw.core :as seesaw])
  (:import
    [java.lang.reflect Modifier]))

(defmulti to-string
  "Retrieve a short description of a tree node"
  (fn [node] (-> node .getKind)))

(defmulti to-string-breadcrumb
  "Retrieve a string to describe a tree node in a path of breadcrumbs"
  (fn [node crumb-length] (-> node .getKind)))

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
(defmethod to-string :nil [node]
  "nil")
(defmethod to-string :method [node]
  (str
    (-> node .getMethod .getName)
    "("
    (s/join (interpose ", " (map (memfn getSimpleName) (-> node .getMethod .getParameterTypes))))
    ") : "
    (-> node .getMethod .getReturnType .getSimpleName)))
(defmethod to-string :field [node]
  (str
    (-> node .getField .getName)
    " : "
    (-> node .getValue)))

(defmethod to-string-breadcrumb :default [node crumb-length]
  (utils/truncate (-> node .getValue .toString) crumb-length))
(defmethod to-string-breadcrumb :nil [node crumb-length]
  "nil")
(defmethod to-string-breadcrumb :method [node crumb-length]
  (utils/truncate (str
    (-> node .getMethod .getName)
    "("
    (s/join (interpose ", " (map (memfn getSimpleName) (-> node .getMethod .getParameterTypes))))
    ")")
    crumb-length))
(defmethod to-string-breadcrumb :field [node crumb-length]
  (utils/truncate (str (-> node .getField .getName))
    crumb-length))

(defmethod to-string-verbose :default [node]
  (str
    (-> node .getValue .getClass)
    "\n\n"
    (to-string-value node)))
(defmethod to-string-verbose :nil [node]
  "nil")
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
  (utils/to-string-sequence (-> node .getValue)))
(defmethod to-string-value :collection [node]
  (utils/to-string-sequence (seq(-> node .getValue))))

(defmethod get-icon :default [node]
  (seesaw/icon (io/resource "icons/genericvariable_obj.gif")))
(defmethod get-icon :method [node]
  (let
    [mod (-> node .getMethod .getModifiers)]
  (cond
    (Modifier/isPublic mod) (seesaw/icon (io/resource "icons/methpub_obj.gif"))
    (Modifier/isPrivate mod) (seesaw/icon (io/resource "icons/methpri_obj.gif"))
    (Modifier/isProtected mod) (seesaw/icon (io/resource "icons/methpro_obj.gif"))
    :else (seesaw/icon (io/resource "icons/methdef_obj.gif")))))
(defmethod get-icon :field [node]
  (let
    [mod (-> node .getField .getModifiers)]
  (cond
    (Modifier/isPublic mod) (seesaw/icon (io/resource "icons/field_public_obj.gif"))
    (Modifier/isPrivate mod) (seesaw/icon (io/resource "icons/field_private_obj.gif"))
    (Modifier/isProtected mod) (seesaw/icon (io/resource "icons/field_protected_obj.gif"))
    :else (seesaw/icon (io/resource "icons/field_default_obj.gif")))))

(defmethod get-javadoc-class :default [node]
  (-> node .getValueClass))
(defmethod get-javadoc-class :method [node]
  (-> node .getMethod .getDeclaringClass))
(defmethod get-javadoc-class :field [node]
  (-> node .getField .getDeclaringClass))
